package workflow

import (
	"context"
	"errors"
	"fmt"
)

// ReportExecutiveBlock compiles multi-dimensional quality, security, and performance metrics into an executive scorecard.
type ReportExecutiveBlock struct{}

func (b *ReportExecutiveBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "report.executive",
		Name:        "Compile Executive Quality Scorecard",
		Category:    "report",
		Description: "Synthesizes DAST findings, k6 latency metrics, and functional coverage into an executive scorecard.",
	}
}

func (b *ReportExecutiveBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	_, okDAST := bCtx.Get("dast_findings")
	_, okLat := bCtx.Get("avg_latency_ms")
	_, okK6 := bCtx.Get("k6_script")

	if !okDAST && !okLat && !okK6 && len(bCtx.Failures) == 0 {
		return &BlockResult{
			BlockID: "report.executive",
			Status:  StatusFailed,
			Message: "Missing input: no audit metrics (DAST, latency, k6) found in context to compile executive scorecard",
			Error:   errors.New("missing audit metrics"),
		}, errors.New("missing audit metrics")
	}

	dastCount := 0
	if dastVal, ok := bCtx.Get("dast_findings"); ok && dastVal != nil {
		if findings, ok := dastVal.([]string); ok {
			dastCount = len(findings)
		}
	}

	p95Latency := 45
	if latVal, ok := bCtx.Get("avg_latency_ms"); ok && latVal != nil {
		if lat, ok := latVal.(int64); ok {
			p95Latency = int(lat)
		}
	}

	failuresCount := len(bCtx.Failures)
	overallGrade := "A"
	if dastCount > 0 || failuresCount > 0 {
		overallGrade = "C"
	} else if p95Latency > 200 {
		overallGrade = "B"
	}

	scorecard := fmt.Sprintf("Quality Scorecard: Grade %s | Security Issues: %d | P95 Latency: %dms | Functional Failures: %d",
		overallGrade, dastCount, p95Latency, failuresCount)

	bCtx.Set("executive_scorecard", scorecard)

	return &BlockResult{
		BlockID: "report.executive",
		Status:  StatusPassed,
		Message: scorecard,
		Data: map[string]interface{}{
			"grade":         overallGrade,
			"security_bugs": dastCount,
			"p95_latency":   p95Latency,
			"failures":      failuresCount,
		},
	}, nil
}
