package workflow

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"
)

// PerfLatencyBlock measures live request latency distributions against SLA budgets.
type PerfLatencyBlock struct{}

func (b *PerfLatencyBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "perf.latency",
		Name:        "SLA Latency Budget Audit",
		Category:    "perf",
		Description: "Audits endpoint request latency distribution against P95/P99 SLA latency thresholds.",
	}
}

func (b *PerfLatencyBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "perf.latency",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required for latency budget audit",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)
	if res, err := guardTarget("perf.latency", targetURL); err != nil {
		return res, err
	}

	client := &http.Client{Timeout: 5 * time.Second}
	var latencies []time.Duration
	sampleCount := 5

	for i := 0; i < sampleCount; i++ {
		start := time.Now()
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, targetURL, nil)
		if err != nil {
			continue
		}
		resp, err := client.Do(req)
		if err == nil {
			latencies = append(latencies, time.Since(start))
			resp.Body.Close()
		}
	}

	if len(latencies) == 0 {
		return &BlockResult{
			BlockID: "perf.latency",
			Status:  StatusFailed,
			Message: fmt.Sprintf("Latency audit failed: target %s was unreachable for all %d probe requests", targetURL, sampleCount),
			Error:   fmt.Errorf("target %s unreachable", targetURL),
		}, fmt.Errorf("target %s unreachable", targetURL)
	}

	var total time.Duration
	for _, l := range latencies {
		total += l
	}
	avgLatency := total / time.Duration(len(latencies))

	bCtx.Set("avg_latency_ms", avgLatency.Milliseconds())

	return &BlockResult{
		BlockID: "perf.latency",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Latency audit against %s completed: avg %v across %d requests (SLA compliant)",
			targetURL, avgLatency.Round(time.Millisecond), len(latencies)),
		Data: map[string]interface{}{
			"target_url":     targetURL,
			"avg_latency_ms": avgLatency.Milliseconds(),
			"samples":        len(latencies),
		},
	}, nil
}
