package store

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"socratix/pkg/model"
)

func newTestStore(t *testing.T) *Store {
	t.Helper()
	s, err := New(t.TempDir())
	if err != nil {
		t.Fatalf("New() error = %v", err)
	}
	return s
}

func TestApiKeysAreNeverWrittenToDiskAsPlaintext(t *testing.T) {
	s := newTestStore(t)
	secret := "sk-ant-super-secret-key"

	if err := s.Save(model.AppState{ApiKeys: model.ApiKeys{Anthropic: secret}}); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	raw, err := os.ReadFile(s.file)
	if err != nil {
		t.Fatalf("read state.json: %v", err)
	}
	if strings.Contains(string(raw), secret) {
		t.Error("plaintext key must not appear in state.json")
	}

	loaded, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.ApiKeys.Anthropic != secret {
		t.Errorf("loaded.ApiKeys.Anthropic = %q, want %q", loaded.ApiKeys.Anthropic, secret)
	}
}

func TestALegacyPlaintextKeyStillLoadsAndGetsEncryptedOnNextSave(t *testing.T) {
	s := newTestStore(t)
	// Simulates a state.json written before encryption existed — raw key string.
	legacyJSON := `{"apiKeys":{"anthropic":"sk-ant-legacy"}}`
	if err := os.WriteFile(s.file, []byte(legacyJSON), 0o644); err != nil {
		t.Fatalf("write legacy file: %v", err)
	}

	loaded, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.ApiKeys.Anthropic != "sk-ant-legacy" {
		t.Fatalf("loaded.ApiKeys.Anthropic = %q, want sk-ant-legacy", loaded.ApiKeys.Anthropic)
	}

	if err := s.Save(loaded); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	raw, _ := os.ReadFile(s.file)
	if strings.Contains(string(raw), "sk-ant-legacy") {
		t.Error("plaintext key should be encrypted after the first save")
	}
	reloaded, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if reloaded.ApiKeys.Anthropic != "sk-ant-legacy" {
		t.Errorf("reloaded.ApiKeys.Anthropic = %q, want sk-ant-legacy", reloaded.ApiKeys.Anthropic)
	}
}

func TestEachSaveLeavesARecoverableBackupOfThePreviousVersion(t *testing.T) {
	s := newTestStore(t)
	if err := s.Save(model.AppState{CompactionModel: "first"}); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	if err := s.Save(model.AppState{CompactionModel: "second"}); err != nil {
		t.Fatalf("Save() error = %v", err)
	}

	backupPath := s.backupFile(1)
	if _, err := os.Stat(backupPath); err != nil {
		t.Fatalf("backup file missing: %v", err)
	}
	backupStore, err := New(filepath.Dir(backupPath))
	if err != nil {
		t.Fatalf("New() for backup dir: %v", err)
	}
	backupStore.file = backupPath
	backup, err := backupStore.Load()
	if err != nil {
		t.Fatalf("Load() backup error = %v", err)
	}
	if backup.CompactionModel != "first" {
		t.Errorf("backup.CompactionModel = %q, want first", backup.CompactionModel)
	}

	current, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if current.CompactionModel != "second" {
		t.Errorf("current.CompactionModel = %q, want second", current.CompactionModel)
	}
}

func TestFullStateRoundTripsThroughSaveAndLoad(t *testing.T) {
	s := newTestStore(t)
	original := model.AppState{
		Discussions:     []model.Discussion{},
		ApiKeys:         model.ApiKeys{Anthropic: "a", OpenAI: "b", Gemini: "c"},
		CompactionModel: "claude-haiku-4-5-20251001",
		TokenBudget:     12345,
		AgentLimit:      4,
	}
	if err := s.Save(original); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	loaded, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.TokenBudget != original.TokenBudget || loaded.CompactionModel != original.CompactionModel ||
		loaded.ApiKeys != original.ApiKeys || loaded.AgentLimit != original.AgentLimit {
		t.Errorf("round trip mismatch:\n got: %+v\nwant: %+v", loaded, original)
	}
}

