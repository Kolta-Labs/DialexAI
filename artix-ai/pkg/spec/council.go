package spec

import (
	"context"
	"fmt"
	"strings"
	"sync/atomic"
	"time"

	"dialex/pkg/model"
	"kritix/pkg/persona"
	"kritix/pkg/repo"
)

// CouncilMember represents an assembled participant in the planning council.
type CouncilMember struct {
	Role    string        `json:"role"`
	Persona model.Persona `json:"persona"`
}

// Council drafts a story spec for the stakeholder roles. NOTE: it is template-based today. It
// calls no model and runs no deliberation rounds; the assembled members are not consulted.
// Output is a structured draft (generic acceptance criteria, one ADR, ecosystem-derived test
// commands) meant to be edited, not a substitute for real requirements analysis.
type Council struct {
	members  []CouncilMember
	registry *persona.Registry
}

// NewCouncil initializes a Stakeholder Council with the standard stakeholder team.
func NewCouncil(registry *persona.Registry) *Council {
	c := &Council{
		registry: registry,
		members:  make([]CouncilMember, 0),
	}

	roles := []struct {
		role string
		id   string
	}{
		{"Product Owner", "product_owner_lead"},
		{"Senior Architect", "senior_software_architect"},
		{"QA Lead", "qa_testing_lead"},
		{"Engineering Manager", "engineering_manager"},
	}

	for _, r := range roles {
		if p, ok := registry.Get(r.id); ok {
			c.members = append(c.members, CouncilMember{
				Role:    r.role,
				Persona: p,
			})
		}
	}

	return c
}

// Plan generates a draft StorySpec from the prompt using fixed templates (no model call).
func (c *Council) Plan(ctx context.Context, pCtx *PlanningContext) (*StorySpec, error) {
	if pCtx == nil || strings.TrimSpace(pCtx.StoryPrompt) == "" {
		return nil, fmt.Errorf("story prompt cannot be empty")
	}

	specID := fmt.Sprintf("STORY-%d", nextSpecNumber())
	title := deriveTitle(pCtx.StoryPrompt)

	spec := &StorySpec{
		ID:        specID,
		Title:     title,
		UserStory: fmt.Sprintf("As a developer/user, I want to %s, so that system functionality is enhanced.", pCtx.StoryPrompt),
		Style:     pCtx.Style,
		InScope: []string{
			pCtx.StoryPrompt,
			"Unit test verification suite covering edge cases",
		},
		OutOfScope: []string{
			"Unrelated refactors outside target domain",
			"Breaking public API contracts without deprecation window",
		},
		AcceptanceCriteria: []Scenario{
			{
				Name:  "Standard Success Path",
				Given: "the system is in a valid running state",
				When:  fmt.Sprintf("the user initiates: %s", pCtx.StoryPrompt),
				Then:  "the operation succeeds with expected state transitions and returns status OK",
			},
			{
				Name:  "Adversarial Error & Timeout Handling",
				Given: "downstream dependencies experience transient failure or network timeout",
				When:  "the operation is triggered",
				Then:  "the system fails safely without leaking unhandled exceptions or state corruption",
			},
		},
		Decisions: []ArchitectureDecision{
			{
				ID:       "ADR-01",
				Title:    "Layer Isolation & Boundary Protection",
				Context:  "Implementing feature while respecting project architectural boundaries.",
				Decision: "Encapsulate logic within domain repository layer; expose clean interface abstractions.",
				Consequences: []string{
					"Prevents direct database coupling in UI presentation layer",
					"Ensures independent testability with mock drivers",
				},
			},
		},
		FileManifest: make([]FileMutation, 0),
		TestCommands: make([]string, 0),
	}

	// Tailor test commands based on detected repo ecosystem
	if pCtx.RepoContext != nil {
		for _, eco := range pCtx.RepoContext.DetectedEcos {
			switch eco {
			case repo.EcosystemGo:
				spec.TestCommands = append(spec.TestCommands, "go test -v ./...")
			case repo.EcosystemGradleKMP:
				spec.TestCommands = append(spec.TestCommands, "./gradlew testDebugUnitTest", "./gradlew ktlintCheck")
			case repo.EcosystemRust:
				spec.TestCommands = append(spec.TestCommands, "cargo test", "cargo clippy")
			case repo.EcosystemNode:
				spec.TestCommands = append(spec.TestCommands, "npm test")
			case repo.EcosystemPython:
				spec.TestCommands = append(spec.TestCommands, "pytest")
			}
		}
	}

	if len(spec.TestCommands) == 0 {
		spec.TestCommands = append(spec.TestCommands, "go test ./...")
	}

	// Apply Style Vectors
	switch pCtx.Style {
	case StylePonytail:
		spec.UserStory = fmt.Sprintf("Executive Summary: Strategic implementation of %q to optimize delivery velocity and user value.", pCtx.StoryPrompt)
	case StyleCaveman:
		spec.UserStory = fmt.Sprintf("Build %q. Write tests. Ensure zero regressions.", pCtx.StoryPrompt)
	}

	spec.RawMarkdown = FormatToMarkdown(spec)
	return spec, nil
}

func deriveTitle(prompt string) string {
	words := strings.Fields(prompt)
	if len(words) > 6 {
		return strings.Join(words[:6], " ") + "..."
	}
	return prompt
}

var lastSpecNumber atomic.Int64

// nextSpecNumber returns a millisecond timestamp that is strictly increasing within the
// process, so two specs planned in the same instant never share an ID (and file name).
func nextSpecNumber() int64 {
	for {
		prev := lastSpecNumber.Load()
		n := time.Now().UnixMilli()
		if n <= prev {
			n = prev + 1
		}
		if lastSpecNumber.CompareAndSwap(prev, n) {
			return n
		}
	}
}
