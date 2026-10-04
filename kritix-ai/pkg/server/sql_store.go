package server

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"kritix/pkg/studio"
)

// SQLStateStore implements StateStore backed by an SQL database (PostgreSQL / SQLite / MySQL).
type SQLStateStore struct {
	db       *sql.DB
	mu       sync.RWMutex
	lastHash string
}

// NewSQLStateStore initializes tables and prepared statements for enterprise multi-instance persistence.
func NewSQLStateStore(db *sql.DB) (*SQLStateStore, error) {
	if db == nil {
		return nil, fmt.Errorf("sql: nil database handle")
	}

	store := &SQLStateStore{
		db:       db,
		lastHash: "0000000000000000000000000000000000000000000000000000000000000000",
	}

	if err := store.initSchema(); err != nil {
		return nil, fmt.Errorf("failed to init sql schema: %w", err)
	}

	return store, nil
}

func (s *SQLStateStore) initSchema() error {
	queries := []string{
		`CREATE TABLE IF NOT EXISTS kritix_sessions (
			tenant_id VARCHAR(128) NOT NULL,
			session_id VARCHAR(128) NOT NULL,
			data TEXT NOT NULL,
			created_at TIMESTAMP NOT NULL,
			updated_at TIMESTAMP NOT NULL,
			PRIMARY KEY (tenant_id, session_id)
		);`,
		`CREATE TABLE IF NOT EXISTS kritix_runs (
			tenant_id VARCHAR(128) NOT NULL,
			run_id VARCHAR(128) NOT NULL,
			blueprint_id VARCHAR(128) NOT NULL,
			status VARCHAR(64) NOT NULL,
			simulated BOOLEAN NOT NULL,
			start_time TIMESTAMP NOT NULL,
			end_time TIMESTAMP NOT NULL,
			summary TEXT NOT NULL,
			PRIMARY KEY (tenant_id, run_id)
		);`,
		`CREATE TABLE IF NOT EXISTS kritix_audits (
			id VARCHAR(128) PRIMARY KEY,
			timestamp TIMESTAMP NOT NULL,
			actor VARCHAR(128) NOT NULL,
			tenant_id VARCHAR(128) NOT NULL,
			resource VARCHAR(256) NOT NULL,
			action VARCHAR(64) NOT NULL,
			status INT NOT NULL,
			prev_hash VARCHAR(64) NOT NULL,
			hash VARCHAR(64) NOT NULL
		);`,
		`CREATE INDEX IF NOT EXISTS idx_kritix_audits_tenant ON kritix_audits (tenant_id, timestamp);`,
	}

	for _, q := range queries {
		if _, err := s.db.Exec(q); err != nil {
			return err
		}
	}

	// Restore last audit hash from table
	var lastHash string
	row := s.db.QueryRow(`SELECT hash FROM kritix_audits ORDER BY timestamp DESC LIMIT 1`)
	if err := row.Scan(&lastHash); err == nil && lastHash != "" {
		s.lastHash = lastHash
	}

	return nil
}

func (s *SQLStateStore) SaveSession(tenantID string, sess *studio.StudioSession) error {
	if tenantID == "" || sess == nil || sess.SessionID == "" {
		return fmt.Errorf("invalid tenant or session")
	}

	data, err := json.Marshal(sess)
	if err != nil {
		return err
	}

	now := time.Now().UTC()
	// Replace/Upsert
	_, _ = s.db.Exec(`DELETE FROM kritix_sessions WHERE tenant_id = ? AND session_id = ?`, tenantID, sess.SessionID)
	_, err = s.db.Exec(
		`INSERT INTO kritix_sessions (tenant_id, session_id, data, created_at, updated_at) VALUES (?, ?, ?, ?, ?)`,
		tenantID, sess.SessionID, string(data), sess.StartTime, now,
	)
	return err
}

func (s *SQLStateStore) GetSession(tenantID, sessionID string) (*studio.StudioSession, error) {
	if tenantID == "" || sessionID == "" {
		return nil, ErrSessionNotFound
	}

	var data string
	err := s.db.QueryRow(
		`SELECT data FROM kritix_sessions WHERE tenant_id = ? AND session_id = ?`,
		tenantID, sessionID,
	).Scan(&data)
	if err == sql.ErrNoRows {
		return nil, ErrSessionNotFound
	} else if err != nil {
		return nil, err
	}

	var sess studio.StudioSession
	if err := json.Unmarshal([]byte(data), &sess); err != nil {
		return nil, err
	}
	return &sess, nil
}

