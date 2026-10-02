// Package store provides JSON persistence with atomic writes, rolling backups, and API-key
// encryption at rest. It uses a platform-standard config directory rather than the legacy
// `~/.aidebate` dotfile, with a one-time, one-way import if an old file is found.
package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strconv"

	"socratix/pkg/model"
)

const backupCount = 2

// Store reads/writes the whole app state as one JSON file. Fine at this scale (a handful
// of projects/discussions on one machine) — swap for real storage if that changes.
type Store struct {
	dir        string
	file       string
	tmpFile    string
	secrets    *secretBox
	legacyPath string // overridable in tests; New() defaults it to LegacyStatePath()
}

// DefaultConfigDir is the platform-standard location for the engine's data — not the
// legacy `~/.aidebate` dotfile the Kotlin app used.
func DefaultConfigDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	switch runtime.GOOS {
	case "darwin":
		return filepath.Join(home, "Library", "Application Support", "Dialex"), nil
	case "windows":
		if appData := os.Getenv("APPDATA"); appData != "" {
			return filepath.Join(appData, "Dialex"), nil
		}
		return filepath.Join(home, "AppData", "Roaming", "Dialex"), nil
	default: // linux and everything else XDG-ish
		if xdg := os.Getenv("XDG_CONFIG_HOME"); xdg != "" {
			return filepath.Join(xdg, "dialex"), nil
		}
		return filepath.Join(home, ".config", "dialex"), nil
	}
}

// LegacyStatePath is where the Kotlin/JVM app kept its state — checked once, for import,
// never written to by the engine.
func LegacyStatePath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".aidebate", "state.json"), nil
}

// New opens (creating if needed) a Store rooted at dir, with state.json inside it.
func New(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	file := filepath.Join(dir, "state.json")
	secrets, err := newSecretBox(filepath.Join(dir, ".state.json.key"))
	if err != nil {
		return nil, err
	}
	legacyPath, err := LegacyStatePath()
	if err != nil {
		return nil, err
	}
	return &Store{dir: dir, file: file, tmpFile: file + ".tmp", secrets: secrets, legacyPath: legacyPath}, nil
}

// Dir returns the root directory of the store.
func (s *Store) Dir() string {
	return s.dir
}

// Load reads the current state, importing a legacy state.json (see LegacyStatePath) on
// first run if this store's own file doesn't exist yet and no import has happened before.
// Falls back to the newest readable backup if the main file is corrupt, then to a fresh
// AppState if nothing usable exists at all — a load never fails outright, so a bad
// state file can never crash the app.
func (s *Store) Load() (model.AppState, error) {
	if _, err := os.Stat(s.file); os.IsNotExist(err) {
		if imported, ok, importErr := s.tryImportLegacy(); importErr == nil && ok {
			return imported, nil
		}
		return model.NewAppState(), nil
	}
	data, err := os.ReadFile(s.file)
	if err == nil {
		var state model.AppState
		if err := json.Unmarshal(data, &state); err == nil {
			state.ApiKeys = decryptApiKeys(state.ApiKeys, s.secrets)
			normalizeState(&state)
			return state, nil
		}
	}
	if state, ok := s.loadFromBackup(); ok {
		return state, nil
	}
	return model.NewAppState(), nil
}

func (s *Store) loadFromBackup() (model.AppState, bool) {
	for n := 1; n <= backupCount; n++ {
		data, err := os.ReadFile(s.backupFile(n))
		if err != nil {
			continue
		}
		var state model.AppState
		if err := json.Unmarshal(data, &state); err != nil {
			continue
		}
		state.ApiKeys = decryptApiKeys(state.ApiKeys, s.secrets)
		normalizeState(&state)
		return state, true
	}
	return model.AppState{}, false
}

