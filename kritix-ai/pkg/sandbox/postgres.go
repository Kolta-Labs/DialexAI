package sandbox

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sync"
	"time"
)

var (
	ErrDatabaseResetFailed   = errors.New("postgres database reset failed")
	ErrNoActiveSavepoint     = errors.New("no active savepoint found with specified name")
	ErrTemplateDBUnavailable = errors.New("template database does not exist or connection failed")
)

// PostgresResetStrategy specifies how single-DB Docker Compose PostgreSQL is rolled back.
type PostgresResetStrategy string

const (
	// ResetStrategySavepoint rolls back to a named SQL transaction savepoint.
	ResetStrategySavepoint PostgresResetStrategy = "savepoint"

	// ResetStrategyTemplateDB drops the active test database and clones from a pristine template DB.
	ResetStrategyTemplateDB PostgresResetStrategy = "template_db"
)

// PostgresResetConfig holds configuration for PostgreSQL container rollback.
type PostgresResetConfig struct {
	Strategy       PostgresResetStrategy `json:"strategy"`
	ActiveDBName   string                `json:"active_db_name"`
	TemplateDBName string                `json:"template_db_name"`
	AdminDSN       string                `json:"admin_dsn,omitempty"`
}

// SQLDBExecutor abstracts standard database operations for unit testing and live database execution.
type SQLDBExecutor interface {
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
}

// PostgresDatabaseResetter executes clean state rollback for single-instance Docker Compose PostgreSQL.
type PostgresDatabaseResetter struct {
	mu         sync.RWMutex
	cfg        PostgresResetConfig
	db         SQLDBExecutor
	savepoints map[string]time.Time
}

// NewPostgresDatabaseResetter creates a resetter for Docker Compose PostgreSQL.
func NewPostgresDatabaseResetter(cfg PostgresResetConfig, db SQLDBExecutor) *PostgresDatabaseResetter {
	if cfg.Strategy == "" {
		cfg.Strategy = ResetStrategySavepoint
	}
	if cfg.ActiveDBName == "" {
		cfg.ActiveDBName = "app_test"
	}
	if cfg.TemplateDBName == "" {
		cfg.TemplateDBName = "app_template"
	}
	return &PostgresDatabaseResetter{
		cfg:        cfg,
		db:         db,
		savepoints: make(map[string]time.Time),
	}
}

// CreateSavepoint creates a named transaction savepoint.
func (p *PostgresDatabaseResetter) CreateSavepoint(ctx context.Context, name string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.db == nil {
		return errors.New("database connection not initialized")
	}

	query := fmt.Sprintf("SAVEPOINT %s;", name)
	if _, err := p.db.ExecContext(ctx, query); err != nil {
		return fmt.Errorf("%w: failed to create savepoint %q: %v", ErrDatabaseResetFailed, name, err)
	}

	p.savepoints[name] = time.Now()
	return nil
}

// RollbackToSavepoint rolls back the transaction to the specified savepoint.
func (p *PostgresDatabaseResetter) RollbackToSavepoint(ctx context.Context, name string) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.db == nil {
		return errors.New("database connection not initialized")
	}

	if _, ok := p.savepoints[name]; !ok {
		return fmt.Errorf("%w: %s", ErrNoActiveSavepoint, name)
	}

	query := fmt.Sprintf("ROLLBACK TO SAVEPOINT %s;", name)
	if _, err := p.db.ExecContext(ctx, query); err != nil {
		return fmt.Errorf("%w: failed to rollback to savepoint %q: %v", ErrDatabaseResetFailed, name, err)
	}

	return nil
}

// RestoreFromTemplateDB drops the active test DB and recreates it from the pristine template DB.
func (p *PostgresDatabaseResetter) RestoreFromTemplateDB(ctx context.Context) error {
	p.mu.Lock()
	defer p.mu.Unlock()

	if p.db == nil {
		return errors.New("admin database connection not initialized")
	}

	// 1. Terminate all active backend connections to the active test DB
	termQuery := fmt.Sprintf("SELECT pg_terminate_backend(pg_stat_activity.pid) FROM pg_stat_activity WHERE pg_stat_activity.datname = %q AND pid <> pg_backend_pid();", p.cfg.ActiveDBName)
	_, _ = p.db.ExecContext(ctx, termQuery)

	// 2. Drop the modified database
	dropQuery := fmt.Sprintf("DROP DATABASE IF EXISTS %s;", p.cfg.ActiveDBName)
	if _, err := p.db.ExecContext(ctx, dropQuery); err != nil {
		return fmt.Errorf("%w: failed to drop database %s: %v", ErrDatabaseResetFailed, p.cfg.ActiveDBName, err)
	}

	// 3. Create fresh database cloned from template DB
	createQuery := fmt.Sprintf("CREATE DATABASE %s WITH TEMPLATE %s OWNER postgres;", p.cfg.ActiveDBName, p.cfg.TemplateDBName)
	if _, err := p.db.ExecContext(ctx, createQuery); err != nil {
		return fmt.Errorf("%w: failed to clone database from template %s: %v", ErrDatabaseResetFailed, p.cfg.TemplateDBName, err)
	}

	return nil
}
