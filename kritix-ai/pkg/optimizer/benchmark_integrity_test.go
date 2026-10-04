package optimizer

import (
	"os"
	"strings"
	"testing"
)

// TestBenchmarkIntegrity_NoHardcodedFallback ensures that benchmark and TCO packages
// contain zero hardcoded metric numbers (e.g. 1.45s, 185000 LOC Medusa, 0.0 flake)
// and fail closed if a corpus or harness cannot be evaluated.
func TestBenchmarkIntegrity_NoHardcodedFallback(t *testing.T) {
	benchFile, err := os.ReadFile("benchmark.go")
	if err != nil {
		t.Fatalf("failed to read benchmark.go: %v", err)
	}
	content := string(benchFile)

	forbiddenStrings := []string{
		"Medusa Storefront (Next.js / Node / PostgreSQL)",
		"185000",
		"1.45",
	}

	for _, s := range forbiddenStrings {
		if strings.Contains(content, s) {
			t.Errorf("VIOLATION: benchmark.go contains forbidden hardcoded fallback metric %q", s)
		}
	}
}

func TestBenchmarkSuite_UnmeasuredWhenCorpusMissing(t *testing.T) {
	suite, err := RunBenchmarkOnCorpus("/nonexistent/corpus/path", 10)
	if err == nil && suite != nil {
		t.Errorf("expected error when corpus is missing, got suite: %+v", suite)
	}
}