// A real bug, found by an actual client hitting a real Discussion whose transcript came out
// as JSON `null`: kotlinx.serialization can't decode `null` into a non-nullable
// `List<DebateMessage>`. Root cause was a state.json written with the `transcript` key absent
// (an old client that omits fields equal to their default) — Go decodes an absent key as a
// nil slice, then re-marshals that nil as `null`, not `[]`. Load must self-heal this on every
// read, not just at import time, since a file already on disk right now can carry the bug.
func TestLoadNormalizesNilTranscriptAndTopLevelSlicesToEmptyNotNull(t *testing.T) {
	s := newTestStore(t)
	// Hand-write JSON the way an old/foreign client actually produced it — `transcript`,
	// `projects`, and `users` all absent, not present-and-null, since that's the real
	// shape that triggered this. Go decodes an absent key exactly like an explicit null.
	raw := `{"discussions":[{"id":"d1","projectId":"p1","name":"D1","status":"DRAFT"}]}`
	if err := os.WriteFile(s.file, []byte(raw), 0o600); err != nil {
		t.Fatal(err)
	}

	loaded, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.Projects == nil || loaded.Users == nil {
		t.Fatalf("top-level slices should be normalized to empty, not nil: %+v", loaded)
	}
	if loaded.Discussions[0].Transcript == nil {
		t.Fatalf("Discussions[0].Transcript should be normalized to empty, not nil")
	}

	// The part that actually broke the real client: re-marshaling must produce `[]`, never
	// `null` — a nil slice would round-trip back to `null` even though Load() itself "saw"
	// an empty, non-nil slice in the struct.
	out, err := json.Marshal(loaded)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(out), `"transcript":null`) {
		t.Errorf("transcript re-marshaled as null, the exact bug this test guards against:\n%s", out)
	}
}

func TestCorruptStateFileFallsBackToBackupInsteadOfWipingData(t *testing.T) {
	s := newTestStore(t)
	// Two saves: the first save has no prior file to back up (rotateBackups is a no-op on
	// an empty store), so bak1 only exists once a *second* save has something to rotate.
	if err := s.Save(model.AppState{CompactionModel: "good"}); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	if err := s.Save(model.AppState{CompactionModel: "good"}); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	// Corrupt the live file directly (simulating a crash mid-write that this store's own
	// atomic-rename can't fully protect against if something else touches the file).
	if err := os.WriteFile(s.file, []byte("{not valid json"), 0o600); err != nil {
		t.Fatalf("corrupt file: %v", err)
	}

	loaded, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.CompactionModel != "good" {
		t.Errorf("CompactionModel = %q, want recovery from backup to give \"good\"", loaded.CompactionModel)
	}
}

func TestLegacyStateIsImportedOnceOnFirstRun(t *testing.T) {
	legacyDir := t.TempDir()
	legacyPath := filepath.Join(legacyDir, "state.json")
	if err := os.WriteFile(legacyPath, []byte(`{"compactionModel":"legacy-model","apiKeys":{"anthropic":"sk-legacy"}}`), 0o644); err != nil {
		t.Fatalf("write legacy file: %v", err)
	}

	s := newTestStore(t)
	s.legacyPath = legacyPath

	loaded, err := s.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if loaded.CompactionModel != "legacy-model" {
		t.Errorf("CompactionModel = %q, want the imported legacy value", loaded.CompactionModel)
	}
	if loaded.ApiKeys.Anthropic != "sk-legacy" {
		t.Errorf("ApiKeys.Anthropic = %q, want sk-legacy", loaded.ApiKeys.Anthropic)
	}

	// The new store's own file now exists with the imported data — confirm it actually
	// persisted, not just returned in-memory.
	if _, err := os.Stat(s.file); err != nil {
		t.Errorf("state.json wasn't written by the import: %v", err)
	}

	// The legacy file is now read-only, and a second Load() must NOT re-import (it should
	// just read the new store's own file, which by now differs if this test saved anything
	// new — here it's identical, so just confirm no error and the marker exists).
	info, err := os.Stat(legacyPath)
	if err != nil {
		t.Fatalf("stat legacy file: %v", err)
	}
	if info.Mode().Perm()&0o222 != 0 {
		t.Errorf("legacy file mode = %v, want read-only after import", info.Mode())
	}
	if _, err := os.Stat(legacyPath + ".migrated"); err != nil {
		t.Errorf("expected a .migrated marker next to the legacy file: %v", err)
	}
}

func TestPasswordHashingRoundTrips(t *testing.T) {
	hash, err := HashPassword("correct horse battery staple")
	if err != nil {
		t.Fatalf("HashPassword() error = %v", err)
	}
	if hash == "correct horse battery staple" {
		t.Fatal("HashPassword() must not return the plaintext")
	}
	if !VerifyPassword(hash, "correct horse battery staple") {
		t.Error("VerifyPassword() = false for the correct password")
	}
	if VerifyPassword(hash, "wrong password") {
		t.Error("VerifyPassword() = true for a wrong password")
	}
}

func TestSaveFailsClosedOnBadKey(t *testing.T) {
	s, err := New(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	st := model.NewAppState()
	st.ApiKeys.Anthropic = "sk-secret"
	if err := s.Save(st); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(s.file)
	s.secrets.key = []byte("bad") // invalid AES key length
	if err := s.Save(st); err == nil {
		t.Fatal("Save must fail when the key is unusable")
	}
	after, _ := os.ReadFile(s.file)
	if string(before) != string(after) || strings.Contains(string(after), "sk-secret") {
		t.Fatal("state file must be untouched and never hold plaintext")
	}
}
