package coder

import (
	"fmt"
	"strings"

	"socratix/pkg/model"
	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/spec"
	"artix/pkg/steering"
)

// DomainCoder compiles prompts and manages generation for domain-specific implementation.
type DomainCoder struct {
	persona  model.Persona
	registry *persona.Registry
}

// NewDomainCoder creates a DomainCoder for a specific engineering domain persona ID.
func NewDomainCoder(domainPersonaID string, registry *persona.Registry) (*DomainCoder, error) {
	p, ok := registry.Get(domainPersonaID)
	if !ok {
		// Fallback to backend_engineer if domain not found
		p, ok = registry.Get("backend_engineer")
		if !ok {
			return nil, fmt.Errorf("domain persona %s not found", domainPersonaID)
		}
	}

	return &DomainCoder{
		persona:  p,
		registry: registry,
	}, nil
}

// PromptContext encapsulates everything the coder needs to generate an atomic patch.
type PromptContext struct {
	Spec             *spec.StorySpec
	RepoContext      *repo.RepositoryContext
	SteeringContext  *steering.PersonaSteeringContext
	ReviewerFeedback string
	PriorFailures    []string
}

// CompilePrompt builds the comprehensive system and task instructions for the coder.
func (c *DomainCoder) CompilePrompt(ctx *PromptContext) (systemPrompt, userPrompt string) {
	var sys strings.Builder

	fmt.Fprintf(&sys, "You are %s, an elite %s.\n", c.persona.Name, c.persona.Role)
	if c.persona.DNA != nil {
		fmt.Fprintf(&sys, "Domain Authority: %s\n", c.persona.DNA.CoreIdentity.DomainAuthority)
		if len(c.persona.DNA.TabooSpace.ForbiddenArguments) > 0 {
			sys.WriteString("\nSTRICT ARCHITECTURAL TABOOS (DO NOT VIOLATE):\n")
			for _, taboo := range c.persona.DNA.TabooSpace.ForbiddenArguments {
				fmt.Fprintf(&sys, "- %s\n", taboo)
			}
		}
	}

	// Injected dynamic steering rules
	if ctx.SteeringContext != nil {
		if len(ctx.SteeringContext.Taboos.ForbiddenArguments) > 0 {
			sys.WriteString("\nPROJECT STEERING TABOOS:\n")
			for _, t := range ctx.SteeringContext.Taboos.ForbiddenArguments {
				fmt.Fprintf(&sys, "- %s\n", t)
			}
		}
		if len(ctx.SteeringContext.Heuristics) > 0 {
			sys.WriteString("\nPROJECT CODING INVARIANTS & STANDARDS:\n")
			for _, h := range ctx.SteeringContext.Heuristics {
				fmt.Fprintf(&sys, "- %s\n", h.FormulaOrMaxime)
			}
		}
	}

	sys.WriteString("\nMANDATE: Output code modifications STRICTLY as a unified git diff format (--- a/... +++ b/...). Do not include conversational markdown around the patch.")

	var user strings.Builder
	fmt.Fprintf(&user, "TASK SPECIFICATION: %s (ID: %s)\n\n", ctx.Spec.Title, ctx.Spec.ID)
	fmt.Fprintf(&user, "User Story: %s\n\n", ctx.Spec.UserStory)

	if len(ctx.Spec.AcceptanceCriteria) > 0 {
		user.WriteString("ACCEPTANCE CRITERIA (Must Satisfy All):\n")
		for _, ac := range ctx.Spec.AcceptanceCriteria {
			fmt.Fprintf(&user, "- [%s] Given %s, When %s, Then %s\n", ac.Name, ac.Given, ac.When, ac.Then)
		}
		user.WriteString("\n")
	}

	if len(ctx.Spec.FileManifest) > 0 {
		user.WriteString("TARGET FILE MANIFEST:\n")
		for _, f := range ctx.Spec.FileManifest {
			fmt.Fprintf(&user, "- `%s` (%s): %s\n", f.Path, f.Action, f.Rationale)
		}
		user.WriteString("\n")
	}

	if ctx.ReviewerFeedback != "" {
		fmt.Fprintf(&user, "CRITICAL FEEDBACK FROM ADVERSARIAL REVIEWER (Address all issues):\n%s\n\n", ctx.ReviewerFeedback)
	}

	if len(ctx.PriorFailures) > 0 {
		user.WriteString("PREVIOUS COMPILER / TEST SUITE FAILURES:\n")
		for _, fail := range ctx.PriorFailures {
			fmt.Fprintf(&user, "```\n%s\n```\n", fail)
		}
		user.WriteString("\n")
	}

	user.WriteString("Generate the unified git patch now.")

	return sys.String(), user.String()
}
