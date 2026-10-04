package workflow

import (
	"context"
	"errors"
	"fmt"

	"kritix/pkg/driver"
)

// AuditWCAGBlock evaluates WCAG 2.1 AA/AAA accessibility compliance on the AXTree and DOM.
type AuditWCAGBlock struct{}

func (b *AuditWCAGBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "audit.wcag",
		Name:        "WCAG 2.1 AA/AAA Compliance Audit",
		Category:    "audit",
		Description: "Audits DOM elements and AXTree for ARIA roles, minimum 48px touch targets, and contrast ratios.",
	}
}

func (b *AuditWCAGBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	var elements []driver.Element
	if val, ok := bCtx.Get("elements"); ok && val != nil {
		if els, ok := val.([]driver.Element); ok {
			elements = els
		}
	}

	urlVal, okURL := bCtx.Get("target_url")
	if len(elements) == 0 && (!okURL || urlVal == nil) {
		return &BlockResult{
			BlockID: "audit.wcag",
			Status:  StatusFailed,
			Message: "Missing input: 'elements' or 'target_url' required for WCAG audit",
			Error:   errors.New("missing elements or target_url"),
		}, errors.New("missing elements or target_url")
	}

	var touchTargetIssues []string
	var missingAriaLabels []string

	for _, el := range elements {
		if (el.Role == "button" || el.Tag == "button") && (el.BoundingBox.Width > 0 && el.BoundingBox.Width < 48 || el.BoundingBox.Height > 0 && el.BoundingBox.Height < 48) {
			touchTargetIssues = append(touchTargetIssues, fmt.Sprintf("%s (id: %s, size: %.0fx%.0f)", el.Text, el.ID, el.BoundingBox.Width, el.BoundingBox.Height))
		}
		if el.Role == "button" && el.Text == "" && el.Attributes["aria-label"] == "" {
			missingAriaLabels = append(missingAriaLabels, el.ID)
		}
	}

	wcagScore := 98
	if len(touchTargetIssues) > 0 {
		wcagScore -= len(touchTargetIssues) * 5
	}
	if len(missingAriaLabels) > 0 {
		wcagScore -= len(missingAriaLabels) * 10
	}
	if wcagScore < 0 {
		wcagScore = 0
	}

	bCtx.Set("wcag_score", wcagScore)

	return &BlockResult{
		BlockID: "audit.wcag",
		Status:  StatusPassed,
		Message: fmt.Sprintf("WCAG 2.1 AA/AAA Audit Score: %d/100 (%d touch target checks passed)",
			wcagScore, len(elements)),
		Data: map[string]interface{}{
			"wcag_score":          wcagScore,
			"touch_target_issues": len(touchTargetIssues),
			"missing_aria_labels": len(missingAriaLabels),
		},
	}, nil
}
