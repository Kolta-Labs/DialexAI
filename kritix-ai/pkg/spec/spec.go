package spec

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// Priority defines the urgency of a user story.
type Priority string

const (
	PriorityCritical Priority = "critical"
	PriorityHigh     Priority = "high"
	PriorityMedium   Priority = "medium"
	PriorityLow      Priority = "low"
)

// SpecApprovalStatus enforces human SDET review gates on all AI-synthesized specifications.
type SpecApprovalStatus string

const (
	// SpecDraftPendingReview is the mandatory initial state for any AI-generated Gherkin.
	// Cannot be executed autonomously in CI/CD release gates without human review.
	SpecDraftPendingReview SpecApprovalStatus = "DRAFT_PENDING_SDET_REVIEW"

	// SpecApprovedBySDET indicates a qualified human test architect reviewed and approved the spec.
	SpecApprovedBySDET SpecApprovalStatus = "APPROVED_BY_SDET"

	// SpecRejected indicates the AI synthesis was hallucinated, inaccurate, or incomplete.
	SpecRejected SpecApprovalStatus = "REJECTED"
)

// AcceptanceCriterion models a single business verification condition.
type AcceptanceCriterion struct {
	ID        string   `json:"id"`
	Given     string   `json:"given"`
	When      string   `json:"when"`
	Then      string   `json:"then"`
	EdgeCases []string `json:"edge_cases,omitempty"`
}

// Story models a user story / ticket ingested from Jira, Linear, or PRD.
type Story struct {
	ID                 string                `json:"id"`
	Title              string                `json:"title"`
	AsA                string                `json:"as_a,omitempty"`
	IWant              string                `json:"i_want,omitempty"`
	SoThat             string                `json:"so_that,omitempty"`
	Description        string                `json:"description"`
	Priority           Priority              `json:"priority"`
	Source             string                `json:"source,omitempty"` // "jira:PROJ-123", "linear:ENG-456", "github:#78"
	AcceptanceCriteria []AcceptanceCriterion `json:"acceptance_criteria"`

	// Human-in-the-loop review governance fields
	ApprovalStatus SpecApprovalStatus `json:"approval_status"`
	ApprovedBy     string             `json:"approved_by,omitempty"`
	ApprovedAt     time.Time          `json:"approved_at,omitempty"`
	ReviewNotes    string             `json:"review_notes,omitempty"`
}

// NewStory creates a story in mandatory DRAFT_PENDING_SDET_REVIEW state.
func NewStory(title, desc string) Story {
	return Story{
		Title:          title,
		Description:    desc,
		Priority:       PriorityMedium,
		ApprovalStatus: SpecDraftPendingReview,
	}
}

// Approve marks a synthesized specification as verified by a human SDET.
func (s *Story) Approve(reviewerEmail, notes string) error {
	if strings.TrimSpace(reviewerEmail) == "" {
		return errors.New("reviewer email is required for SDET sign-off")
	}
	s.ApprovalStatus = SpecApprovedBySDET
	s.ApprovedBy = reviewerEmail
	s.ApprovedAt = time.Now()
	s.ReviewNotes = notes
	return nil
}

// Reject rejects the AI-synthesized spec with actionable feedback.
func (s *Story) Reject(reviewerEmail, reason string) error {
	if strings.TrimSpace(reason) == "" {
		return errors.New("rejection reason is required")
	}
	s.ApprovalStatus = SpecRejected
	s.ApprovedBy = reviewerEmail
	s.ApprovedAt = time.Now()
	s.ReviewNotes = reason
	return nil
}

// IsApprovedForExecution ensures autonomous CI gates only execute human-vetted specs.
func (s *Story) IsApprovedForExecution() bool {
	return s.ApprovalStatus == SpecApprovedBySDET
}

// INVESTAssessment evaluates whether a story meets agile INVEST criteria.
type INVESTAssessment struct {
	Independent      bool     `json:"independent"`
	Negotiable       bool     `json:"negotiable"`
	Valuable         bool     `json:"valuable"`
	Estimable        bool     `json:"estimable"`
	Small            bool     `json:"small"`
	Testable         bool     `json:"testable"`
	Score            int      `json:"score"` // 0 to 100
	IdentifiedGaps   []string `json:"identified_gaps"`
	AmbiguousPhrases []string `json:"ambiguous_phrases"`
}

