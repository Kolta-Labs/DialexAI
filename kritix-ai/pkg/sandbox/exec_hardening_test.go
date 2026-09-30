package sandbox

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestSandboxExitCodeAndStderr(t *testing.T) {
	res := NewSandbox(t.TempDir()).Run(context.Background(), "echo oops >&2; exit 3", nil)
	if res.Success() || res.ExitCode != 3 || res.Stderr != "oops" {
		t.Fatalf("got %+v", res)
	}
}

func TestSandboxUsesWorkspaceDirAndOverride(t *testing.T) {
	ws, other := t.TempDir(), t.TempDir()
	box := NewSandbox(ws)
	if got := box.Run(context.Background(), "pwd -P", nil).Stdout; !strings.HasSuffix(got, ws[strings.LastIndex(ws, "/")+1:]) {
		t.Errorf("default cwd not used: %q", got)
	}
	got := box.Run(context.Background(), "pwd -P", &ExecOptions{Cwd: other}).Stdout
	if !strings.HasSuffix(got, other[strings.LastIndex(other, "/")+1:]) {
		t.Errorf("cwd override not used: %q", got)
	}
}

// Custom env vars must extend, not replace, the inherited environment, otherwise HOME
// vanish and ordinary tools (go, gradle, git) cannot be found by the test command.
func TestSandboxEnvExtendsInherited(t *testing.T) {
	res := NewSandbox(t.TempDir()).Run(context.Background(),
		`echo "$FOO:${HOME:+has-home}"`, &ExecOptions{Env: map[string]string{"FOO": "bar"}})
	if res.Stdout != "bar:has-home" {
		t.Fatalf("got %q, want custom var AND inherited HOME", res.Stdout)
	}
}

// A timeout must stop the whole process tree promptly. A child that keeps the output pipe
// open must not keep Run blocked until the child exits on its own.
func TestSandboxTimeoutKillsChildren(t *testing.T) {
	start := time.Now()
	res := NewSandbox(t.TempDir()).Run(context.Background(),
		"sleep 30; echo unreachable", &ExecOptions{Timeout: 200 * time.Millisecond})
	if !res.TimedOut {
		t.Fatalf("expected timeout, got %+v", res)
	}
	if elapsed := time.Since(start); elapsed > 3*time.Second {
		t.Fatalf("Run blocked %v after a 200ms timeout; children were not killed", elapsed)
	}
}

func TestSandboxCapsRunawayOutput(t *testing.T) {
	res := NewSandbox(t.TempDir()).Run(context.Background(),
		"yes | head -c 5000000", &ExecOptions{MaxOutputBytes: 1000})
	if !res.Truncated || len(res.Stdout) > 1000 {
		t.Fatalf("truncated=%v len=%d", res.Truncated, len(res.Stdout))
	}
}

func TestSandboxParentContextCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(100 * time.Millisecond); cancel() }()
	start := time.Now()
	res := NewSandbox(t.TempDir()).Run(ctx, "sleep 30", nil)
	if time.Since(start) > 3*time.Second || res.Success() {
		t.Fatalf("cancel not honored: %+v after %v", res, time.Since(start))
	}
}
