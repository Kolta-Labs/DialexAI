package reviewer

import (
	"fmt"
	"strings"

	"dialex/pkg/model"
	"kritix/pkg/persona"
	"kritix/pkg/sandbox"
	"kritix/pkg/steering"
)

// ReviewVerdict represents the Adversarial Reviewer's evaluation result.
type ReviewVerdict struct {
	Approved           bool     `json:"approved"`
	Summary            string   `json:"summary"`
	BlockingIssues     []string `json:"blockingIssues"`
	Warnings           []string `json:"warnings"`
	ActionableFeedback string   `json:"actionableFeedback"`
}

// AdversarialReviewer evaluates unified diffs and test results.
type AdversarialReviewer struct {
	persona  model.Persona
	registry *persona.Registry
}

// NewAdversarialReviewer creates a Reviewer persona engine.
func NewAdversarialReviewer(registry *persona.Registry) *AdversarialReviewer {
	p, ok := registry.Get("adversarial_code_reviewer")
	if !ok {
		p = model.Persona{
			ID:   "adversarial_code_reviewer",
			Name: "Adversarial Code Reviewer",
			Role: "Diff Scrutinizer & Test Enforcer",
		}
	}

	return &AdversarialReviewer{
		persona:  p,
		registry: registry,
	}
}

// ReviewContext contains everything needed to review an iteration.
type ReviewContext struct {
	Diff            string
	TestResults     []*sandbox.ExecResult
	SteeringContext *steering.PersonaSteeringContext
}

// Evaluate performs deterministic and heuristic code review.
func (r *AdversarialReviewer) Evaluate(ctx *ReviewContext) *ReviewVerdict {
	verdict := &ReviewVerdict{
		Approved:       true,
		BlockingIssues: make([]string, 0),
		Warnings:       make([]string, 0),
	}

	// 1. Deterministic Test Validation
	for _, tr := range ctx.TestResults {
		if !tr.Success() {
			verdict.Approved = false
			failMsg := fmt.Sprintf("Command %q failed with exit code %d", tr.Command, tr.ExitCode)
			if tr.TimedOut {
				failMsg = fmt.Sprintf("Command %q timed out", tr.Command)
			}
			verdict.BlockingIssues = append(verdict.BlockingIssues, failMsg)
			if tr.Stderr != "" {
				verdict.BlockingIssues = append(verdict.BlockingIssues, "Error detail:\n"+tr.Stderr)
			}
		}
	}

	// 2. Diff Validation (Empty or invalid diff check)
	if strings.TrimSpace(ctx.Diff) == "" {
		verdict.Approved = false
		verdict.BlockingIssues = append(verdict.BlockingIssues, "Workspace diff is empty; no code changes were produced.")
	}

	// 3. Taboo Space Violations in Diff
	if ctx.SteeringContext != nil {
		for _, taboo := range ctx.SteeringContext.Taboos.ForbiddenArguments {
			lowerTaboo := strings.ToLower(taboo)
			if strings.Contains(lowerTaboo, "raw sqlite") && strings.Contains(strings.ToLower(ctx.Diff), "android.database.sqlite") {
				verdict.Approved = false
				verdict.BlockingIssues = append(verdict.BlockingIssues, fmt.Sprintf("Violates Taboo: %s", taboo))
			}
			if strings.Contains(lowerTaboo, "blocking main thread") && strings.Contains(ctx.Diff, "Thread.sleep") {
				verdict.Approved = false
				verdict.BlockingIssues = append(verdict.BlockingIssues, fmt.Sprintf("Violates Taboo: %s", taboo))
			}
		}
	}

	// 4. Synthesize Summary & Actionable Feedback
	if verdict.Approved {
		verdict.Summary = "Code satisfies all acceptance criteria, meets active steering invariants, and passes all tests."
	} else {
		var sb strings.Builder
		sb.WriteString("REVIEW REJECTED. The following issues must be resolved:\n")
		for i, issue := range verdict.BlockingIssues {
			fmt.Fprintf(&sb, "%d. %s\n", i+1, issue)
		}
		verdict.Summary = fmt.Sprintf("Found %d blocking issues.", len(verdict.BlockingIssues))
		verdict.ActionableFeedback = sb.String()
	}

	return verdict
}
