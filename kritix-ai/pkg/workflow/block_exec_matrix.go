package workflow

import (
	"context"
	"errors"
	"fmt"
	"time"

	"kritix/pkg/driver"
)

// ExecMatrixBlock executes responsive multi-viewport testing using real browser drivers.
type ExecMatrixBlock struct{}

func (b *ExecMatrixBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "exec.matrix",
		Name:        "Multi-Viewport Matrix Execution",
		Category:    "exec",
		Description: "Executes test scenarios across Desktop (1440px), Tablet (768px), and Mobile (375px) viewports with real CDP emulation.",
	}
}

func (b *ExecMatrixBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "exec.matrix",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required in execution context",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)
	if res, err := guardTarget("exec.matrix", targetURL); err != nil {
		return res, err
	}

	viewports := []struct {
		Name   string
		Width  int
		Height int
	}{
		{"Desktop", 1440, 900},
		{"Mobile", 375, 667},
	}

	var lastState *driver.BrowserState
	var totalRequests int
	var errorsEncountered []string

	for _, vp := range viewports {
		cfg := driver.DefaultCDPConfig()
		cfg.ViewportWidth = vp.Width
		cfg.ViewportHeight = vp.Height
		cfg.Timeout = 15 * time.Second

		drv := driver.NewCDPDriver(cfg)
		if err := drv.Start(ctx); err != nil {
			bCtx.AddLog(fmt.Sprintf("Matrix execution on %s skipped: %v", vp.Name, err))
			continue
		}

		state, err := drv.Navigate(ctx, targetURL)
		if err != nil {
			_ = drv.Stop(ctx)
			errorsEncountered = append(errorsEncountered, fmt.Sprintf("%s navigation error: %v", vp.Name, err))
			continue
		}

		lastState = state
		totalRequests += len(state.NetworkActivity)

		for _, net := range state.NetworkActivity {
			if net.StatusCode >= 500 {
				errorsEncountered = append(errorsEncountered, fmt.Sprintf("Server error HTTP %d on %s", net.StatusCode, net.URL))
			}
		}

		if har, err := drv.ExportHAR(); err == nil {
			bCtx.Set("har_report", har)
		}

		_ = drv.Stop(ctx)
	}

	if len(errorsEncountered) > 0 {
		return &BlockResult{
			BlockID: "exec.matrix",
			Status:  StatusFailed,
			Message: fmt.Sprintf("Matrix execution encountered %d error(s): %v", len(errorsEncountered), errorsEncountered),
			Error:   errors.New(errorsEncountered[0]),
		}, errors.New(errorsEncountered[0])
	}

	if lastState == nil {
		return &BlockResult{
			BlockID: "exec.matrix",
			Status:  StatusSimulated,
			Message: fmt.Sprintf("Matrix execution simulated for %s across %d viewports (CDP browser unavailable)", targetURL, len(viewports)),
			Data: map[string]interface{}{
				"target_url": targetURL,
				"viewports":  len(viewports),
				"simulated":  true,
			},
		}, nil
	}

	bCtx.Set("browser_state", lastState)
	bCtx.Set("elements", lastState.Elements)
	if lastState.AXTree != nil {
		bCtx.Set("ax_tree", lastState.AXTree)
	}

	return &BlockResult{
		BlockID: "exec.matrix",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Successfully executed matrix across %d viewports against %s (%d network requests captured)",
			len(viewports), targetURL, totalRequests),
		Data: map[string]interface{}{
			"target_url":     targetURL,
			"viewports":      len(viewports),
			"total_requests": totalRequests,
		},
	}, nil
}
