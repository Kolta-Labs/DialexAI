package sandbox

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
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
	Command    string `json:"command"`
	Isolation  string `json:"isolation"`
	Stdout     string `json:"stdout"`
	Stderr     string `json:"stderr"`
	ExitCode   int    `json:"exitCode"`
	DurationMs int64  `json:"durationMs"`
	TimedOut   bool   `json:"timedOut"`
	Truncated  bool   `json:"truncated"`
	Error      string `json:"error,omitempty"`
}

// Success returns true if the command exited with code 0 and did not time out.
func (r *ExecResult) Success() bool {
	return r.ExitCode == 0 && !r.TimedOut && r.Error == ""
}

// cappedBuffer keeps at most max bytes and discards the rest, so a runaway command cannot
// exhaust memory while it runs.
type cappedBuffer struct {
	buf       bytes.Buffer
	max       int
	truncated bool
}

func (c *cappedBuffer) Write(p []byte) (int, error) {
	n := len(p) // report everything consumed, or io.Copy treats it as a short write and stops draining
	if room := c.max - c.buf.Len(); len(p) > room {
		c.truncated = true
		p = p[:max(room, 0)]
	}
	c.buf.Write(p)
	return n, nil
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

	cmd, isolation := confine(cwd, cmdStr)
	cmd = exec.CommandContext(execCtx, cmd.Path, cmd.Args[1:]...)
	cmd.Dir = cwd

	// Use process group so children are killed on timeout
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	// On timeout/cancel kill the whole process group, not just sh, and don't wait forever on
	// pipes that surviving grandchildren might still hold open.
	cmd.Cancel = func() error { return syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL) }
	cmd.WaitDelay = time.Second

	stdoutBuf := &cappedBuffer{max: maxBytes}
	stderrBuf := &cappedBuffer{max: maxBytes}
	cmd.Stdout = stdoutBuf
	cmd.Stderr = stderrBuf

	if opts != nil && len(opts.Env) > 0 {
		cmd.Env = os.Environ() // extend, never replace, the inherited environment
		for k, v := range opts.Env {
			cmd.Env = append(cmd.Env, fmt.Sprintf("%s=%s", k, v))
		}
	}

	runErr := cmd.Run()
	duration := time.Since(start)

	result := &ExecResult{
		Command:    cmdStr,
		Isolation:  isolation,
		DurationMs: duration.Milliseconds(),
		ExitCode:   0,
	}

	// Check timeout
	if errors.Is(execCtx.Err(), context.DeadlineExceeded) {
		result.TimedOut = true
		result.ExitCode = -1
		result.Error = fmt.Sprintf("command timed out after %v", timeout)
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
	result.Truncated = stdoutBuf.truncated || stderrBuf.truncated
	result.Stdout = strings.TrimSpace(stdoutBuf.buf.String())
	result.Stderr = strings.TrimSpace(stderrBuf.buf.String())

	return result
}
