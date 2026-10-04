package workflow

import (
	"fmt"
	"regexp"
	"strings"

	"kritix/pkg/driver"
	"kritix/pkg/spec"
)

// BindingStatus indicates whether a Gherkin step or scenario is bound to real AXTree elements.
type BindingStatus string

const (
	StatusBoundVerified      BindingStatus = "BOUND_VERIFIED"
	StatusNeedsClarification BindingStatus = "NEEDS_CLARIFICATION"
)

// StepBinding tracks the association between a Gherkin step and an AXNode.
type StepBinding struct {
	StepText      string        `json:"step_text"`
	Keyword       string        `json:"keyword"` // Given, When, Then
	TargetQuery   string        `json:"target_query"`
	BoundNodeID   string        `json:"bound_node_id,omitempty"`
	BoundRole     string        `json:"bound_role,omitempty"`
	Status        BindingStatus `json:"status"`
	Clarification string        `json:"clarification,omitempty"`
}

// ScenarioBindingResult contains the validation outcome for a complete Gherkin scenario.
type ScenarioBindingResult struct {
	ScenarioName  string        `json:"scenario_name"`
	Status        BindingStatus `json:"status"`
	Bindings      []StepBinding `json:"bindings"`
	UnboundSteps  []string      `json:"unbound_steps,omitempty"`
	Clarification string        `json:"clarification,omitempty"`
}

// ValidateGherkinAgainstAXTree parses acceptance criteria or Gherkin scenarios and dry-runs step binding
// against the target page's Accessibility Tree (AXTree) and interactive DOM elements.
func ValidateGherkinAgainstAXTree(criteria []spec.AcceptanceCriterion, axRoot *driver.AXNode, elements []driver.Element) []ScenarioBindingResult {
	results := make([]ScenarioBindingResult, len(criteria))

	for i, ac := range criteria {
		scenarioName := fmt.Sprintf("Scenario %d: %s", i+1, ac.Given)
		if ac.When != "" {
			scenarioName = fmt.Sprintf("Scenario %d: When %s", i+1, ac.When)
		}

		var bindings []StepBinding
		var unbound []string

		// 1. Bind Given step
		if ac.Given != "" {
			b := bindStep("Given", ac.Given, axRoot, elements)
			bindings = append(bindings, b)
			if b.Status == StatusNeedsClarification {
				unbound = append(unbound, fmt.Sprintf("Given %s (%s)", ac.Given, b.Clarification))
			}
		}

		// 2. Bind When step
		if ac.When != "" {
			b := bindStep("When", ac.When, axRoot, elements)
			bindings = append(bindings, b)
			if b.Status == StatusNeedsClarification {
				unbound = append(unbound, fmt.Sprintf("When %s (%s)", ac.When, b.Clarification))
			}
		}

		// 3. Bind Then step
		if ac.Then != "" {
			b := bindStep("Then", ac.Then, axRoot, elements)
			bindings = append(bindings, b)
			if b.Status == StatusNeedsClarification {
				unbound = append(unbound, fmt.Sprintf("Then %s (%s)", ac.Then, b.Clarification))
			}
		}

		status := StatusBoundVerified
		clarification := ""
		if len(unbound) > 0 {
			status = StatusNeedsClarification
			clarification = fmt.Sprintf("NEEDS_CLARIFICATION: %d step(s) could not be bound to any element in the active AXTree/DOM: %s",
				len(unbound), strings.Join(unbound, "; "))
		}

		results[i] = ScenarioBindingResult{
			ScenarioName:  scenarioName,
			Status:        status,
			Bindings:      bindings,
			UnboundSteps:  unbound,
			Clarification: clarification,
		}
	}

	return results
}

func bindStep(keyword, stepText string, axRoot *driver.AXNode, elements []driver.Element) StepBinding {
	// Extract target entities quoted or named in step
	target := extractTargetEntity(stepText)

	// Search in elements list first
	for _, el := range elements {
		if matchesElement(target, el) {
			return StepBinding{
				StepText:    stepText,
				Keyword:     keyword,
				TargetQuery: target,
				BoundNodeID: el.ID,
				BoundRole:   el.Role,
				Status:      StatusBoundVerified,
			}
		}
	}

	// Search recursively in AXTree
	if axRoot != nil {
		if node := findInAXTree(axRoot, target); node != nil {
			return StepBinding{
				StepText:    stepText,
				Keyword:     keyword,
				TargetQuery: target,
				BoundNodeID: node.ID,
				BoundRole:   node.Role,
				Status:      StatusBoundVerified,
			}
		}
	}

	return StepBinding{
		StepText:      stepText,
		Keyword:       keyword,
		TargetQuery:   target,
		Status:        StatusNeedsClarification,
		Clarification: fmt.Sprintf("No matching AXTree node or interactive element found for target query %q", target),
	}
}

var quoteRegex = regexp.MustCompile(`["']([^"']+)["']`)

func extractTargetEntity(step string) string {
	matches := quoteRegex.FindAllStringSubmatch(step, -1)
	if len(matches) > 0 && len(matches[0]) > 1 {
		return matches[0][1]
	}
	// Strip common keywords
	cleaned := strings.ToLower(step)
	cleaned = strings.TrimPrefix(cleaned, "user clicks ")
	cleaned = strings.TrimPrefix(cleaned, "they click ")
	cleaned = strings.TrimPrefix(cleaned, "user enters ")
	cleaned = strings.TrimPrefix(cleaned, "user sees ")
	cleaned = strings.TrimPrefix(cleaned, "banner displays ")
	cleaned = strings.TrimPrefix(cleaned, "on ")
	cleaned = strings.TrimPrefix(cleaned, "the ")
	return strings.TrimSpace(cleaned)
}

func matchesElement(query string, el driver.Element) bool {
	q := strings.ToLower(query)
	if strings.EqualFold(el.TestID, query) || strings.EqualFold(el.ID, query) {
		return true
	}
	if strings.Contains(strings.ToLower(el.Text), q) || strings.Contains(strings.ToLower(el.Placeholder), q) {
		return true
	}
	if strings.EqualFold(el.Role, query) {
		return true
	}
	return false
}

func findInAXTree(node *driver.AXNode, query string) *driver.AXNode {
	if node == nil {
		return nil
	}
	q := strings.ToLower(query)
	if strings.Contains(strings.ToLower(node.Name), q) || strings.Contains(strings.ToLower(node.Description), q) || strings.EqualFold(node.Role, query) {
		return node
	}
	for i := range node.Children {
		if found := findInAXTree(&node.Children[i], query); found != nil {
			return found
		}
	}
	return nil
}
