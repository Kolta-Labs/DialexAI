package figma

import (
	"strings"
	"testing"
)

func TestCompareTokens(t *testing.T) {
	tokens := []FigmaToken{
		{
			Name:          "button.primary.bg",
			Type:          TokenColor,
			TargetElement: "#submit-btn",
			CSSProperty:   "background-color",
			ExpectedValue: "#2563eb",
		},
		{
			Name:          "button.primary.radius",
			Type:          TokenRadius,
			TargetElement: "#submit-btn",
			CSSProperty:   "border-radius",
			ExpectedValue: "8px",
		},
	}

	// 1. Mismatch scenario (dev used #1d4ed8 and 4px radius)
	domStylesMismatch := map[string]map[string]string{
		"#submit-btn": {
			"background-color": "#1d4ed8",
			"border-radius":    "4px",
		},
	}

	mismatches := CompareTokens(tokens, domStylesMismatch)
	if len(mismatches) != 2 {
		t.Fatalf("expected 2 design token mismatches, got %d", len(mismatches))
	}

	summary := FormatMismatchSummary(mismatches)
	if !strings.Contains(summary, "Figma Visual Design Divergences") {
		t.Errorf("summary missing title")
	}

	// 2. Exact match scenario
	domStylesMatch := map[string]map[string]string{
		"#submit-btn": {
			"background-color": "#2563eb",
			"border-radius":    "8px",
		},
	}

	matchResults := CompareTokens(tokens, domStylesMatch)
	if len(matchResults) != 0 {
		t.Errorf("expected 0 mismatches for exact match, got %d", len(matchResults))
	}
}
