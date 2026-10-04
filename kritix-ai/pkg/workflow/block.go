package workflow

import (
	"context"
	"fmt"
	"sync"
	"time"

	"kritix/pkg/security"
)

// BlockStatus represents the state of a block after execution.
type BlockStatus string

const (
	StatusPassed            BlockStatus = "PASSED"
	StatusPassedWithHealing BlockStatus = "PASSED_WITH_HEALING"
	StatusFailed            BlockStatus = "FAILED"
	StatusQuarantined       BlockStatus = "QUARANTINED"
	StatusSkipped           BlockStatus = "SKIPPED"
	// Legacy alias
	StatusSuccess BlockStatus = "PASSED"
)

// BlockDescriptor provides human-readable and categorical metadata for a block.
type BlockDescriptor struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Category    string `json:"category"` // "ingest", "review", "analysis", "synth", "exec", "sync"
	Description string `json:"description"`
	Simulated   bool   `json:"simulated,omitempty"`
}

// BlockResult encapsulates the execution outcome of a single block.
type BlockResult struct {
	BlockID   string                 `json:"block_id"`
	Status    BlockStatus            `json:"status"`
	Message   string                 `json:"message"`
	Simulated bool                   `json:"simulated,omitempty"`
	Data      map[string]interface{} `json:"data,omitempty"`
	Artifacts []string               `json:"artifacts,omitempty"`
	Duration  time.Duration          `json:"duration"`
	Error     error                  `json:"error,omitempty"`
}

// Context serves as the thread-safe state bus passed through the workflow DAG.
type Context struct {
	mu        sync.RWMutex
	Variables map[string]interface{} `json:"variables"`
	Artifacts map[string]string      `json:"artifacts"`
	Failures  []string               `json:"failures"`
	Logs      []string               `json:"logs"`
}

// NewContext creates a new pipeline execution context.
func NewContext(initialVars map[string]interface{}) *Context {
	if initialVars == nil {
		initialVars = make(map[string]interface{})
	}
	return &Context{
		Variables: initialVars,
		Artifacts: make(map[string]string),
		Failures:  make([]string, 0),
		Logs:      make([]string, 0),
	}
}

// Get retrieves a variable value from the context.
func (c *Context) Get(key string) (interface{}, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	v, ok := c.Variables[key]
	return v, ok
}

// Set stores a variable into the context.
func (c *Context) Set(key string, val interface{}) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Variables[key] = val
}

// AddFailure records a test failure or assertion error.
func (c *Context) AddFailure(f string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Failures = append(c.Failures, f)
}

// AddLog appends an audit message to the execution log.
func (c *Context) AddLog(msg string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Logs = append(c.Logs, fmt.Sprintf("[%s] %s", time.Now().Format("15:04:05"), msg))
}

// Block represents a single, composable step in a testing workflow.
type Block interface {
	Descriptor() BlockDescriptor
	Execute(ctx context.Context, bCtx *Context) (*BlockResult, error)
}

// guardTarget enforces the default-deny scope list (KRITIX_ALLOWED_TARGETS) for every block that
// sends traffic to a target. It returns a failed result when the target is out of scope, else nil.
func guardTarget(blockID, targetURL string) (*BlockResult, error) {
	if err := security.ValidateFuzzTarget(targetURL, security.AllowedTargetsFromEnv()); err != nil {
		err = fmt.Errorf("out of scope, no traffic sent: %w (set KRITIX_ALLOWED_TARGETS)", err)
		return &BlockResult{BlockID: blockID, Status: StatusFailed, Message: err.Error(), Error: err}, err
	}
	return nil, nil
}
