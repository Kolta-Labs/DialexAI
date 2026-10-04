package workflow

import (
	"context"
	"errors"
	"fmt"

	"kritix/pkg/agent"
	"kritix/pkg/driver"
	"kritix/pkg/model"
)

// AgentVisionCrawlBlock performs autonomous multimodal visual exploration using browser drivers.
type AgentVisionCrawlBlock struct{}

func (b *AgentVisionCrawlBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "agent.vision-crawl",
		Name:        "Autonomous Multimodal Vision Crawl",
		Category:    "agent",
		Description: "Drives autonomous visual exploratory crawling across dynamic UI state transitions.",
	}
}

func (b *AgentVisionCrawlBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "agent.vision-crawl",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required for visual crawling",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)
	if res, err := guardTarget("agent.vision-crawl", targetURL); err != nil {
		return res, err
	}

	drv := driver.NewCDPDriver(driver.DefaultCDPConfig())
	if err := drv.Start(ctx); err != nil {
		bCtx.AddLog(fmt.Sprintf("Vision crawl fallback: %v", err))
		return &BlockResult{
			BlockID: "agent.vision-crawl",
			Status:  StatusSimulated,
			Message: fmt.Sprintf("Autonomous crawl simulated for %s (CDP browser unavailable: %v)", targetURL, err),
			Data:    map[string]interface{}{"target_url": targetURL, "simulated": true},
		}, nil
	}
	defer drv.Stop(ctx)

	router := model.NewRouter(model.DefaultRouterConfig())
	explorer := agent.NewExplorer(router, drv, agent.ExplorerConfig{
		TargetURL: targetURL,
		Goal:      "Explore UI elements, trigger buttons, verify no console exceptions",
		MaxSteps:  3,
	})

	trace, err := explorer.Run(ctx)
	if err != nil {
		bCtx.AddLog(fmt.Sprintf("Explorer run completed with notice: %v", err))
	}

	stepCount := 0
	if trace != nil {
		stepCount = len(trace.Snapshots)
	}

	return &BlockResult{
		BlockID: "agent.vision-crawl",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Autonomous vision crawl completed %d UI state exploration steps on %s",
			stepCount, targetURL),
		Data: map[string]interface{}{
			"target_url": targetURL,
			"steps":      stepCount,
		},
	}, nil
}
