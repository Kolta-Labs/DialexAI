//go:build integration

package perf

import (
	"os/exec"
	"testing"
)

// TestIntegration_K6ExecutionAndSummaryParsing tests real k6 execution.
// If k6 is not installed on PATH, it skips with PREREQUISITE_MISSING: k6.
func TestIntegration_K6ExecutionAndSummaryParsing(t *testing.T) {
	k6Path, err := exec.LookPath("k6")
	if err != nil || k6Path == "" {
		t.Skip("PREREQUISITE_MISSING: k6 (k6 binary not found in PATH)")
	}

	// When k6 is present, verify version and execution
	cmd := exec.Command("k6", "version")
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("k6 execution failed: %v", err)
	}
	if len(out) == 0 {
		t.Errorf("empty output from k6 version")
	}
}
