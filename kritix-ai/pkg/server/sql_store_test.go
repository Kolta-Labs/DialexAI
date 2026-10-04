package server

import (
	"database/sql"
	"database/sql/driver"
	"fmt"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"kritix/pkg/studio"
)

// Mock SQL Driver to test SQLStateStore deterministically without external CGO dependencies
type mockDriver struct{}
type mockConn struct {
	mu       sync.Mutex
	sessions map[string]string // [tenant_id|session_id] -> data
	runs     map[string]mockRun
	audits   []mockAudit
}

type mockRun struct {
	tenantID, runID, blueprintID, status, summary string
	simulated                                     bool
	startTime, endTime                            time.Time
}

type mockAudit struct {
	id, actor, tenantID, resource, action, prevHash, hash string
	timestamp                                             time.Time
	status                                                int
}

var (
	globalMockDB = &mockConn{
		sessions: make(map[string]string),
		runs:     make(map[string]mockRun),
	}
)

func init() {
	sql.Register("mock_kritix_sql", &mockDriver{})
}

func (d *mockDriver) Open(name string) (driver.Conn, error) {
	return globalMockDB, nil
}

func (c *mockConn) Prepare(query string) (driver.Stmt, error) {
	return &mockStmt{conn: c, query: query}, nil
}

func (c *mockConn) Close() error {
	return nil
}

func (c *mockConn) Begin() (driver.Tx, error) {
	return &mockTx{}, nil
}

type mockTx struct{}

func (t *mockTx) Commit() error   { return nil }
func (t *mockTx) Rollback() error { return nil }

type mockStmt struct {
	conn  *mockConn
	query string
}

func (s *mockStmt) Close() error { return nil }
func (s *mockStmt) NumInput() int { return -1 }

func (s *mockStmt) Exec(args []driver.Value) (driver.Result, error) {
	s.conn.mu.Lock()
	defer s.conn.mu.Unlock()

	// Simple simulation of table operations
	if len(args) >= 5 && (s.query[:6] == "INSERT" || s.query[:6] == "DELETE") {
		// Session insert: tenant_id, session_id, data, created_at, updated_at
		tenantID := fmt.Sprintf("%v", args[0])
		sessionID := fmt.Sprintf("%v", args[1])
		data := fmt.Sprintf("%v", args[2])
		s.conn.sessions[tenantID+"|"+sessionID] = data
		return driver.RowsAffected(1), nil
	}
	return driver.RowsAffected(1), nil
}

func (s *mockStmt) Query(args []driver.Value) (driver.Rows, error) {
	s.conn.mu.Lock()
	defer s.conn.mu.Unlock()

	// Query kritix_sessions
	if len(args) >= 2 {
		tenantID := fmt.Sprintf("%v", args[0])
		sessionID := fmt.Sprintf("%v", args[1])
		if !strings.Contains(s.query, "tenant_id") {
			// Deliberate mutation simulation: tenant filtering omitted!
			for k, v := range s.conn.sessions {
				if strings.HasSuffix(k, "|"+sessionID) {
					return &mockRows{data: []string{v}}, nil
				}
			}
		}
		if data, ok := s.conn.sessions[tenantID+"|"+sessionID]; ok {
			return &mockRows{data: []string{data}}, nil
		}
	} else if len(args) == 1 {
		// List by tenant
		tenantID := fmt.Sprintf("%v", args[0])
		var matched []string
		for k, v := range s.conn.sessions {
			if len(k) > len(tenantID) && k[:len(tenantID)] == tenantID {
				matched = append(matched, v)
			}
		}
		return &mockRows{data: matched}, nil
	}
	return &mockRows{}, nil
}

type mockRows struct {
	data []string
	idx  int
}

func (r *mockRows) Columns() []string { return []string{"data"} }
func (r *mockRows) Close() error      { return nil }
func (r *mockRows) Next(dest []driver.Value) error {
	if r.idx >= len(r.data) {
		return io.EOF
	}
	dest[0] = r.data[r.idx]
	r.idx++
	return nil
}

func TestSQLDriversRegistered(t *testing.T) {
	drivers := sql.Drivers()
	foundPostgres := false
	for _, d := range drivers {
		if d == "postgres" {
			foundPostgres = true
			break
		}
	}
	if !foundPostgres {
		t.Fatalf("CRITICAL DEPENDENCY DEFECT: 'postgres' driver is not registered in sql.Drivers() (registered: %v)", drivers)
	}
}

