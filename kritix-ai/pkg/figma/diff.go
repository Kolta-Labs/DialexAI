package figma

import (
	"fmt"
	"strings"
)

// TokenType categorizes design variables.
type TokenType string

const (
	TokenColor      TokenType = "color"
	TokenTypography TokenType = "typography"
	TokenDimension  TokenType = "dimension"
	TokenRadius     TokenType = "border_radius"
)

// FigmaToken models a design token extracted from a Figma canvas.
type FigmaToken struct {
	Name          string    `json:"name"`           // e.g. "button.primary.background"
	Type          TokenType `json:"type"`
	TargetElement string    `json:"target_element"` // e.g. "#checkout-btn"
	CSSProperty   string    `json:"css_property"`   // e.g. "background-color"
	ExpectedValue string    `json:"expected_value"` // e.g. "#2563eb"
}

// TokenMismatch records a divergence between the Figma design token and the rendered DOM CSS.
type TokenMismatch struct {
	TokenName      string    `json:"token_name"`
	TargetElement  string    `json:"target_element"`
	CSSProperty    string    `json:"css_property"`
	ExpectedValue  string    `json:"expected_value"`
	ActualDOMValue string    `json:"actual_dom_value"`
	Type           TokenType `json:"type"`
}

// CompareTokens evaluates rendered element styles against authoritative Figma tokens.
func CompareTokens(tokens []FigmaToken, elementStyles map[string]map[string]string) []TokenMismatch {
	var mismatches []TokenMismatch

	for _, t := range tokens {
		elemStyles, exists := elementStyles[t.TargetElement]
		if !exists {
			mismatches = append(mismatches, TokenMismatch{
				TokenName:      t.Name,
				TargetElement:  t.TargetElement,
				CSSProperty:    t.CSSProperty,
				ExpectedValue:  t.ExpectedValue,
				ActualDOMValue: "element_not_found",
				Type:           t.Type,
			})
			continue
		}

		actual, hasProp := elemStyles[t.CSSProperty]
		if !hasProp || !normalizeStyle(actual, t.ExpectedValue) {
			mismatches = append(mismatches, TokenMismatch{
				TokenName:      t.Name,
				TargetElement:  t.TargetElement,
				CSSProperty:    t.CSSProperty,
				ExpectedValue:  t.ExpectedValue,
				ActualDOMValue: actual,
				Type:           t.Type,
			})
		}
	}

	return mismatches
}

func normalizeStyle(actual, expected string) bool {
	a := strings.ToLower(strings.TrimSpace(actual))
	e := strings.ToLower(strings.TrimSpace(expected))
	return a == e || strings.ReplaceAll(a, " ", "") == strings.ReplaceAll(e, " ", "")
}

// FormatMismatchSummary generates an executive design review table.
func FormatMismatchSummary(mismatches []TokenMismatch) string {
	if len(mismatches) == 0 {
		return "✓ 100% Visual Design Token Fidelity. DOM matches Figma canvas exactly.\n"
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("### 🎨 Figma Visual Design Divergences (%d found)\n", len(mismatches)))
	for _, m := range mismatches {
		sb.WriteString(fmt.Sprintf("- **%s** on `%s`: Expected `%s: %s`, got `%s`\n",
			m.TokenName, m.TargetElement, m.CSSProperty, m.ExpectedValue, m.ActualDOMValue))
	}
	sb.WriteString("\n")
	return sb.String()
}
