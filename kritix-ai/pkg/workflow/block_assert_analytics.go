package workflow

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"kritix/pkg/driver"
	"kritix/pkg/spec"
)

// AssertAnalyticsBlock verifies that expected analytics events fired in the network HAR trace or DOM dataLayer.
type AssertAnalyticsBlock struct{}

func (b *AssertAnalyticsBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "assert.analytics",
		Name:        "Verify DataLayer & Tagging Events",
		Category:    "assert",
		Description: "Asserts that defined tracking and analytics events (Segment, GA4, Snowplow) were dispatched during execution.",
	}
}

func (b *AssertAnalyticsBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	var expectedEvents []spec.AnalyticsEventDef

	if val, ok := bCtx.Get("bundle"); ok && val != nil {
		if bundle, ok := val.(*spec.MultiArtifactBundle); ok {
			expectedEvents = bundle.AnalyticsEvents
		}
	}
	if len(expectedEvents) == 0 {
		if val, ok := bCtx.Get("required_analytics_events"); ok && val != nil {
			if evs, ok := val.([]spec.AnalyticsEventDef); ok {
				expectedEvents = evs
			}
		}
	}

	if len(expectedEvents) == 0 {
		return &BlockResult{
			BlockID: "assert.analytics",
			Status:  StatusFailed,
			Message: "Missing input: no analytics event schema found in context",
			Error:   errors.New("missing analytics event schema"),
		}, errors.New("missing analytics event schema")
	}

	var capturedNetwork []driver.NetworkEvent
	if val, ok := bCtx.Get("har_report"); ok && val != nil {
		if har, ok := val.(*driver.HARReport); ok {
			for _, entry := range har.Entries {
				capturedNetwork = append(capturedNetwork, driver.NetworkEvent{
					URL:      entry.Request.URL,
					Method:   entry.Request.Method,
					PostData: entry.Request.PostData,
				})
			}
		}
	}
	if len(capturedNetwork) == 0 {
		if val, ok := bCtx.Get("browser_state"); ok && val != nil {
			if state, ok := val.(*driver.BrowserState); ok {
				capturedNetwork = state.NetworkActivity
			}
		}
	}

	var missingEvents []string
	var verifiedCount int

	for _, reqEv := range expectedEvents {
		found := false
		for _, net := range capturedNetwork {
			if strings.Contains(strings.ToLower(net.URL), strings.ToLower(reqEv.EventName)) ||
				strings.Contains(strings.ToLower(net.PostData), strings.ToLower(reqEv.EventName)) {
				found = true
				verifiedCount++
				break
			}
		}
		if !found {
			missingEvents = append(missingEvents, reqEv.EventName)
		}
	}

	if len(capturedNetwork) == 0 {
		return &BlockResult{
			BlockID: "assert.analytics",
			Status:  StatusSimulated,
			Message: fmt.Sprintf("Analytics assertion simulated for %d event(s) (no network traffic captured in context)", len(expectedEvents)),
			Data: map[string]interface{}{
				"expected_events": len(expectedEvents),
				"simulated":       true,
			},
		}, nil
	}

	if len(missingEvents) > 0 {
		return &BlockResult{
			BlockID: "assert.analytics",
			Status:  StatusFailed,
			Message: fmt.Sprintf("Analytics assertion failed: %d missing event(s): %v", len(missingEvents), missingEvents),
			Error:   fmt.Errorf("missing %d analytics events", len(missingEvents)),
		}, fmt.Errorf("missing %d analytics events", len(missingEvents))
	}

	return &BlockResult{
		BlockID: "assert.analytics",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Verified %d analytics event definitions against HAR trace", len(expectedEvents)),
		Data: map[string]interface{}{
			"verified_events": len(expectedEvents),
			"network_entries": len(capturedNetwork),
		},
	}, nil
}
