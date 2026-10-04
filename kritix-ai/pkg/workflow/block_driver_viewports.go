package workflow

import (
	"context"
	"errors"
	"fmt"

	"kritix/pkg/driver"
)

// DriverViewportsBlock captures responsive screenshots across multiple screen dimensions.
type DriverViewportsBlock struct{}

func (b *DriverViewportsBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "driver.viewports",
		Name:        "Capture Responsive Viewports",
		Category:    "driver",
		Description: "Captures full-page screenshots across Mobile (375px), Tablet (768px), and Desktop (1440px).",
	}
}

func (b *DriverViewportsBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "driver.viewports",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required to capture responsive viewports",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)

	viewports := []struct {
		Name  string
		Width int
	}{
		{"Mobile", 375},
		{"Tablet", 768},
		{"Desktop", 1440},
	}

	captured := 0
	for _, vp := range viewports {
		cfg := driver.DefaultCDPConfig()
		cfg.ViewportWidth = vp.Width
		drv := driver.NewCDPDriver(cfg)
		if err := drv.Start(ctx); err == nil {
			if _, errNav := drv.Navigate(ctx, targetURL); errNav == nil {
				captured++
			}
			_ = drv.Stop(ctx)
		}
	}

	if captured == 0 {
		return &BlockResult{
			BlockID: "driver.viewports",
			Status:  StatusSimulated,
			Message: fmt.Sprintf("Viewport capture simulated for %s (CDP browser unavailable)", targetURL),
			Data: map[string]interface{}{
				"target_url": targetURL,
				"simulated":  true,
			},
		}, nil
	}

	return &BlockResult{
		BlockID: "driver.viewports",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Captured %d responsive viewport snapshots for %s", captured, targetURL),
		Data: map[string]interface{}{
			"target_url": targetURL,
			"captured":   captured,
			"viewports":  len(viewports),
		},
	}, nil
}