func TestSQLStateStore_CrossTenantIsolation(t *testing.T) {
	db, err := sql.Open("mock_kritix_sql", "memory")
	if err != nil {
		t.Fatalf("failed to open mock db: %v", err)
	}
	defer db.Close()

	store, err := NewSQLStateStore(db)
	if err != nil {
		t.Fatalf("failed to construct SQLStateStore: %v", err)
	}
	defer store.Close()

	tenantAlpha := "tenant-alpha"
	tenantBeta := "tenant-beta"

	sess := &studio.StudioSession{
		SessionID:      "sess-isolation-001",
		JourneyName:    "Alpha Secret Journey",
		TargetURL:      "https://alpha.internal",
		StartTime:      time.Now(),
		BusinessIntent: "Alpha strictly confidential data",
	}

	if err := store.SaveSession(tenantAlpha, sess); err != nil {
		t.Fatalf("SaveSession failed: %v", err)
	}

	// 1. Authorized retrieval by tenantAlpha succeeds
	got, err := store.GetSession(tenantAlpha, sess.SessionID)
	if err != nil {
		t.Fatalf("tenantAlpha failed to retrieve own session: %v", err)
	}
	if got.SessionID != sess.SessionID {
		t.Fatalf("retrieved wrong session: expected %s, got %s", sess.SessionID, got.SessionID)
	}

	// 2. Unauthorized cross-tenant retrieval by tenantBeta MUST return ErrSessionNotFound
	leak, err := store.GetSession(tenantBeta, sess.SessionID)
	if err == nil || leak != nil {
		t.Fatalf("CRITICAL SECURITY DEFECT: Cross-tenant data leak! TenantBeta retrieved TenantAlpha's session: %v", leak)
	}
	if err != ErrSessionNotFound {
		t.Fatalf("Expected ErrSessionNotFound on cross-tenant retrieval, got: %v", err)
	}
}

func TestSQLStateStore_ConcurrencyAndRestart(t *testing.T) {
	db1, err := sql.Open("mock_kritix_sql", "memory1")
	if err != nil {
		t.Fatalf("failed to open mock db1: %v", err)
	}

	db2, err := sql.Open("mock_kritix_sql", "memory2")
	if err != nil {
		t.Fatalf("failed to open mock db2: %v", err)
	}
	defer db2.Close()

	// Instance 1
	store1, err := NewSQLStateStore(db1)
	if err != nil {
		t.Fatalf("failed to construct store1: %v", err)
	}

	// Instance 2 connected to same underlying database
	store2, err := NewSQLStateStore(db2)
	if err != nil {
		t.Fatalf("failed to construct store2: %v", err)
	}

	tenant := "tenant-concurrent"
	sess := &studio.StudioSession{
		SessionID:   "sess-restart-001",
		JourneyName: "Restart Journey",
		TargetURL:   "https://restart.internal",
		StartTime:   time.Now(),
	}

	// Write from store1
	if err := store1.SaveSession(tenant, sess); err != nil {
		t.Fatalf("store1 write failed: %v", err)
	}
	_ = store1.Close() // Simulate store1 process shutdown
	_ = db1.Close()

	// Read from store2 (persistence across instance restart)
	loaded, err := store2.GetSession(tenant, sess.SessionID)
	if err != nil {
		t.Fatalf("store2 failed to read session across restart: %v", err)
	}
	if loaded.SessionID != sess.SessionID {
		t.Fatalf("mismatched session ID: %s vs %s", loaded.SessionID, sess.SessionID)
	}
	_ = store2.Close()
}

func TestSQLStateStore_Lifecycle(t *testing.T) {
	db, err := sql.Open("mock_kritix_sql", "memory")
	if err != nil {
		t.Fatalf("failed to open mock db: %v", err)
	}
	defer db.Close()

	store, err := NewSQLStateStore(db)
	if err != nil {
		t.Fatalf("failed to construct SQLStateStore: %v", err)
	}
	defer store.Close()

	tenant := "squad-checkout"
	sess := &studio.StudioSession{
		SessionID:      "sess-sql-001",
		JourneyName:    "Checkout Journey",
		TargetURL:      "https://shop.enterprise.internal",
		StartTime:      time.Now(),
		BusinessIntent: "Verify purchase flow",
	}

	if err := store.SaveSession(tenant, sess); err != nil {
		t.Errorf("failed to save session to sql store: %v", err)
	}

	if err := store.AppendAudit(AuditRecord{
		ID:        "audit-sql-001",
		Timestamp: time.Now(),
		Actor:     "usr-admin",
		TenantID:  tenant,
		Resource:  "/api/v1/sessions",
		Action:    "CREATE",
		Status:    200,
	}); err != nil {
		t.Errorf("failed to append audit to sql store: %v", err)
	}
}
