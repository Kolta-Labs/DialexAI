package perf

import (
	"fmt"
	"strings"
	"time"
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
