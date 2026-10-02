package steering

import (
	"bufio"
	"fmt"
	"strings"

	"dialex/pkg/model"
)

// Binder manages persona-to-steering-rule mappings and compiles them into Persona DNA constraints.
type Binder struct {
	config SteeringConfig
	rules  map[string]RuleFile
}

// NewBinder creates a Binder with a given config and loaded rules.
func NewBinder(config SteeringConfig, rules []RuleFile) *Binder {
	ruleMap := make(map[string]RuleFile)
	for _, r := range rules {
		ruleMap[r.ID] = r
		ruleMap[r.Name] = r
	}

	return &Binder{
		config: config,
		rules:  ruleMap,
	}
}

// CompilePersonaSteering resolves the active rules for personaID and extracts Taboos and Heuristics.
func (b *Binder) CompilePersonaSteering(personaID string) *PersonaSteeringContext {
	ctx := &PersonaSteeringContext{
		PersonaID:  personaID,
		BoundRules: make([]RuleFile, 0),
		Taboos: model.TabooSpace{
			ForbiddenArguments: []string{},
			PenaltyAction:      "Reject code; demand adherence to project steering invariants.",
		},
		Heuristics: make([]model.HeuristicRule, 0),
	}

	activeRuleIDs := make(map[string]bool)

	// 1. Add Global Rules
	for _, gid := range b.config.GlobalRules {
		activeRuleIDs[gid] = true
	}

	// 2. Add Role-Specific Bound Rules
	if bound, ok := b.config.Bindings[personaID]; ok {
		for _, rid := range bound {
			activeRuleIDs[rid] = true
		}
	}

	// 3. Fallback: if no specific binding exists for coder/reviewer, bind standard matching rules
	if len(b.config.Bindings) == 0 {
		for id := range b.rules {
			activeRuleIDs[id] = true
		}
	}

	for id := range activeRuleIDs {
		rule, ok := b.rules[id]
		if !ok {
			continue
		}
		ctx.BoundRules = append(ctx.BoundRules, rule)
		b.extractConstraints(rule, ctx)
	}

	return ctx
}

func (b *Binder) extractConstraints(rule RuleFile, ctx *PersonaSteeringContext) {
	scanner := bufio.NewScanner(strings.NewReader(rule.Content))
	ruleIdx := 0

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		lower := strings.ToLower(line)

		// Detect Taboos (prohibitions, negative constraints)
		if strings.HasPrefix(line, "- [!]") ||
			strings.Contains(lower, "forbidden:") ||
			strings.Contains(lower, "taboo:") ||
			strings.HasPrefix(lower, "- never ") ||
			strings.HasPrefix(lower, "- do not ") ||
			strings.HasPrefix(lower, "never ") ||
			strings.HasPrefix(lower, "do not ") {
			clean := cleanRuleText(line)
			if clean != "" {
				ctx.Taboos.ForbiddenArguments = append(ctx.Taboos.ForbiddenArguments, clean)
			}
			continue
		}

		// Detect Heuristics (positive guidelines, idioms, invariants)
		if strings.HasPrefix(lower, "- always ") ||
			strings.HasPrefix(lower, "- prefer ") ||
			strings.HasPrefix(lower, "always ") ||
			strings.HasPrefix(lower, "prefer ") ||
			strings.Contains(lower, "heuristic:") ||
			strings.Contains(lower, "standard:") {
			clean := cleanRuleText(line)
			if clean != "" {
				ruleIdx++
				ctx.Heuristics = append(ctx.Heuristics, model.HeuristicRule{
					ID:                   fmt.Sprintf("heur_%s_%d", rule.ID, ruleIdx),
					Name:                 fmt.Sprintf("Rule from %s", rule.Name),
					FormulaOrMaxime:      clean,
					TriggerCondition:     "During code generation and code review evaluation.",
					ApplicationDirective: fmt.Sprintf("Enforce rule: %s", clean),
				})
			}
		}
	}
}

func cleanRuleText(text string) string {
	text = strings.TrimPrefix(text, "- [!]")
	text = strings.TrimPrefix(text, "- ")
	text = strings.TrimPrefix(text, "* ")
	text = strings.TrimSpace(text)
	return text
}
