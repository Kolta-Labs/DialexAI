package server

import (
	"database/sql"
	"database/sql/driver"
	"fmt"
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
	return &mockRows{}, nil
}

type mockRows struct{}

func (r *mockRows) Columns() []string              { return []string{"hash"} }
func (r *mockRows) Close() error                   { return nil }
func (r *mockRows) Next(dest []driver.Value) error { return fmt.Errorf("EOF") }

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
