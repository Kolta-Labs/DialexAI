package runner

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"socratix/pkg/model"
)

// writeScript creates an executable shell script in a temp dir and returns its absolute
// path — using an absolute path sidesteps ResolveBinary's login-shell PATH lookup (that
// mechanism mirrors an already-proven Kotlin approach; exercising it here would mean
// spawning a real login shell per test, slow and environment-dependent for no extra
// coverage).
func writeScript(t *testing.T, body string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, "fake-cli")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body), 0o755); err != nil {
		t.Fatalf("write script: %v", err)
	}
	return path
}

func testAgent() model.Agent {
	return model.Agent{Provider: model.ProviderAnthropic, Model: "claude-x", RunMode: model.RunModeCLI}
}

func TestCliRunnerEchoesStdinBack(t *testing.T) {
	script := writeScript(t, `cat`)
	runner := NewCliAgentRunner(model.CliCommands{Anthropic: script})

	reply, err := runner.Respond(context.Background(), testAgent(), "topic", "", "", nil, "")
	if err != nil {
		t.Fatalf("Respond() error = %v", err)
	}
	if !strings.Contains(reply.Content, "Topic: topic") {
		t.Errorf("reply.Content = %q, want it to contain the prompt we piped in", reply.Content)
	}
}

func TestCliRunnerNonZeroExitSurfacesOutput(t *testing.T) {
	script := writeScript(t, `echo "boom: bad key" >&2; exit 1`)
	runner := NewCliAgentRunner(model.CliCommands{Anthropic: script})

	_, err := runner.Respond(context.Background(), testAgent(), "t", "", "", nil, "")
	if err == nil {
		t.Fatal("Respond() error = nil, want an error for a non-zero exit")
	}
	if !strings.Contains(err.Error(), "boom: bad key") {
		t.Errorf("error = %q, want it to include the process's own output", err.Error())
	}
}

func TestCliRunnerTimesOut(t *testing.T) {
	script := writeScript(t, `sleep 5`)
	runner := NewCliAgentRunner(model.CliCommands{Anthropic: script})
	runner.TimeoutSeconds = 1

	start := time.Now()
	_, err := runner.Respond(context.Background(), testAgent(), "t", "", "", nil, "")
	elapsed := time.Since(start)

	if err == nil || !strings.Contains(err.Error(), "timed out") {
		t.Fatalf("Respond() error = %v, want a timeout error", err)
	}
	if elapsed > 4*time.Second {
		t.Errorf("Respond() took %v, want it to return close to the 1s timeout, not wait out the 5s sleep", elapsed)
	}
}

// Hard stop: cancelling the passed-in context must kill the process immediately (SIGKILL
// via exec.CommandContext) instead of waiting for the configured timeout or the process's
// own sleep — this is what makes the app's hard-stop button actually instant.
func TestCliRunnerHardStopCancelsImmediately(t *testing.T) {
	script := writeScript(t, `sleep 5`)
	runner := NewCliAgentRunner(model.CliCommands{Anthropic: script})
	runner.TimeoutSeconds = 30 // long enough that only cancellation, not the timeout, can explain a fast return

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(200 * time.Millisecond)
		cancel()
	}()

	start := time.Now()
	_, err := runner.Respond(ctx, testAgent(), "t", "", "", nil, "")
	elapsed := time.Since(start)

	if err == nil {
		t.Fatal("Respond() error = nil, want context.Canceled after a hard stop")
	}
	if elapsed > 2*time.Second {
		t.Errorf("Respond() took %v after cancellation, want well under the 5s sleep — hard stop should be immediate", elapsed)
	}
}

func TestCliRunnerModelOverrideAppendsFlag(t *testing.T) {
	script := writeScript(t, `echo "$@"`)
	runner := NewCliAgentRunner(model.CliCommands{Anthropic: script + " --print"})

	reply, err := runner.Respond(context.Background(), testAgent(), "t", "", "", nil, "claude-haiku-4-5-20251001")
	if err != nil {
		t.Fatalf("Respond() error = %v", err)
	}
	if !strings.Contains(reply.Content, "--model claude-haiku-4-5-20251001") {
		t.Errorf("reply.Content = %q, want it to show the --model flag was appended", reply.Content)
	}
}

func TestCliRunnerMissingCommandIsAClearError(t *testing.T) {
	runner := NewCliAgentRunner(model.CliCommands{})
	_, err := runner.Respond(context.Background(), testAgent(), "t", "", "", nil, "")
	if err == nil || !strings.Contains(err.Error(), "no CLI command configured") {
		t.Errorf("error = %v, want a clear \"no CLI command configured\" message", err)
	}
}

// claudeNamedScript is a fake CLI whose base name is "claude" so the trusted-workspace
// flag logic applies. It logs argv to a file and reports a headless permission error.
func claudeNamedScript(t *testing.T) (script, log string) {
	t.Helper()
	dir := t.TempDir()
	log = filepath.Join(dir, "argv.log")
	script = filepath.Join(dir, "claude")
	body := "#!/bin/sh\necho \"$@\" >> " + log + "\necho 'headless mode cannot prompt for tool permission' >&2\nexit 1\n"
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatal(err)
	}
	return script, log
}

func bypassRunner(t *testing.T, allow bool) (*CliAgentRunner, string) {
	script, log := claudeNamedScript(t)
	r := NewCliAgentRunnerWithContext(model.CliCommands{Anthropic: script},
		[]model.FolderScope{{Path: t.TempDir(), IsTrusted: true}}, nil)
	r.AllowPermissionBypass = allow
	return r, log
}

func TestCliRunnerNeverBypassesPermissionsByDefault(t *testing.T) {
	r, log := bypassRunner(t, false)
	_, err := r.Respond(context.Background(), testAgent(), "t", "", "", nil, "")
	if err == nil || !strings.Contains(err.Error(), "permission") {
		t.Fatalf("want a permission error, got %v", err)
	}
	b, _ := os.ReadFile(log)
	if strings.Contains(string(b), "dangerously-skip-permissions") || strings.Count(string(b), "\n") != 1 {
		t.Fatalf("flag added or CLI retried without opt-in: %q", b)
	}
}

func TestCliRunnerBypassWhenOptedIn(t *testing.T) {
	r, log := bypassRunner(t, true)
	r.Respond(context.Background(), testAgent(), "t", "", "", nil, "")
	b, _ := os.ReadFile(log)
	if !strings.Contains(string(b), "dangerously-skip-permissions") {
		t.Fatalf("opt-in should add the flag: %q", b)
	}
}
