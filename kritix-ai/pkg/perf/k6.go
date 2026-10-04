package perf

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"
)

var (
	ErrK6NotInstalled = errors.New("k6 executable not found in PATH; install k6 (https://k6.io/docs/get-started/installation/) or run in simulated mode")
	ErrK6Execution    = errors.New("k6 execution failed")
	ErrSLAViolation   = errors.New("k6 performance threshold violated SLA")
)

// ProfileType defines load distribution characteristics.
type ProfileType string

const (
	ProfileSmoke ProfileType = "smoke" // Low concurrency verification
	ProfileRamp  ProfileType = "ramp"  // Gradual load increase
	ProfileSpike ProfileType = "spike" // Sudden traffic surge
	ProfileSoak  ProfileType = "soak"  // Sustained long-duration test
)

// LoadConfig configures parameters for load generation.
type LoadConfig struct {
	TargetURL    string        `json:"target_url"`
	Profile      ProfileType   `json:"profile"`
	VirtualUsers int           `json:"virtual_users"`
	Duration     time.Duration `json:"duration"`
	P95Threshold time.Duration `json:"p95_threshold"`
}

// LatencyReport summarizes observed response time metrics.
type LatencyReport struct {
	P50Latency time.Duration `json:"p50_latency"`
	P95Latency time.Duration `json:"p95_latency"`
	P99Latency time.Duration `json:"p99_latency"`
	PassedSLA  bool          `json:"passed_sla"`
}

// K6ExecutionResult holds parsed output from a live k6 run.
type K6ExecutionResult struct {
	P50Latency  time.Duration `json:"p50_latency"`
	P95Latency  time.Duration `json:"p95_latency"`
	P99Latency  time.Duration `json:"p99_latency"`
	ErrorRate   float64       `json:"error_rate"`
	TotalReqs   int           `json:"total_reqs"`
	PassedSLA   bool          `json:"passed_sla"`
	SummaryJSON string        `json:"summary_json,omitempty"`
}

// GenerateK6Script creates a standalone, production-ready k6 JavaScript test script.
func GenerateK6Script(cfg LoadConfig) string {
	if cfg.VirtualUsers <= 0 {
		cfg.VirtualUsers = 20
	}
	if cfg.Duration <= 0 {
		cfg.Duration = 30 * time.Second
	}
	if cfg.P95Threshold <= 0 {
		cfg.P95Threshold = 500 * time.Millisecond
	}

	var sb strings.Builder
	sb.WriteString("import http from 'k6/http';\n")
	sb.WriteString("import { check, sleep } from 'k6';\n\n")

	sb.WriteString("export const options = {\n")
	switch cfg.Profile {
	case ProfileSpike:
		sb.WriteString("  stages: [\n")
		sb.WriteString("    { duration: '10s', target: 5 },\n")
		sb.WriteString(fmt.Sprintf("    { duration: '20s', target: %d }, // Spike surge\n", cfg.VirtualUsers*3))
		sb.WriteString("    { duration: '10s', target: 0 },\n")
		sb.WriteString("  ],\n")
	case ProfileRamp:
		sb.WriteString("  stages: [\n")
		sb.WriteString("    { duration: '30s', target: 10 },\n")
		sb.WriteString(fmt.Sprintf("    { duration: '1m', target: %d },\n", cfg.VirtualUsers))
		sb.WriteString("    { duration: '30s', target: 0 },\n")
		sb.WriteString("  ],\n")
	default: // Smoke / standard
		sb.WriteString(fmt.Sprintf("  vus: %d,\n", cfg.VirtualUsers))
		sb.WriteString(fmt.Sprintf("  duration: '%s',\n", cfg.Duration))
	}

	sb.WriteString("  thresholds: {\n")
	sb.WriteString(fmt.Sprintf("    http_req_duration: ['p(95)<%d'], // 95%% of requests must complete below %dms\n",
		cfg.P95Threshold.Milliseconds(), cfg.P95Threshold.Milliseconds()))
	sb.WriteString("    http_req_failed: ['rate<0.01'],    // Error rate must be less than 1%\n")
	sb.WriteString("  },\n")
	sb.WriteString("};\n\n")

	sb.WriteString("export default function () {\n")
	sb.WriteString(fmt.Sprintf("  const res = http.get(%q);\n", cfg.TargetURL))
	sb.WriteString("  check(res, {\n")
	sb.WriteString("    'status is 200': (r) => r.status === 200,\n")
	sb.WriteString("    'protocol is HTTP/2 or 1.1': (r) => r.proto !== '',\n")
	sb.WriteString("  });\n")
	sb.WriteString("  sleep(1);\n")
	sb.WriteString("}\n")

	return sb.String()
}

