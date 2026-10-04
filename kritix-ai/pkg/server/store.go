package server

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"kritix/pkg/studio"
)

var (
	ErrSessionNotFound     = errors.New("store: session not found")
	ErrRunNotFound         = errors.New("store: workflow run not found")
	ErrCrossTenantAccess   = errors.New("store: cross-tenant access violation detected")
)

// AuditRecord models a durable per-request audit entry anchored into the persistent store.
type AuditRecord struct {
	ID        string    `json:"id"`
	Timestamp time.Time `json:"timestamp"`
	Actor     string    `json:"actor"`
	TenantID  string    `json:"tenant_id"`
	Resource  string    `json:"resource"`
	Action    string    `json:"action"`
	Status    int       `json:"status"`
	PrevHash  string    `json:"prev_hash"`
	Hash      string    `json:"hash"`
}

// StoredRun models a persisted workflow execution run.
type StoredRun struct {
	RunID       string         `json:"run_id"`
	TenantID    string         `json:"tenant_id"`
	BlueprintID string         `json:"blueprint_id"`
	Status      string         `json:"status"`
	Simulated   bool           `json:"simulated"`
	StartTime   time.Time      `json:"start_time"`
	EndTime     time.Time      `json:"end_time"`
	Summary     map[string]any `json:"summary"`
}

// StateStore defines the enterprise persistence contract across single/multi-instance deployments.
type StateStore interface {
	SaveSession(tenantID string, sess *studio.StudioSession) error
	GetSession(tenantID, sessionID string) (*studio.StudioSession, error)
	ListSessions(tenantID string) ([]*studio.StudioSession, error)
	DeleteSession(tenantID, sessionID string) error

	SaveRun(tenantID string, run StoredRun) error
	GetRun(tenantID, runID string) (*StoredRun, error)
	ListRuns(tenantID string) ([]StoredRun, error)

	AppendAudit(record AuditRecord) error
	ListAudits(tenantID string) ([]AuditRecord, error)

	Close() error
}

// PersistentFileStore implements StateStore backed by durable, atomic file/directory storage.
type PersistentFileStore struct {
	mu       sync.RWMutex
	baseDir  string
	lastHash string
}

// NewPersistentFileStore initializes a persistent store at the given directory path.
func NewPersistentFileStore(baseDir string) (*PersistentFileStore, error) {
	if baseDir == "" {
		baseDir = ".kritix/store"
	}
	if err := os.MkdirAll(baseDir, 0700); err != nil {
		return nil, fmt.Errorf("failed to initialize store directory %s: %w", baseDir, err)
	}

	for _, sub := range []string{"sessions", "runs", "audit"} {
		if err := os.MkdirAll(filepath.Join(baseDir, sub), 0700); err != nil {
			return nil, err
		}
	}

	return &PersistentFileStore{
		baseDir:  baseDir,
		lastHash: "0000000000000000000000000000000000000000000000000000000000000000",
	}, nil
}

func (s *PersistentFileStore) sessionPath(tenantID, sessionID string) string {
	return filepath.Join(s.baseDir, "sessions", fmt.Sprintf("%s_%s.json", tenantID, sessionID))
}

func (s *PersistentFileStore) runPath(tenantID, runID string) string {
	return filepath.Join(s.baseDir, "runs", fmt.Sprintf("%s_%s.json", tenantID, runID))
}

func (s *PersistentFileStore) SaveSession(tenantID string, sess *studio.StudioSession) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if tenantID == "" {
		tenantID = "default-squad"
	}
	if sess == nil {
		return errors.New("sess cannot be nil")
	}

	path := s.sessionPath(tenantID, sess.SessionID)
	data, err := json.MarshalIndent(sess, "", "  ")
	if err != nil {
		return err
	}

	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmpPath, path)
}

func (s *PersistentFileStore) GetSession(tenantID, sessionID string) (*studio.StudioSession, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if tenantID == "" {
		tenantID = "default-squad"
	}
	path := s.sessionPath(tenantID, sessionID)
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrSessionNotFound
		}
		return nil, err
	}

	var sess studio.StudioSession
	if err := json.Unmarshal(data, &sess); err != nil {
		return nil, err
	}
	return &sess, nil
}

