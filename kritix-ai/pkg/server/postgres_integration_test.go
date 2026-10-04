//go:build integration

package server

import (
	"database/sql"
	"os"
	"sync"
	"testing"
	"time"

	_ "github.com/lib/pq"
	"kritix/pkg/studio"
)

// TestIntegration_PostgresSQLStore verifies real PostgreSQL container persistence:
// 1. Database driver registration in sql.Drivers().
// 2. Real PostgreSQL connection and schema initialization.
// 3. Data persistence across store close & reconnect (process restart simulation).
// 4. Concurrent multi-instance writes and reads across separate store instances without deadlocks.
// 5. Strict cross-tenant isolation (tenant A data is invisible to tenant B).
func TestIntegration_PostgresSQLStore(t *testing.T) {
	// 1. Dependency gate: 'postgres' driver must be registered
	foundPostgres := false
	for _, d := range sql.Drivers() {
		if d == "postgres" {
			foundPostgres = true
			break
		}
	}
	if !foundPostgres {
		t.Fatalf("CRITICAL DEPENDENCY DEFECT: 'postgres' driver not registered in sql.Drivers() (registered: %v)", sql.Drivers())
	}

	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		dbURL = os.Getenv("POSTGRES_URL")
	}
	if dbURL == "" {
		if os.Getenv("KRITIX_HARNESS") == "1" {
			t.Fatalf("PREREQUISITE_MISSING: postgres (DATABASE_URL or POSTGRES_URL required in harness mode)")
		}
		t.Skip("PREREQUISITE_MISSING: postgres (DATABASE_URL or POSTGRES_URL required for real PostgreSQL integration test)")
	}

	db1, err := sql.Open("postgres", dbURL)
	if err != nil {
		t.Fatalf("Failed to connect to PostgreSQL: %v", err)
	}
	defer db1.Close()

	if err := db1.Ping(); err != nil {
		t.Fatalf("PostgreSQL ping failed: %v", err)
	}

	// Instance 1
	store1, err := NewSQLStateStore(db1)
	if err != nil {
		t.Fatalf("Failed to initialize SQLStateStore store1: %v", err)
	}

	// Instance 2 (concurrent instance on separate connection)
	db2, err := sql.Open("postgres", dbURL)
	if err != nil {
		t.Fatalf("Failed to connect to PostgreSQL for instance 2: %v", err)
	}
	defer db2.Close()

	store2, err := NewSQLStateStore(db2)
	if err != nil {
		t.Fatalf("Failed to initialize SQLStateStore store2: %v", err)
	}

	tenantA := "tenant-alpha"
	tenantB := "tenant-beta"

	sessA := &studio.StudioSession{
		SessionID:   "sess-alpha-001",
		JourneyName: "Checkout Alpha",
		TargetURL:   "https://alpha.example.com",
		StartTime:   time.Now().UTC(),
	}

	if err := store1.SaveSession(tenantA, sessA); err != nil {
		t.Fatalf("store1.SaveSession failed: %v", err)
	}

	// 2. Cross-tenant leakage check
	leakSess, err := store1.GetSession(tenantB, sessA.SessionID)
	if err == nil && leakSess != nil {
		t.Fatalf("CRITICAL SECURITY VIOLATION: Cross-tenant data leak. Tenant B retrieved Tenant A's session")
	}
	if err != ErrSessionNotFound {
		t.Fatalf("Expected ErrSessionNotFound on cross-tenant read, got: %v", err)
	}

	// 3. Process restart persistence verification: close store1, read from store2
	_ = store1.Close()
	_ = db1.Close()

	loadedSess, err := store2.GetSession(tenantA, sessA.SessionID)
	if err != nil {
		t.Fatalf("store2 failed to read session after store1 shutdown: %v", err)
	}
	if loadedSess.SessionID != sessA.SessionID {
		t.Fatalf("Mismatched session ID after restart: %s vs %s", loadedSess.SessionID, sessA.SessionID)
	}

	// 4. Multi-instance concurrent writes and reads
	var wg sync.WaitGroup
	errCh := make(chan error, 10)

	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			run := StoredRun{
				RunID:       "run-concurrent-" + string(rune('0'+idx)),
				BlueprintID: "pr-smoke-guard",
				Status:      "passed",
				StartTime:   time.Now().UTC(),
			}
			targetStore := store2
			if idx%2 == 0 {
				targetStore = store1
			}
			if err := targetStore.SaveRun(tenantA, run); err != nil {
				errCh <- err
			}
		}(i)
	}
	wg.Wait()
	close(errCh)

	for err := range errCh {
		t.Errorf("Concurrent write error: %v", err)
	}
}
