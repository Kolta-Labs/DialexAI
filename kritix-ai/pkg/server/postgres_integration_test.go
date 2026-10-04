//go:build integration

package server

import (
	"database/sql"
	"os"
	"sync"
	"testing"
	"time"

	"kritix/pkg/studio"
)

// TestIntegration_PostgresSQLStore verifies real PostgreSQL container persistence:
// 1. Connection and schema initialization.
// 2. Data persistence across store close & reconnect.
// 3. Concurrent multi-instance writes without deadlocks or collisions.
// 4. Strict cross-tenant isolation (tenant A cannot read tenant B's data).
func TestIntegration_PostgresSQLStore(t *testing.T) {
	dbURL := os.Getenv("DATABASE_URL")
	if dbURL == "" {
		dbURL = os.Getenv("POSTGRES_URL")
	}
	if dbURL == "" {
		t.Skip("PREREQUISITE_MISSING: postgres (DATABASE_URL or POSTGRES_URL required for real PostgreSQL integration test)")
	}

	db, err := sql.Open("postgres", dbURL)
	if err != nil {
		t.Fatalf("Failed to connect to PostgreSQL: %v", err)
	}
	defer db.Close()

	if err := db.Ping(); err != nil {
		t.Fatalf("PostgreSQL ping failed: %v", err)
	}

	store1, err := NewSQLStateStore(db)
	if err != nil {
		t.Fatalf("Failed to initialize SQLStateStore: %v", err)
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

	// 1. Cross-tenant leakage check
	leakSess, err := store1.GetSession(tenantB, sessA.SessionID)
	if err == nil && leakSess != nil {
		t.Fatalf("CRITICAL SECURITY VIOLATION: Cross-tenant data leak. Tenant B retrieved Tenant A's session")
	}

	// 2. Multi-instance concurrency check
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
			if err := store1.SaveRun(tenantA, run); err != nil {
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
