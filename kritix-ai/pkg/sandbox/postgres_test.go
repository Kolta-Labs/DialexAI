package sandbox

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
)

// mockSQLDB implements SQLDBExecutor for unit testing PostgresDatabaseResetter
type mockSQLDB struct {
	mu           sync.Mutex
	executed     []string
	failOnQuery  string
}

func (m *mockSQLDB) ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.failOnQuery != "" && strings.Contains(query, m.failOnQuery) {
		return nil, errors.New("simulated sql execution failure")
	}
	m.executed = append(m.executed, query)
	return nil, nil
}

func (m *mockSQLDB) QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row {
	return nil
}

func (m *mockSQLDB) GetExecuted() []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	res := make([]string, len(m.executed))
	copy(res, m.executed)
	return res
}

func TestPostgresDatabaseResetter_SavepointLifecycle(t *testing.T) {
	ctx := context.Background()
	mockDB := &mockSQLDB{}

	resetter := NewPostgresDatabaseResetter(PostgresResetConfig{
		Strategy:     ResetStrategySavepoint,
		ActiveDBName: "medusa_test",
	}, mockDB)

	// 1. Create savepoint
	err := resetter.CreateSavepoint(ctx, "sp_before_checkout")
	if err != nil {
		t.Fatalf("unexpected error creating savepoint: %v", err)
	}

	executed := mockDB.GetExecuted()
	if len(executed) != 1 || !strings.Contains(executed[0], "SAVEPOINT sp_before_checkout;") {
		t.Fatalf("expected SAVEPOINT query, got %v", executed)
	}

	// 2. Rollback to valid savepoint
	err = resetter.RollbackToSavepoint(ctx, "sp_before_checkout")
	if err != nil {
		t.Fatalf("unexpected error rolling back to savepoint: %v", err)
	}

	executed = mockDB.GetExecuted()
	if len(executed) != 2 || !strings.Contains(executed[1], "ROLLBACK TO SAVEPOINT sp_before_checkout;") {
		t.Fatalf("expected ROLLBACK TO SAVEPOINT query, got %v", executed)
	}

	// 3. Rollback to nonexistent savepoint
	err = resetter.RollbackToSavepoint(ctx, "nonexistent_sp")
	if err == nil || !errors.Is(err, ErrNoActiveSavepoint) {
		t.Fatalf("expected ErrNoActiveSavepoint, got %v", err)
	}
}

func TestPostgresDatabaseResetter_TemplateDBRestore(t *testing.T) {
	ctx := context.Background()
	mockDB := &mockSQLDB{}

	resetter := NewPostgresDatabaseResetter(PostgresResetConfig{
		Strategy:       ResetStrategyTemplateDB,
		ActiveDBName:   "kritix_store_test",
		TemplateDBName: "kritix_store_template",
	}, mockDB)

	err := resetter.RestoreFromTemplateDB(ctx)
	if err != nil {
		t.Fatalf("unexpected error in RestoreFromTemplateDB: %v", err)
	}

	executed := mockDB.GetExecuted()
	if len(executed) != 3 {
		t.Fatalf("expected 3 SQL commands (terminate, drop, clone), got %d: %v", len(executed), executed)
	}

	// Step 1: terminate active backend connections
	if !strings.Contains(executed[0], "pg_terminate_backend") || !strings.Contains(executed[0], "kritix_store_test") {
		t.Errorf("expected connection termination query, got: %s", executed[0])
	}

	// Step 2: drop active database
	if !strings.Contains(executed[1], "DROP DATABASE IF EXISTS kritix_store_test;") {
		t.Errorf("expected drop database query, got: %s", executed[1])
	}

	// Step 3: clone from template
	if !strings.Contains(executed[2], "CREATE DATABASE kritix_store_test WITH TEMPLATE kritix_store_template") {
		t.Errorf("expected template clone query, got: %s", executed[2])
	}
}

func TestPostgresDatabaseResetter_ErrorHandling(t *testing.T) {
	ctx := context.Background()
	mockDB := &mockSQLDB{failOnQuery: "DROP DATABASE"}

	resetter := NewPostgresDatabaseResetter(PostgresResetConfig{
		Strategy:       ResetStrategyTemplateDB,
		ActiveDBName:   "kritix_store_test",
		TemplateDBName: "kritix_store_template",
	}, mockDB)

	err := resetter.RestoreFromTemplateDB(ctx)
	if err == nil || !errors.Is(err, ErrDatabaseResetFailed) {
		t.Fatalf("expected ErrDatabaseResetFailed on query error, got %v", err)
	}
}

func TestPostgresDatabaseResetter_ConcurrentIsolation(t *testing.T) {
	ctx := context.Background()
	mockDB := &mockSQLDB{}

	var wg sync.WaitGroup
	errCh := make(chan error, 10)

	for worker := 0; worker < 10; worker++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			r := NewPostgresDatabaseResetter(PostgresResetConfig{
				Strategy:     ResetStrategySavepoint,
				ActiveDBName: fmt.Sprintf("tenant_db_%d", w),
			}, mockDB)

			spName := fmt.Sprintf("sp_worker_%d", w)
			if err := r.CreateSavepoint(ctx, spName); err != nil {
				errCh <- err
				return
			}
			if err := r.RollbackToSavepoint(ctx, spName); err != nil {
				errCh <- err
				return
			}
		}(worker)
	}

	wg.Wait()
	close(errCh)

	for err := range errCh {
		t.Errorf("concurrent isolation error: %v", err)
	}
}

