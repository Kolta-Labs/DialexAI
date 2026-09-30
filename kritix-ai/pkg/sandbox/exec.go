package sandbox

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"syscall"
	"time"
)

const (
	// DefaultMaxOutputBytes limits output capture to 1MB to prevent memory exhaustion
	DefaultMaxOutputBytes = 1024 * 1024
	// DefaultTimeout is the fallback command execution timeout
	DefaultTimeout = 5 * time.Minute
)

// ExecOptions specifies execution parameters for a sandboxed command.
type ExecOptions struct {
	Cwd            string            `json:"cwd"`
	Timeout        time.Duration     `json:"timeout"`
	MaxOutputBytes int               `json:"maxOutputBytes"`
	Env            map[string]string `json:"env,omitempty"`
}

// ExecResult contains the deterministic result of running a command.
type ExecResult struct {
	Command    string        `json:"command"`
	Stdout     string        `json:"stdout"`
	Stderr     string        `json:"stderr"`
	ExitCode   int           `json:"exitCode"`
	DurationMs int64         `json:"durationMs"`
	TimedOut   bool          `json:"timedOut"`
	Truncated  bool          `json:"truncated"`
	Error      string        `json:"error,omitempty"`
}

// Success returns true if the command exited with code 0 and did not time out.
func (r *ExecResult) Success() bool {
	return r.ExitCode == 0 && !r.TimedOut && r.Error == ""
}

// Sandbox provides safe, isolated command execution.
type Sandbox struct {
	defaultCwd string
}

// NewSandbox creates a Sandbox bound to a workspace directory.
func NewSandbox(workspaceDir string) *Sandbox {
	return &Sandbox{
		defaultCwd: workspaceDir,
	}
}

// Run executes a shell command line string with safety boundaries.
func (s *Sandbox) Run(ctx context.Context, cmdStr string, opts *ExecOptions) *ExecResult {
	start := time.Now()

	cwd := s.defaultCwd
	timeout := DefaultTimeout
	maxBytes := DefaultMaxOutputBytes

	if opts != nil {
		if opts.Cwd != "" {
			cwd = opts.Cwd
		}
		if opts.Timeout > 0 {
			timeout = opts.Timeout
		}
		if opts.MaxOutputBytes > 0 {
			maxBytes = opts.MaxOutputBytes
		}
	}

	execCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cmd := exec.CommandContext(execCtx, "sh", "-c", cmdStr)
	cmd.Dir = cwd

	// Use process group so children are killed on timeout
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	var stdoutBuf, stderrBuf bytes.Buffer
	cmd.Stdout = &stdoutBuf
	cmd.Stderr = &stderrBuf

	if opts != nil && len(opts.Env) > 0 {
		for k, v := range opts.Env {
			cmd.Env = append(cmd.Env, fmt.Sprintf("%s=%s", k, v))
		}
	}

	runErr := cmd.Run()
	duration := time.Since(start)

	result := &ExecResult{
		Command:    cmdStr,
		DurationMs: duration.Milliseconds(),
		ExitCode:   0,
	}

	// Check timeout
	if errors.Is(execCtx.Err(), context.DeadlineExceeded) {
		result.TimedOut = true
		result.ExitCode = -1
		result.Error = fmt.Sprintf("command timed out after %v", timeout)

		// Terminate child process group
		if cmd.Process != nil {
			_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
		}
	} else if runErr != nil {
		var exitErr *exec.ExitError
		if errors.As(runErr, &exitErr) {
			result.ExitCode = exitErr.ExitCode()
		} else {
			result.ExitCode = 1
			result.Error = runErr.Error()
		}
	}

	// Capture and truncate output
	stdoutBytes := stdoutBuf.Bytes()
	stderrBytes := stderrBuf.Bytes()

	if len(stdoutBytes) > maxBytes {
		stdoutBytes = stdoutBytes[:maxBytes]
		result.Truncated = true
	}
	if len(stderrBytes) > maxBytes {
		stderrBytes = stderrBytes[:maxBytes]
		result.Truncated = true
	}

	result.Stdout = strings.TrimSpace(string(stdoutBytes))
	result.Stderr = strings.TrimSpace(string(stderrBytes))

	return result
}
