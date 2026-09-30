package sandbox

import (
	"context"
	"os"
	"testing"
	"time"
)

func TestSandboxRunEcho(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_sandbox_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	box := NewSandbox(tempDir)
	res := box.Run(context.Background(), "echo 'hello world'", nil)

	if !res.Success() {
		t.Fatalf("expected success, got exit code %d (error: %s)", res.ExitCode, res.Error)
	}
	if res.Stdout != "hello world" {
		t.Errorf("expected 'hello world', got %q", res.Stdout)
	}
}

func TestSandboxTimeout(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_sandbox_timeout")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	box := NewSandbox(tempDir)
	opts := &ExecOptions{
		Timeout: 100 * time.Millisecond,
	}

	// Sleep for 2 seconds with 100ms timeout
	res := box.Run(context.Background(), "sleep 2", opts)

	if !res.TimedOut {
		t.Errorf("expected command to be timed out")
	}
	if res.ExitCode != -1 {
		t.Errorf("expected exit code -1 on timeout, got %d", res.ExitCode)
	}
}

func TestSandboxOutputTruncation(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_sandbox_trunc")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	box := NewSandbox(tempDir)
	opts := &ExecOptions{
		MaxOutputBytes: 10,
	}

	res := box.Run(context.Background(), "echo 'this is a long string that exceeds 10 bytes'", opts)

	if !res.Truncated {
		t.Errorf("expected output to be marked truncated")
	}
	if len(res.Stdout) > 10 {
		t.Errorf("expected stdout length <= 10, got %d", len(res.Stdout))
	}
}