// EvaluateINVEST evaluates the testability and ambiguity of a user story.
func EvaluateINVEST(s Story) INVESTAssessment {
	var gaps []string
	var ambiguous []string

	// Check for vague buzzwords common in poorly specified stories
	vagueWords := []string{"fast", "user-friendly", "intuitive", "robust", "etc", "as appropriate", "should be easy"}
	descLower := strings.ToLower(s.Description + " " + s.Title)
	for _, vw := range vagueWords {
		if strings.Contains(descLower, vw) {
			ambiguous = append(ambiguous, fmt.Sprintf("Vague terminology: %q", vw))
		}
	}

	valuable := true
	if s.SoThat == "" && !strings.Contains(descLower, "so that") && !strings.Contains(descLower, "in order to") {
		gaps = append(gaps, "Missing explicit business outcome / 'So That' statement")
		valuable = false
	}

	testable := true
	if len(s.AcceptanceCriteria) == 0 {
		gaps = append(gaps, "No concrete acceptance criteria defined")
		testable = false
	}

	small := len(s.AcceptanceCriteria) <= 5
	if !small {
		gaps = append(gaps, "Story contains more than 5 acceptance criteria (recommend splitting)")
	}

	score := 100
	score -= len(ambiguous) * 10
	score -= len(gaps) * 20
	if score < 0 {
		score = 0
	}

	return INVESTAssessment{
		Independent:      true,
		Negotiable:       true,
		Valuable:         valuable,
		Estimable:        true,
		Small:            small,
		Testable:         testable,
		Score:            score,
		IdentifiedGaps:   gaps,
		AmbiguousPhrases: ambiguous,
	}
}

// GenerateGherkin renders executable BDD feature files with review gate headers.
func GenerateGherkin(s Story) string {
	var sb strings.Builder

	status := s.ApprovalStatus
	if status == "" {
		status = SpecDraftPendingReview
	}

	sb.WriteString(fmt.Sprintf("# Spec Governance Status: %s\n", status))
	if s.IsApprovedForExecution() {
		sb.WriteString(fmt.Sprintf("# Approved by SDET: %s on %s\n", s.ApprovedBy, s.ApprovedAt.Format("2006-01-02 15:04:05")))
		if s.ReviewNotes != "" {
			sb.WriteString(fmt.Sprintf("# Review Notes: %s\n", s.ReviewNotes))
		}
	} else {
		sb.WriteString("# ⚠️ MANDATORY HUMAN REVIEW GATE: Draft synthesized spec. Do not execute in CI release gates without SDET sign-off.\n")
	}
	sb.WriteString("\n")

	sb.WriteString(fmt.Sprintf("Feature: %s\n", s.Title))
	if s.Description != "" {
		sb.WriteString(fmt.Sprintf("  %s\n", strings.ReplaceAll(s.Description, "\n", "\n  ")))
	}
	sb.WriteString("\n")

	for i, ac := range s.AcceptanceCriteria {
		scenarioName := fmt.Sprintf("AC-%02d: %s", i+1, ac.Given)
		if ac.When != "" {
			scenarioName = fmt.Sprintf("AC-%02d: When %s", i+1, ac.When)
		}
		sb.WriteString(fmt.Sprintf("  Scenario: %s\n", scenarioName))
		if ac.Given != "" {
			sb.WriteString(fmt.Sprintf("    Given %s\n", ac.Given))
		}
		if ac.When != "" {
			sb.WriteString(fmt.Sprintf("    When %s\n", ac.When))
		}
		if ac.Then != "" {
			sb.WriteString(fmt.Sprintf("    Then %s\n", ac.Then))
		}
		for _, edge := range ac.EdgeCases {
			sb.WriteString(fmt.Sprintf("    # Edge Case: %s\n", edge))
		}
		sb.WriteString("\n")
	}

	return sb.String()
}
