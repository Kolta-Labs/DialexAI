package tui

import (
	"fmt"
	"strings"

	"artix/pkg/persona"
)

// GrillQuestion represents a probing question formulated by a stakeholder.
type GrillQuestion struct {
	Role     string `json:"role"`
	Question string `json:"question"`
	Guidance string `json:"guidance"`
}

// AlignmentInterview conducts an interactive pre-planning interview.
type AlignmentInterview struct {
	registry *persona.Registry
}

// NewAlignmentInterview creates an interview manager.
func NewAlignmentInterview(reg *persona.Registry) *AlignmentInterview {
	return &AlignmentInterview{registry: reg}
}

// GenerateQuestions generates 3 Socratic alignment questions to eliminate requirements ambiguity.
func (ai *AlignmentInterview) GenerateQuestions(prompt string) []GrillQuestion {
	trimmed := strings.TrimSpace(prompt)
	if trimmed == "" {
		trimmed = "Feature Implementation"
	}

	return []GrillQuestion{
		{
			Role:     "Product Owner",
			Question: fmt.Sprintf("For %q, what is the exact fallback behavior when upstream dependencies are unreachable or timeout?", trimmed),
			Guidance: "Define offline fallback vs fatal error dialog.",
		},
		{
			Role:     "Senior Architect",
			Question: fmt.Sprintf("Which architectural boundary encapsulates the state changes for %q, and what taboo constraints must be protected?", trimmed),
			Guidance: "Prevent direct DB writes from presentation/UI layer.",
		},
		{
			Role:     "QA Lead",
			Question: fmt.Sprintf("What deterministic verification commands and negative test scenarios must pass before sign-off on %q?", trimmed),
			Guidance: "Identify unit/integration test targets and mock dependencies.",
		},
	}
}