// Save writes state atomically (temp file, then rename over the target — a crash, kill, or
// full disk mid-write leaves the temp file damaged, never the file Load actually reads)
// with rotating backups (a *clean* write of a broken state is recoverable from one save
// back).
func (s *Store) Save(state model.AppState) error {
	toWrite := state
	keys, err := encryptApiKeys(state.ApiKeys, s.secrets)
	if err != nil {
		return err
	}
	toWrite.ApiKeys = keys
	data, err := json.MarshalIndent(toWrite, "", "  ")
	if err != nil {
		return err
	}
	s.rotateBackups()
	if err := os.WriteFile(s.tmpFile, data, 0o600); err != nil {
		return err
	}
	if err := os.Rename(s.tmpFile, s.file); err != nil {
		return err
	}
	return os.Chmod(s.file, 0o600)
}

func (s *Store) backupFile(n int) string {
	return filepath.Join(s.dir, "state.json.bak"+strconv.Itoa(n))
}

func (s *Store) rotateBackups() {
	if _, err := os.Stat(s.file); err != nil {
		return
	}
	for n := backupCount; n >= 2; n-- {
		src := s.backupFile(n - 1)
		if _, err := os.Stat(src); err == nil {
			copyFile(src, s.backupFile(n))
		}
	}
	copyFile(s.file, s.backupFile(1))
}

// tryImportLegacy checks for the old JVM app's state.json and, if found (and not already
// imported — a `.migrated` marker sits next to it once done), copies it into this store's
// location once, then makes the legacy file read-only so it's obviously deprecated without
// mutating its contents (the old app might still expect its exact shape).
func (s *Store) tryImportLegacy() (model.AppState, bool, error) {
	legacyPath := s.legacyPath
	markerPath := legacyPath + ".migrated"
	if _, err := os.Stat(markerPath); err == nil {
		return model.AppState{}, false, nil // already imported once; don't re-import
	}
	data, err := os.ReadFile(legacyPath)
	if err != nil {
		return model.AppState{}, false, nil // no legacy file — not an error, just nothing to import
	}
	var legacy model.AppState
	if err := json.Unmarshal(data, &legacy); err != nil {
		return model.AppState{}, false, err
	}
	// The old Kotlin app's own serializer omits a field that equals its default (e.g. an
	// empty transcript) rather than writing `[]` — Go decodes an absent key as a nil slice,
	// and later re-marshals that nil as JSON `null`, which a non-nullable Kotlin
	// `List<DebateMessage>` on the *client* side can't decode. Normalize before it's ever
	// saved or served.
	normalizeState(&legacy)
	// The legacy file's keys were encrypted under the JVM app's own key file
	// (~/.aidebate/.state.json.key), which this engine doesn't have — those bytes won't
	// decrypt against this store's key and decrypt() will (by design) return them as-is.
	// That's a known limitation of the import, not silent data loss: the raw ciphertext
	// just sits there unusable until the user re-enters that key in Settings.
	if err := s.Save(legacy); err != nil {
		return model.AppState{}, false, err
	}
	if err := os.WriteFile(markerPath, []byte("imported into "+s.dir+"\n"), 0o644); err != nil {
		return model.AppState{}, false, err
	}
	_ = os.Chmod(legacyPath, 0o444)
	return legacy, true, nil
}

// normalizeState turns every nil slice a decode can produce (an absent JSON key, or an
// explicit `null`) into an empty one instead. Go's own decoder is fine with nil here, but a
// nil slice re-marshals as JSON `null`, not `[]` — and a non-nullable Kotlin `List<T>` on the
// client side fails to decode that. Idempotent, safe to call on every load.
func normalizeState(state *model.AppState) {
	if state.Projects == nil {
		state.Projects = []model.Project{}
	}
	if state.Discussions == nil {
		state.Discussions = []model.Discussion{}
	}
	if state.Users == nil {
		state.Users = []model.User{}
	}
	if state.Personas == nil {
		state.Personas = []model.Persona{}
	}
	if state.CompactionSettings.CompactionThreshold <= 0 {
		state.CompactionSettings = model.DefaultCompactionSettings()
	}
	for i := range state.Discussions {
		if state.Discussions[i].Transcript == nil {
			state.Discussions[i].Transcript = []model.DebateMessage{}
		}
	}
}

func copyFile(src, dst string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	return os.WriteFile(dst, data, 0o600)
}
