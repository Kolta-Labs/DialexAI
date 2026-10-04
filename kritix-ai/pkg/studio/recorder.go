package studio

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"kritix/pkg/driver"
	"kritix/pkg/spec"
)

var (
	ErrMissingBusinessIntent = errors.New("mandatory business intent documentation missing: enterprise governance requires explicit intent to prevent black box tests")
	ErrMissingAuthorSDET     = errors.New("mandatory author SDET identifier missing: all recorded flows must be attributable to an engineer")
)

// HumanAction models a user interaction recorded in the Studio with explicit intent.
type HumanAction struct {
	Type            driver.ActionType `json:"type"`
	TargetID        string            `json:"target_id,omitempty"`
	TargetText      string            `json:"target_text,omitempty"`
	TargetRole      string            `json:"target_role,omitempty"`
	InputValue      string            `json:"input_value,omitempty"`
	Timestamp       time.Time         `json:"timestamp"`
	StepIntent      string            `json:"step_intent,omitempty"`      // Business rationale for this step
	ExpectedOutcome string            `json:"expected_outcome,omitempty"` // Specific assertion or invariant
}

// StudioSession captures an interactive human demonstration with mandatory intent documentation.
type StudioSession struct {
	SessionID         string        `json:"session_id"`
	JourneyName       string        `json:"journey_name"`
	TargetURL         string        `json:"target_url"`
	AuthorSDET        string        `json:"author_sdet"`         // Author attribution
	BusinessIntent    string        `json:"business_intent"`     // Mandatory domain intent
	VerificationRules []string      `json:"verification_rules"`  // Specific invariants verified
	Actions           []HumanAction `json:"actions"`
	StartTime         time.Time     `json:"start_time"`
}

// NewStudioSession creates an interactive demonstration session.
func NewStudioSession(sessionID, journeyName, targetURL string) *StudioSession {
	return &StudioSession{
		SessionID:         sessionID,
		JourneyName:       journeyName,
		TargetURL:         targetURL,
		Actions:           make([]HumanAction, 0),
		VerificationRules: make([]string, 0),
		StartTime:         time.Now(),
	}
}

// RecordInteraction appends a human action to the demonstration session.
func (s *StudioSession) RecordInteraction(action HumanAction) {
	if action.Timestamp.IsZero() {
		action.Timestamp = time.Now()
	}
	s.Actions = append(s.Actions, action)
}

// ValidateIntentDocumentation enforces reproducible enterprise documentation for recorded flows.
func (s *StudioSession) ValidateIntentDocumentation() error {
	if strings.TrimSpace(s.BusinessIntent) == "" || len(strings.TrimSpace(s.BusinessIntent)) < 15 {
		return ErrMissingBusinessIntent
	}
	if strings.TrimSpace(s.AuthorSDET) == "" {
		return ErrMissingAuthorSDET
	}
	return nil
}

// SynthesizeAutonomousScenario converts the human recording into a generalized BDD scenario.
func (s *StudioSession) SynthesizeAutonomousScenario() spec.Story {
	var criteria []spec.AcceptanceCriterion

	for i, a := range s.Actions {
		desc := ""
		switch a.Type {
		case driver.ActionClick:
			target := a.TargetText
			if target == "" {
				target = a.TargetID
			}
			desc = fmt.Sprintf("user clicks %s (%s)", target, a.TargetRole)
		case driver.ActionTypeKey:
			desc = fmt.Sprintf("user types %q into %s", a.InputValue, a.TargetID)
		default:
			desc = fmt.Sprintf("user performs %s", a.Type)
		}

		expected := "application advances without unhandled errors"
		if a.ExpectedOutcome != "" {
			expected = a.ExpectedOutcome
		}

		criterion := spec.AcceptanceCriterion{
			ID:    fmt.Sprintf("STEP-%02d", i+1),
			When:  desc,
			Then:  expected,
			EdgeCases: []string{
				"Rapid double-click simulation",
				"Empty input boundary submission",
			},
		}
		if a.StepIntent != "" {
			criterion.Given = fmt.Sprintf("intent: %s", a.StepIntent)
		}
		criteria = append(criteria, criterion)
	}

	description := fmt.Sprintf("Autonomous journey synthesized from human demonstration on %s.\nIntent: %s\nAuthor SDET: %s",
		s.TargetURL, s.BusinessIntent, s.AuthorSDET)

	return spec.Story{
		ID:                 s.SessionID,
		Title:              s.JourneyName,
		Description:        description,
		Priority:           spec.PriorityHigh,
		Source:             "studio:teach-the-agent",
		AcceptanceCriteria: criteria,
	}
}

// ExportPlaywrightTemplate converts the recorded journey into a clean, intent-annotated Playwright test.
func (s *StudioSession) ExportPlaywrightTemplate() string {
	var sb strings.Builder
	sb.WriteString("import { test, expect } from '@playwright/test';\n\n")
	sb.WriteString("/**\n")
	sb.WriteString(fmt.Sprintf(" * Journey: %s\n", s.JourneyName))
	sb.WriteString(fmt.Sprintf(" * Author SDET: %s\n", s.AuthorSDET))
	sb.WriteString(fmt.Sprintf(" * Business Intent: %s\n", s.BusinessIntent))
	if len(s.VerificationRules) > 0 {
		sb.WriteString(" * Invariants:\n")
		for _, rule := range s.VerificationRules {
			sb.WriteString(fmt.Sprintf(" *  - %s\n", rule))
		}
	}
	sb.WriteString(" */\n")
	sb.WriteString(fmt.Sprintf("test(%q, async ({ page }) => {\n", s.JourneyName))
	sb.WriteString(fmt.Sprintf("  // Step 1: Navigate to %s\n", s.TargetURL))
	sb.WriteString(fmt.Sprintf("  await page.goto(%q);\n\n", s.TargetURL))

	for i, a := range s.Actions {
		if a.StepIntent != "" {
			sb.WriteString(fmt.Sprintf("  // Step %d: %s\n", i+2, a.StepIntent))
		}
		switch a.Type {
		case driver.ActionClick:
			if a.TargetRole != "" && a.TargetText != "" {
				sb.WriteString(fmt.Sprintf("  await page.getByRole(%q, { name: %q }).click();\n", a.TargetRole, a.TargetText))
			} else if a.TargetID != "" {
				sb.WriteString(fmt.Sprintf("  await page.locator('#%s').click();\n", a.TargetID))
			}
		case driver.ActionTypeKey:
			sb.WriteString(fmt.Sprintf("  await page.locator('#%s').fill(%q);\n", a.TargetID, a.InputValue))
		}
		if a.ExpectedOutcome != "" {
			sb.WriteString(fmt.Sprintf("  // Invariant: %s\n", a.ExpectedOutcome))
		}
	}

	sb.WriteString("\n  // Assert page stability\n")
	sb.WriteString("  await expect(page).toHaveScreenshot();\n")
	sb.WriteString("});\n")
	return sb.String()
}
