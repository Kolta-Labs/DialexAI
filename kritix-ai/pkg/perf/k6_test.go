package perf

import (
	"context"
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

func TestParseK6SummaryJSON(t *testing.T) {
	summary := `{
		"metrics": {
			"http_req_duration": {
				"values": {
					"med": 45.2,
					"p(95)": 180.5,
					"p(99)": 240.0,
					"avg": 55.1
				}
			},
			"http_req_failed": {
				"values": {
					"rate": 0.002
				}
			},
			"http_reqs": {
				"values": {
					"count": 5000.0
				}
			}
		}
	}`

	res, err := ParseK6SummaryJSON([]byte(summary), 200*time.Millisecond)
	if err != nil {
		t.Fatalf("ParseK6SummaryJSON failed: %v", err)
	}

	if !res.PassedSLA {
		t.Errorf("expected PassedSLA = true for 180.5ms vs 200ms target")
	}
	if res.TotalReqs != 5000 {
		t.Errorf("expected 5000 requests, got %d", res.TotalReqs)
	}
	if res.ErrorRate != 0.002 {
		t.Errorf("expected 0.002 error rate, got %f", res.ErrorRate)
	}

	// Threshold failure path: target 100ms < 180.5ms p95 must fail SLA
	strictRes, err := ParseK6SummaryJSON([]byte(summary), 100*time.Millisecond)
	if err != nil {
		t.Fatalf("ParseK6SummaryJSON strict failed: %v", err)
	}
	if strictRes.PassedSLA {
		t.Errorf("expected PassedSLA = false when p95 (180.5ms) exceeds target (100ms)")
	}
}

func TestExecuteK6_NotInstalledOrRun(t *testing.T) {
	// If k6 is not installed, ExecuteK6Script must return ErrK6NotInstalled
	if !IsK6Installed() {
		_, err := ExecuteK6Script(context.Background(), "script", 200*time.Millisecond)
		if err != ErrK6NotInstalled {
			t.Errorf("expected ErrK6NotInstalled, got %v", err)
		}
	}
}
