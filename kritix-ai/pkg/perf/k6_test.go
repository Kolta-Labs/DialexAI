package perf

import (
	"strings"
	"testing"
	"time"
)

func TestGenerateK6Script(t *testing.T) {
	script := GenerateK6Script(LoadConfig{
		TargetURL:    "https://api.example.com/checkout",
		Profile:      ProfileSpike,
		VirtualUsers: 25,
		P95Threshold: 400 * time.Millisecond,
	})

	if !strings.Contains(script, "import http from 'k6/http';") {
		t.Errorf("missing k6 import")
	}
	if !strings.Contains(script, "https://api.example.com/checkout") {
		t.Errorf("missing target URL")
	}
	if !strings.Contains(script, "p(95)<400") {
		t.Errorf("missing p95 threshold")
	}
	if !strings.Contains(script, "Spike surge") {
		t.Errorf("missing spike stage annotation")
	}
}

func TestEvaluateLatency(t *testing.T) {
	samples := make([]time.Duration, 100)
	for i := 0; i < 90; i++ {
		samples[i] = 100 * time.Millisecond
	}
	for i := 90; i < 100; i++ {
		samples[i] = 450 * time.Millisecond // p95 will fall here
	}

	report := EvaluateLatency(samples, 500*time.Millisecond)
	if !report.PassedSLA {
		t.Errorf("expected to pass SLA of 500ms when p95 is 450ms")
	}

	strictReport := EvaluateLatency(samples, 300*time.Millisecond)
	if strictReport.PassedSLA {
		t.Errorf("expected to fail SLA of 300ms when p95 is 450ms")
	}
}
