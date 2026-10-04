package telemetry

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"kritix/pkg/workflow"
)

// TestZeroTelemetryByDefault_ProofOfZeroEgress asserts that running workflows or offline
// operations makes zero outbound HTTP/telemetry network calls unless explicitly configured.
func TestZeroTelemetryByDefault_ProofOfZeroEgress(t *testing.T) {
	var outboundAttempts int64

	// Mock external honeypot listener to detect accidental telemetry egress
	honeypot := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&outboundAttempts, 1)
		http.Error(w, "prohibited egress", http.StatusBadGateway)
	}))
	defer honeypot.Close()

	t.Setenv("KRITIX_TELEMETRY_ENDPOINT", honeypot.URL)
	t.Setenv("KRITIX_ZERO_EGRESS", "true")

	// Execute offline blueprint / blocks
	dag := workflow.NewDAG("offline-zero-telemetry-proof", "Offline Zero Telemetry Proof")
	dag.AddNode("local_parse", &workflow.IngestOpenAPIBlock{})

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	bCtx := workflow.NewContext(map[string]any{
		"openapi_spec": `{"openapi":"3.0.0","info":{"title":"Offline Test","version":"1.0"},"paths":{"/health":{}}}`,
	})

	state, err := dag.Execute(ctx, bCtx)
	if err != nil {
		t.Fatalf("unexpected DAG error: %v", err)
	}

	if state == nil || state.NodeResults["local_parse"].Status != workflow.StatusPassed {
		t.Errorf("expected local_parse to succeed, got status: %v", state.NodeResults["local_parse"])
	}

	// Verify zero outbound calls were attempted
	if attempts := atomic.LoadInt64(&outboundAttempts); attempts > 0 {
		t.Fatalf("VIOLATION: Detected %d unwanted telemetry/outbound egress attempts during offline execution!", attempts)
	}
}
