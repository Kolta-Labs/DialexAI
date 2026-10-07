package sandbox

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"testing"
)

func TestConfinementBlocksNetworkAndOutsideWrites(t *testing.T) {
	if runtime.GOOS != "darwin" {
		if _, err := exec.LookPath("bwrap"); err != nil {
			if os.Getenv("CI") != "" || os.Getenv("ARTIX_ENTERPRISE") != "" {
				t.Fatalf("bwrap OS sandbox is required in CI / Enterprise environments, but was not found: %v", err)
			}
			t.Skip("no OS sandbox available")
		}
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.Write([]byte("ok")) }))
	defer srv.Close()
	ws := t.TempDir()
	outside := filepath.Join(os.Getenv("HOME"), ".artix-sandbox-test-should-not-exist")
	defer os.Remove(outside)
	box := NewSandbox(ws)
	ctx := context.Background()

	if r := box.Run(ctx, "echo hi > inside.txt && cat inside.txt", nil); !r.Success() || r.Isolation != IsolationOSSandbox {
		t.Fatalf("write inside workspace must work under os-sandbox: %+v", r)
	}
	if r := box.Run(ctx, "touch "+outside, nil); r.Success() {
		t.Fatal("write outside the workspace must be denied")
	}
	if _, err := os.Stat(outside); err == nil {
		t.Fatal("file was created outside the workspace")
	}
	if r := box.Run(ctx, "curl -s -m 3 "+srv.URL, nil); r.Success() {
		t.Fatal("network must be denied")
	}
	t.Setenv("ARTIX_SANDBOX_NETWORK", "1")
	if r := box.Run(ctx, "curl -s -m 3 "+srv.URL, nil); !r.Success() || r.Stdout != "ok" {
		t.Fatalf("network opt-in must work: %+v", r)
	}
}

func TestBuildBwrapArgs(t *testing.T) {
	writable := []string{"/tmp", "/var/tmp"}
	argsNoNet := BuildBwrapArgs(writable, false, "echo hello")
	
	hasRoBind := false
	hasUnshareNet := false
	for _, a := range argsNoNet {
		if a == "--ro-bind" {
			hasRoBind = true
		}
		if a == "--unshare-net" {
			hasUnshareNet = true
		}
	}
	if !hasRoBind {
		t.Errorf("expected --ro-bind in bwrap args: %v", argsNoNet)
	}
	if !hasUnshareNet {
		t.Errorf("expected --unshare-net when network disabled: %v", argsNoNet)
	}

	argsWithNet := BuildBwrapArgs(writable, true, "echo hello")
	for _, a := range argsWithNet {
		if a == "--unshare-net" {
			t.Errorf("did not expect --unshare-net when network allowed: %v", argsWithNet)
		}
	}
}

func TestEnterpriseModeBlocksSandboxEscape(t *testing.T) {
	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("ARTIX_SANDBOX", "off")

	// Even with ARTIX_SANDBOX=off, enterprise mode must prevent plain unconfined fallback
	// on platforms where os sandbox exists
	_, iso := confine(t.TempDir(), "echo hi")
	if runtime.GOOS == "darwin" {
		if iso != IsolationOSSandbox {
			t.Errorf("enterprise mode must ignore ARTIX_SANDBOX=off on darwin, got isolation: %s", iso)
		}
	}
}

func TestEnterpriseModeFailClosedRefusal(t *testing.T) {
	t.Setenv("ARTIX_ENTERPRISE", "1")
	// Clear PATH so LookPath fails for sandbox-exec and bwrap
	t.Setenv("PATH", "")

	box := NewSandbox(t.TempDir())
	res := box.Run(context.Background(), "echo fail-closed", nil)
	if res.Isolation != IsolationRefused {
		t.Fatalf("expected IsolationRefused in enterprise mode when no sandbox tool is available, got: %s", res.Isolation)
	}
	if res.ExitCode != 126 {
		t.Errorf("expected exit code 126 for refused sandbox execution, got %d", res.ExitCode)
	}
	if res.Success() {
		t.Errorf("refused sandbox execution must not succeed")
	}
}

func TestSandboxBlocksSensitiveCredentialsRead(t *testing.T) {
	if runtime.GOOS != "darwin" {
		if _, err := exec.LookPath("bwrap"); err != nil {
			t.Skip("skipping credential read deny test: no sandbox available on this OS")
		}
	}

	fakeHome := t.TempDir()
	sshDir := filepath.Join(fakeHome, ".ssh")
	if err := os.MkdirAll(sshDir, 0700); err != nil {
		t.Fatal(err)
	}
	secretFile := filepath.Join(sshDir, "id_ed25519")
	if err := os.WriteFile(secretFile, []byte("SUPER_SECRET_KEY"), 0600); err != nil {
		t.Fatal(err)
	}

	t.Setenv("HOME", fakeHome)
	ws := t.TempDir()
	box := NewSandbox(ws)

	res := box.Run(context.Background(), "cat "+secretFile, nil)
	if res.Success() {
		t.Fatalf("sandbox must deny reading sensitive ssh credential from %s, but read succeeded: %s", secretFile, res.Stdout)
	}
}

func TestSandboxBlocksArtixHomeDirectoryReadAndWrite(t *testing.T) {
	if runtime.GOOS != "darwin" {
		if _, err := exec.LookPath("bwrap"); err != nil {
			t.Skip("skipping artix home sandbox test: no sandbox available on this OS")
		}
	}

	fakeHome := t.TempDir()
	artixDir := filepath.Join(fakeHome, ".artix")
	if err := os.MkdirAll(artixDir, 0700); err != nil {
		t.Fatal(err)
	}
	personaFile := filepath.Join(artixDir, "persona.json")
	if err := os.WriteFile(personaFile, []byte("SENSITIVE_STEERING_DATA"), 0600); err != nil {
		t.Fatal(err)
	}

	t.Setenv("HOME", fakeHome)
	ws := t.TempDir()
	box := NewSandbox(ws)

	// 1. Read must be denied
	resRead := box.Run(context.Background(), "cat "+personaFile, nil)
	if resRead.Success() {
		t.Fatalf("sandbox must deny reading ~/.artix files, but succeeded: %s", resRead.Stdout)
	}

	// 2. Write to ~/.artix must be denied (prevent poisoning of global state)
	poisonFile := filepath.Join(artixDir, "poison.json")
	resWrite := box.Run(context.Background(), "echo poisoned > "+poisonFile, nil)
	if resWrite.Success() {
		t.Fatalf("sandbox must deny writing to ~/.artix, but succeeded")
	}
	if _, err := os.Stat(poisonFile); err == nil {
		t.Fatalf("poison file was created inside ~/.artix by sandboxed code")
	}
}



