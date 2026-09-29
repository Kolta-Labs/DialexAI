package knowledge

import (
	"fmt"
	"strings"
	"time"
)

// RuleSynthesizer converts human review comments into structured steering rules.
type RuleSynthesizer struct{}

// NewRuleSynthesizer creates a synthesizer.
func NewRuleSynthesizer() *RuleSynthesizer {
	return &RuleSynthesizer{}
}

// SynthesizeFromComment analyzes reviewer text and outputs a structured rule.
func (rs *RuleSynthesizer) SynthesizeFromComment(comment string) (*SynthesizedRule, error) {
	trimmed := strings.TrimSpace(comment)
	if trimmed == "" {
		return nil, fmt.Errorf("comment cannot be empty")
	}

	lower := strings.ToLower(trimmed)
	isTaboo := strings.Contains(lower, "never") ||
		strings.Contains(lower, "don't") ||
		strings.Contains(lower, "do not") ||
		strings.Contains(lower, "avoid") ||
		strings.Contains(lower, "prohibited")

	// Determine roles
	roles := []string{"adversarial_code_reviewer"}
	if strings.Contains(lower, "android") || strings.Contains(lower, "compose") || strings.Contains(lower, "ui") {
		roles = append(roles, "android_engineer")
	}
	if strings.Contains(lower, "backend") || strings.Contains(lower, "database") || strings.Contains(lower, "sql") {
		roles = append(roles, "backend_engineer")
	}
	if strings.Contains(lower, "security") || strings.Contains(lower, "token") || strings.Contains(lower, "auth") {
		roles = append(roles, "security_auditor")
	}

	ruleID := fmt.Sprintf("rule-syn-%d", time.Now().UnixNano()%1000000)
	name := deriveRuleName(trimmed)

	return &SynthesizedRule{
		RuleID:      ruleID,
		Name:        name,
		IsTaboo:     isTaboo,
		RuleText:    trimmed,
		Rationale:   "Synthesized automatically from human code review comment.",
		TargetRoles: roles,
	}, nil
}

func deriveRuleName(text string) string {
	words := strings.Fields(text)
	if len(words) > 5 {
		return strings.Join(words[:5], " ") + "..."
	}
	return text
}
