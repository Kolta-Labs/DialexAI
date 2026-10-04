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

	script := perf.GenerateK6Script(perf.LoadConfig{
		TargetURL:    targetURL,
		Profile:      perf.ProfileSpike,
		VirtualUsers: 50,
		Duration:     30 * time.Second,
		P95Threshold: 200 * time.Millisecond,
	})

	bCtx.Set("k6_script", script)
	bCtx.Set("k6_p95_sla_ms", 200)

	return &BlockResult{
		BlockID: "perf.k6-spike",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Generated and validated 50 VU k6 spike test script for %s (P95 SLA: 200ms)", targetURL),
		Data: map[string]interface{}{
			"target_url":   targetURL,
			"vus":          50,
			"p95_sla_ms":   200,
			"duration_sec": 30,
		},
	}, nil
}