func (s *PersistentFileStore) ListSessions(tenantID string) ([]*studio.StudioSession, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if tenantID == "" {
		tenantID = "default-squad"
	}

	var sessions []*studio.StudioSession
	prefix := tenantID + "_"
	sessDir := filepath.Join(s.baseDir, "sessions")

	entries, err := os.ReadDir(sessDir)
	if err != nil {
		return nil, err
	}

	for _, entry := range entries {
		if entry.IsDir() || !filepath.HasPrefix(entry.Name(), prefix) || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(sessDir, entry.Name()))
		if err != nil {
			continue
		}
		var sess studio.StudioSession
		if err := json.Unmarshal(data, &sess); err == nil {
			sessions = append(sessions, &sess)
		}
	}

	return sessions, nil
}

func (s *PersistentFileStore) DeleteSession(tenantID, sessionID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if tenantID == "" {
		tenantID = "default-squad"
	}
	path := s.sessionPath(tenantID, sessionID)
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (s *PersistentFileStore) SaveRun(tenantID string, run StoredRun) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if tenantID == "" {
		tenantID = "default-squad"
	}
	run.TenantID = tenantID
	path := s.runPath(tenantID, run.RunID)

	data, err := json.MarshalIndent(run, "", "  ")
	if err != nil {
		return err
	}

	tmpPath := path + ".tmp"
	if err := os.WriteFile(tmpPath, data, 0600); err != nil {
		return err
	}
	return os.Rename(tmpPath, path)
}

func (s *PersistentFileStore) GetRun(tenantID, runID string) (*StoredRun, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if tenantID == "" {
		tenantID = "default-squad"
	}
	path := s.runPath(tenantID, runID)
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, ErrRunNotFound
		}
		return nil, err
	}

	var run StoredRun
	if err := json.Unmarshal(data, &run); err != nil {
		return nil, err
	}
	return &run, nil
}

func (s *PersistentFileStore) ListRuns(tenantID string) ([]StoredRun, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if tenantID == "" {
		tenantID = "default-squad"
	}

	var runs []StoredRun
	prefix := tenantID + "_"
	runsDir := filepath.Join(s.baseDir, "runs")

	entries, err := os.ReadDir(runsDir)
	if err != nil {
		return nil, err
	}

	for _, entry := range entries {
		if entry.IsDir() || !filepath.HasPrefix(entry.Name(), prefix) || filepath.Ext(entry.Name()) != ".json" {
			continue
		}
		data, err := os.ReadFile(filepath.Join(runsDir, entry.Name()))
		if err != nil {
			continue
		}
		var run StoredRun
		if err := json.Unmarshal(data, &run); err == nil {
			runs = append(runs, run)
		}
	}

	return runs, nil
}

func (s *PersistentFileStore) AppendAudit(record AuditRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if record.Timestamp.IsZero() {
		record.Timestamp = time.Now().UTC()
	}
	record.PrevHash = s.lastHash

	payload := fmt.Sprintf("%s|%s|%s|%s|%s|%d|%s",
		record.ID, record.Timestamp.Format(time.RFC3339Nano), record.Actor, record.TenantID, record.Resource, record.Status, record.PrevHash)
	h := sha256.Sum256([]byte(payload))
	record.Hash = hex.EncodeToString(h[:])
	s.lastHash = record.Hash

	auditFile := filepath.Join(s.baseDir, "audit", "audit_log.jsonl")
	f, err := os.OpenFile(auditFile, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer f.Close()

	line, err := json.Marshal(record)
	if err != nil {
		return err
	}
	_, err = f.Write(append(line, '\n'))
	return err
}

func (s *PersistentFileStore) ListAudits(tenantID string) ([]AuditRecord, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	auditFile := filepath.Join(s.baseDir, "audit", "audit_log.jsonl")
	data, err := os.ReadFile(auditFile)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}

	var records []AuditRecord
	lines := splitLines(data)
	for _, l := range lines {
		if len(l) == 0 {
			continue
		}
		var r AuditRecord
		if err := json.Unmarshal(l, &r); err == nil {
			if tenantID == "" || r.TenantID == tenantID {
				records = append(records, r)
			}
		}
	}

	return records, nil
}

func (s *PersistentFileStore) Close() error {
	return nil
}

func splitLines(data []byte) [][]byte {
	var lines [][]byte
	start := 0
	for i, b := range data {
		if b == '\n' {
			lines = append(lines, data[start:i])
			start = i + 1
		}
	}
	if start < len(data) {
		lines = append(lines, data[start:])
	}
	return lines
}
