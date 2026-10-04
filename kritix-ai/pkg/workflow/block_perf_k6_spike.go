package workflow

import (
	"context"
	"errors"
	"fmt"
	"time"

	"kritix/pkg/perf"
)

// PerfK6SpikeBlock generates and executes load/spike performance tests with SLA assertions.
type PerfK6SpikeBlock struct{}

func (b *PerfK6SpikeBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "perf.k6-spike",
		Name:        "k6 Spike Load Test (50 VUs)",
		Category:    "perf",
		Description: "Executes 50 Virtual User spike load test with p95 <= 200ms latency assertions.",
	}
}

func (b *PerfK6SpikeBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "perf.k6-spike",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required for k6 performance testing",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)

	slaTarget := 200 * time.Millisecond
	script := perf.GenerateK6Script(perf.LoadConfig{
		TargetURL:    targetURL,
		Profile:      perf.ProfileSpike,
		VirtualUsers: 50,
		Duration:     30 * time.Second,
		P95Threshold: slaTarget,
	})

	bCtx.Set("k6_script", script)
	bCtx.Set("k6_p95_sla_ms", slaTarget.Milliseconds())

	// Check if a custom mock result or runner is supplied in context (for unit tests/CI)
	if customRes, ok := bCtx.Get("k6_mock_result"); ok && customRes != nil {
		if k6Res, ok := customRes.(*perf.K6ExecutionResult); ok {
			if !k6Res.PassedSLA {
				return &BlockResult{
					BlockID: "perf.k6-spike",
					Status:  StatusFailed,
					Message: fmt.Sprintf("k6 spike test violated SLA: p95=%v > %v", k6Res.P95Latency, slaTarget),
					Data: map[string]interface{}{
						"p95_ms":     k6Res.P95Latency.Milliseconds(),
						"error_rate": k6Res.ErrorRate,
					},
					Error: perf.ErrSLAViolation,
				}, perf.ErrSLAViolation
			}
			return &BlockResult{
				BlockID: "perf.k6-spike",
				Status:  StatusPassed,
				Message: fmt.Sprintf("k6 spike test passed SLA: p95=%v (target <= %v, error_rate=%.2f%%)", k6Res.P95Latency, slaTarget, k6Res.ErrorRate*100),
				Data: map[string]interface{}{
					"target_url": targetURL,
					"p50_ms":     k6Res.P50Latency.Milliseconds(),
					"p95_ms":     k6Res.P95Latency.Milliseconds(),
					"p99_ms":     k6Res.P99Latency.Milliseconds(),
					"error_rate": k6Res.ErrorRate,
					"total_reqs": k6Res.TotalReqs,
				},
			}, nil
		}
	}

	// Check if k6 executable is available
	if !perf.IsK6Installed() {
		// Honest reporting: k6 not installed -> StatusSimulated, NEVER StatusPassed!
		return &BlockResult{
			BlockID:   "perf.k6-spike",
			Status:    StatusSimulated,
			Simulated: true,
			Message:   fmt.Sprintf("Simulated: k6 binary not installed in PATH; generated spike test script for %s without running", targetURL),
			Data: map[string]interface{}{
				"target_url": targetURL,
				"vus":        50,
				"p95_sla_ms": slaTarget.Milliseconds(),
				"simulated":  true,
			},
		}, nil
	}

	// Execute live k6
	result, err := perf.ExecuteK6Script(ctx, script, slaTarget)
	if err != nil {
		if errors.Is(err, perf.ErrSLAViolation) || (result != nil && !result.PassedSLA) {
			return &BlockResult{
				BlockID: "perf.k6-spike",
				Status:  StatusFailed,
				Message: fmt.Sprintf("k6 spike test failed SLA: p95=%v exceeds %v", result.P95Latency, slaTarget),
				Error:   err,
			}, err
		}
		return &BlockResult{
			BlockID: "perf.k6-spike",
			Status:  StatusFailed,
			Message: fmt.Sprintf("k6 execution error: %v", err),
			Error:   err,
		}, err
	}

	return &BlockResult{
		BlockID: "perf.k6-spike",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Executed 50 VU k6 spike test on %s (p95: %v, SLA: %v)", targetURL, result.P95Latency, slaTarget),
		Data: map[string]interface{}{
			"target_url": targetURL,
			"p50_ms":     result.P50Latency.Milliseconds(),
			"p95_ms":     result.P95Latency.Milliseconds(),
			"p99_ms":     result.P99Latency.Milliseconds(),
			"error_rate": result.ErrorRate,
			"total_reqs": result.TotalReqs,
		},
	}, nil
}