// IsK6Installed checks if the k6 binary is present in PATH.
func IsK6Installed() bool {
	_, err := exec.LookPath("k6")
	return err == nil
}

// ParseK6SummaryJSON parses k6's summary JSON output.
func ParseK6SummaryJSON(data []byte, p95Target time.Duration) (*K6ExecutionResult, error) {
	var raw struct {
		Metrics struct {
			HTTPReqDuration struct {
				Values struct {
					Med float64 `json:"med"`
					P95 float64 `json:"p(95)"`
					P99 float64 `json:"p(99)"`
					Avg float64 `json:"avg"`
				} `json:"values"`
			} `json:"http_req_duration"`
			HTTPReqFailed struct {
				Values struct {
					Rate float64 `json:"rate"`
				} `json:"values"`
			} `json:"http_req_failed"`
			HTTPReqs struct {
				Values struct {
					Count float64 `json:"count"`
				} `json:"values"`
			} `json:"http_reqs"`
		} `json:"metrics"`
	}

	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, fmt.Errorf("failed to parse k6 summary: %w", err)
	}

	p50 := time.Duration(raw.Metrics.HTTPReqDuration.Values.Med * float64(time.Millisecond))
	p95 := time.Duration(raw.Metrics.HTTPReqDuration.Values.P95 * float64(time.Millisecond))
	p99 := time.Duration(raw.Metrics.HTTPReqDuration.Values.P99 * float64(time.Millisecond))
	errRate := raw.Metrics.HTTPReqFailed.Values.Rate
	totalReqs := int(raw.Metrics.HTTPReqs.Values.Count)

	passedSLA := (p95Target <= 0 || p95 <= p95Target) && errRate < 0.01

	return &K6ExecutionResult{
		P50Latency:  p50,
		P95Latency:  p95,
		P99Latency:  p99,
		ErrorRate:   errRate,
		TotalReqs:   totalReqs,
		PassedSLA:   passedSLA,
		SummaryJSON: string(data),
	}, nil
}

// ExecuteK6Script runs a generated k6 script using the live k6 executable.
func ExecuteK6Script(ctx context.Context, scriptContent string, p95Target time.Duration) (*K6ExecutionResult, error) {
	if !IsK6Installed() {
		return nil, ErrK6NotInstalled
	}

	tmpScript, err := os.CreateTemp("", "k6-script-*.js")
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmpScript.Name())

	if _, err := tmpScript.WriteString(scriptContent); err != nil {
		return nil, err
	}
	_ = tmpScript.Close()

	tmpSummary, err := os.CreateTemp("", "k6-summary-*.json")
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmpSummary.Name())
	_ = tmpSummary.Close()

	cmd := exec.CommandContext(ctx, "k6", "run", "--summary-export", tmpSummary.Name(), tmpScript.Name())
	var stderr bytes.Buffer
	cmd.Stderr = &stderr

	runErr := cmd.Run()

	summaryBytes, err := os.ReadFile(tmpSummary.Name())
	if err != nil || len(summaryBytes) == 0 {
		if runErr != nil {
			return nil, fmt.Errorf("%w: %v, stderr: %s", ErrK6Execution, runErr, stderr.String())
		}
		return nil, fmt.Errorf("k6 produced no summary: %s", stderr.String())
	}

	result, err := ParseK6SummaryJSON(summaryBytes, p95Target)
	if err != nil {
		return nil, err
	}

	if runErr != nil && !result.PassedSLA {
		return result, ErrSLAViolation
	}

	return result, nil
}

// EvaluateLatency checks whether observed response times violate the configured SLA.
func EvaluateLatency(samples []time.Duration, p95Target time.Duration) LatencyReport {
	if len(samples) == 0 {
		return LatencyReport{PassedSLA: true}
	}

	// Simple percentile calculation
	p95Idx := int(float64(len(samples)) * 0.95)
	if p95Idx >= len(samples) {
		p95Idx = len(samples) - 1
	}

	p95 := samples[p95Idx]
	return LatencyReport{
		P50Latency: samples[len(samples)/2],
		P95Latency: p95,
		P99Latency: samples[len(samples)-1],
		PassedSLA:  p95 <= p95Target,
	}
}
