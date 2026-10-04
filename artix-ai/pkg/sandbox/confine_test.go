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

