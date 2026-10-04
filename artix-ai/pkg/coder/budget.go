package coder

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"unicode/utf8"

	"socratix/pkg/model"
)

// TaskType defines the operational context for compiling Persona DNA layers.
type TaskType string

const (
	TaskCodeGeneration   TaskType = "code_generation"
	TaskPatchReview      TaskType = "patch_review"
	TaskSpecDeliberation TaskType = "spec_deliberation"
	TaskSecurityAudit    TaskType = "security_audit"
)

// TokenBudget defines token limits at story, team, and daily levels.
type TokenBudget struct {
	MaxStoryTokens  int    `json:"maxStoryTokens"`
	MaxTeamTokens   int    `json:"maxTeamTokens"`
	MaxDayTokens    int    `json:"maxDayTokens"`
	UsedStoryTokens int    `json:"usedStoryTokens"`
	UsedTeamTokens  int    `json:"usedTeamTokens"`
	UsedDayTokens   int    `json:"usedDayTokens"`
	TeamID          string `json:"teamId,omitempty"`
}

// LoadBudgetFromEnv loads budget limits from ARTIX_BUDGET_* / KRITIX_BUDGET_* environment variables.
func LoadBudgetFromEnv() *TokenBudget {
	b := &TokenBudget{}
	hasEnv := false

	if v := getEnv("ARTIX_BUDGET_PER_STORY", "KRITIX_BUDGET_PER_STORY"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			b.MaxStoryTokens = n
			hasEnv = true
		}
	}
	if v := getEnv("ARTIX_BUDGET_PER_TEAM", "KRITIX_BUDGET_PER_TEAM"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			b.MaxTeamTokens = n
			hasEnv = true
		}
	}
	if v := getEnv("ARTIX_BUDGET_PER_DAY", "KRITIX_BUDGET_PER_DAY"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			b.MaxDayTokens = n
			hasEnv = true
		}
	}
	if v := getEnv("ARTIX_TEAM_ID", "KRITIX_TEAM_ID"); v != "" {
		b.TeamID = v
	}

	if !hasEnv {
		return nil
	}
	return b
}

func getEnv(keys ...string) string {
	for _, k := range keys {
		if v := os.Getenv(k); v != "" {
			return v
		}
	}
	return ""
}

// EstimateTokens calculates an approximate token count based on string length and content.
func EstimateTokens(s string) int {
	if s == "" {
		return 0
	}
	// A standard heuristic: ~4 characters per token
	count := (utf8.RuneCountInString(s) + 3) / 4
	if count < 1 {
		return 1
	}
	return count
}

// RoundCost tracks token metrics for a single round of the convergence loop.
type RoundCost struct {
	Round            int            `json:"round"`
	RoleTokens       map[string]int `json:"roleTokens"`
	DNAOverhead      map[string]int `json:"dnaOverhead"`
	TotalRoundTokens int            `json:"totalRoundTokens"`
}

// CostReport is the structured summary of token consumption across the convergence loop.
type CostReport struct {
	StoryID           string      `json:"storyId"`
	TeamID            string      `json:"teamId,omitempty"`
	TotalTokens       int         `json:"totalTokens"`
	BudgetStoryCap    int         `json:"budgetStoryCap,omitempty"`
	BudgetTeamCap     int         `json:"budgetTeamCap,omitempty"`
	BudgetDayCap      int         `json:"budgetDayCap,omitempty"`
	Exhausted         bool        `json:"exhausted"`
	ExhaustionReason  string      `json:"exhaustionReason,omitempty"`
	Rounds            []RoundCost `json:"rounds"`
	DNAOverheadTokens int         `json:"dnaOverheadTokens"`
}

// CompileDNALayers renders only the DNA layers relevant to the given task type.
// For example, patch review omits Communication Style, Temporal Mode, etc.
func CompileDNALayers(dna *model.PersonaDNA, task TaskType) string {
	if dna == nil {
		return ""
	}
	var sb strings.Builder

	// Core Identity: Domain Authority is essential across all tasks
	if dna.CoreIdentity.DomainAuthority != "" {
		fmt.Fprintf(&sb, "Domain Authority: %s\n", dna.CoreIdentity.DomainAuthority)
	}

	// Taboo Space: Forbidden arguments apply to all tasks
	if len(dna.TabooSpace.ForbiddenArguments) > 0 {
		sb.WriteString("\nSTRICT ARCHITECTURAL TABOOS (DO NOT VIOLATE):\n")
		for _, taboo := range dna.TabooSpace.ForbiddenArguments {
			fmt.Fprintf(&sb, "- %s\n", taboo)
		}
	}

	switch task {
	case TaskCodeGeneration:
		// Code generation uses heuristic rules, domain ontology standards, and synthesis preference
		if len(dna.HeuristicLibrary) > 0 {
			sb.WriteString("\nDOMAIN HEURISTICS & RULES:\n")
			for _, h := range dna.HeuristicLibrary {
				fmt.Fprintf(&sb, "- [%s] %s\n", h.Name, h.FormulaOrMaxime)
			}
		}
		if len(dna.DomainOntology.MandatoryStandards) > 0 {
			sb.WriteString("\nMANDATORY STANDARDS:\n")
			for _, s := range dna.DomainOntology.MandatoryStandards {
				fmt.Fprintf(&sb, "- %s\n", s)
			}
		}
		if dna.SynthesisPreference.Style != "" {
			fmt.Fprintf(&sb, "Synthesis Style: %s\n", dna.SynthesisPreference.Style)
		}
		// Explicitly omits: CommunicationVector (tone, sentence ceiling, rhetorical devices)

	case TaskPatchReview:
		// Reviewer requires adversarial posture, mandatory standards, and taboo space, but omits communication style
		if dna.AdversarialPosture.Stance != "" {
			fmt.Fprintf(&sb, "Reviewer Posture: %s (Tenacity: %.2f)\n", dna.AdversarialPosture.Stance, dna.AdversarialPosture.TenacityScore)
		}
		if len(dna.DomainOntology.MandatoryStandards) > 0 {
			sb.WriteString("\nREVIEW COMPLIANCE STANDARDS:\n")
			for _, s := range dna.DomainOntology.MandatoryStandards {
				fmt.Fprintf(&sb, "- %s\n", s)
			}
		}
		// Explicitly omits: CommunicationVector, EpistemicBias theory ratio

	case TaskSpecDeliberation:
		// Council deliberation uses epistemic bias, communication vector, and synthesis preference
		if dna.EpistemicBias.PrimaryMode != "" {
			fmt.Fprintf(&sb, "Reasoning Mode: %s\n", dna.EpistemicBias.PrimaryMode)
		}
		if dna.CommunicationVector.Tone != "" {
			fmt.Fprintf(&sb, "Communication Tone: %s\n", dna.CommunicationVector.Tone)
		}
		if dna.SynthesisPreference.Style != "" {
			fmt.Fprintf(&sb, "Synthesis Style: %s\n", dna.SynthesisPreference.Style)
		}

	case TaskSecurityAudit:
		if dna.AdversarialPosture.Stance != "" {
			fmt.Fprintf(&sb, "Audit Posture: %s\n", dna.AdversarialPosture.Stance)
		}
		if len(dna.DomainOntology.MandatoryStandards) > 0 {
			sb.WriteString("\nAUDIT MANDATORY STANDARDS:\n")
			for _, ms := range dna.DomainOntology.MandatoryStandards {
				fmt.Fprintf(&sb, "- %s\n", ms)
			}
		}
	}

	return sb.String()
}
