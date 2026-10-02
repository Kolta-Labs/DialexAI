package sandbox

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestConfinementBlocksNetworkAndOutsideWrites(t *testing.T) {
	if runtime.GOOS != "darwin" {
		if _, err := os.Stat("/usr/bin/bwrap"); err != nil {
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