func (s *SQLStateStore) ListSessions(tenantID string) ([]*studio.StudioSession, error) {
	rows, err := s.db.Query(`SELECT data FROM kritix_sessions WHERE tenant_id = ? ORDER BY created_at DESC`, tenantID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var list []*studio.StudioSession
	for rows.Next() {
		var data string
		if err := rows.Scan(&data); err != nil {
			continue
		}
		var sess studio.StudioSession
		if err := json.Unmarshal([]byte(data), &sess); err == nil {
			list = append(list, &sess)
		}
	}
	return list, nil
}

func (s *SQLStateStore) DeleteSession(tenantID, sessionID string) error {
	res, err := s.db.Exec(`DELETE FROM kritix_sessions WHERE tenant_id = ? AND session_id = ?`, tenantID, sessionID)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrSessionNotFound
	}
	return nil
}

func (s *SQLStateStore) SaveRun(tenantID string, run StoredRun) error {
	if tenantID == "" || run.RunID == "" {
		return fmt.Errorf("invalid tenant or run")
	}

	summaryBytes, err := json.Marshal(run.Summary)
	if err != nil {
		summaryBytes = []byte("{}")
	}

	_, _ = s.db.Exec(`DELETE FROM kritix_runs WHERE tenant_id = ? AND run_id = ?`, tenantID, run.RunID)
	_, err = s.db.Exec(
		`INSERT INTO kritix_runs (tenant_id, run_id, blueprint_id, status, simulated, start_time, end_time, summary) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		tenantID, run.RunID, run.BlueprintID, run.Status, run.Simulated, run.StartTime, run.EndTime, string(summaryBytes),
	)
	return err
}

func (s *SQLStateStore) GetRun(tenantID, runID string) (*StoredRun, error) {
	var blueprintID, status, summaryStr string
	var simulated bool
	var startTime, endTime time.Time

	err := s.db.QueryRow(
		`SELECT blueprint_id, status, simulated, start_time, end_time, summary FROM kritix_runs WHERE tenant_id = ? AND run_id = ?`,
		tenantID, runID,
	).Scan(&blueprintID, &status, &simulated, &startTime, &endTime, &summaryStr)
	if err == sql.ErrNoRows {
		return nil, ErrRunNotFound
	} else if err != nil {
		return nil, err
	}

	var summary map[string]any
	_ = json.Unmarshal([]byte(summaryStr), &summary)

	return &StoredRun{
		RunID:       runID,
		TenantID:    tenantID,
		BlueprintID: blueprintID,
		Status:      status,
		Simulated:   simulated,
		StartTime:   startTime,
		EndTime:     endTime,
		Summary:     summary,
	}, nil
}

func (s *SQLStateStore) ListRuns(tenantID string) ([]StoredRun, error) {
	rows, err := s.db.Query(
		`SELECT run_id, blueprint_id, status, simulated, start_time, end_time, summary FROM kritix_runs WHERE tenant_id = ? ORDER BY start_time DESC`,
		tenantID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var list []StoredRun
	for rows.Next() {
		var runID, blueprintID, status, summaryStr string
		var simulated bool
		var startTime, endTime time.Time

		if err := rows.Scan(&runID, &blueprintID, &status, &simulated, &startTime, &endTime, &summaryStr); err != nil {
			continue
		}

		var summary map[string]any
		_ = json.Unmarshal([]byte(summaryStr), &summary)

		list = append(list, StoredRun{
			RunID:       runID,
			TenantID:    tenantID,
			BlueprintID: blueprintID,
			Status:      status,
			Simulated:   simulated,
			StartTime:   startTime,
			EndTime:     endTime,
			Summary:     summary,
		})
	}
	return list, nil
}

func (s *SQLStateStore) AppendAudit(record AuditRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	record.PrevHash = s.lastHash
	h := sha256.New()
	h.Write([]byte(fmt.Sprintf("%s|%s|%s|%s|%s|%d|%s",
		record.ID, record.Timestamp.Format(time.RFC3339Nano), record.Actor, record.TenantID, record.Resource, record.Status, record.PrevHash)))
	record.Hash = hex.EncodeToString(h.Sum(nil))
	s.lastHash = record.Hash

	_, err := s.db.Exec(
		`INSERT INTO kritix_audits (id, timestamp, actor, tenant_id, resource, action, status, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		record.ID, record.Timestamp, record.Actor, record.TenantID, record.Resource, record.Action, record.Status, record.PrevHash, record.Hash,
	)
	return err
}

func (s *SQLStateStore) ListAudits(tenantID string) ([]AuditRecord, error) {
	rows, err := s.db.Query(
		`SELECT id, timestamp, actor, tenant_id, resource, action, status, prev_hash, hash FROM kritix_audits WHERE tenant_id = ? ORDER BY timestamp ASC`,
		tenantID,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var list []AuditRecord
	for rows.Next() {
		var r AuditRecord
		if err := rows.Scan(&r.ID, &r.Timestamp, &r.Actor, &r.TenantID, &r.Resource, &r.Action, &r.Status, &r.PrevHash, &r.Hash); err != nil {
			continue
		}
		list = append(list, r)
	}
	return list, nil
}

func (s *SQLStateStore) Close() error {
	return s.db.Close()
}
