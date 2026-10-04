package workflow

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"
)

func TestMain(m *testing.M) {
	os.Setenv("KRITIX_ALLOWED_TARGETS", ".example.com,127.0.0.1,localhost")
	os.Exit(m.Run())
}

func activeBlocks() map[string]Block {
	return map[string]Block{
		"exec.fuzz":       &ExecFuzzBlock{},
		"exec.owasp-dast": &ExecOWASPDASTBlock{},
		"perf.latency":    &PerfLatencyBlock{},
		"exec.matrix":     &ExecMatrixBlock{},
	}
}

func TestActiveBlocksRefuseOutOfScopeTargets(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { atomic.AddInt32(&hits, 1) }))
	defer srv.Close()

	t.Setenv("KRITIX_ALLOWED_TARGETS", ".other.test") // loopback server is not on the list
	for id, b := range activeBlocks() {
		bc := NewContext(map[string]interface{}{"target_url": srv.URL})
		res, err := b.Execute(context.Background(), bc)
		if err == nil || res.Status != StatusFailed {
			t.Errorf("%s: out-of-scope target must fail, got %v / %v", id, res.Status, err)
		}
	}
	t.Setenv("KRITIX_ALLOWED_TARGETS", "")
	for id, b := range activeBlocks() {
		if _, err := b.Execute(context.Background(), NewContext(map[string]interface{}{"target_url": srv.URL})); err == nil {
			t.Errorf("%s: empty allowlist must deny everything", id)
		}
	}
	if n := atomic.LoadInt32(&hits); n != 0 {
		t.Fatalf("sent %d requests to an out-of-scope host", n)
	}
}

func TestFuzzInScopeEscapesAndCatchesAll5xx(t *testing.T) {
	var raw []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw = append(raw, r.URL.RawQuery)
		if r.URL.Path != "/v1/items" {
			w.WriteHeader(404)
			return
		}
		w.WriteHeader(503)
	}))
	defer srv.Close()
	t.Setenv("KRITIX_ALLOWED_TARGETS", "127.0.0.1")
	bc := NewContext(map[string]interface{}{"target_url": srv.URL, "fuzz_path": "/v1/items", "fuzz_param": "q"})
	res, err := (&ExecFuzzBlock{}).Execute(context.Background(), bc)
	if err == nil || res.Status != StatusFailed {
		t.Fatalf("503s must be reported as findings, got %v / %v", res.Status, err)
	}
	if len(raw) == 0 {
		t.Fatal("no requests sent to in-scope target")
	}
	for _, q := range raw {
		if len(q) < 2 || q[:2] != "q=" {
			t.Fatalf("custom param/path ignored: %q", q)
		}
	}
}
