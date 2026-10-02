package reviewer

import (
	"context"
	"fmt"
	"strings"

	"socratix/pkg/model"
	"artix/pkg/persona"
	"artix/pkg/sandbox"
	"artix/pkg/steering"
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
	critic   Critic
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
	// Ctx and Criteria feed the optional model review (see SetCritic); both may be empty.
	Ctx      context.Context
	Criteria []string
}

// Evaluate performs a deterministic, rule-based review: test exit codes, a non-empty diff, and
// a small set of built-in taboo patterns. It is not a model-based review and does not evaluate
// acceptance criteria; Summary and Warnings say exactly what was and was not checked.
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

	if len(ctx.TestResults) == 0 {
		verdict.Warnings = append(verdict.Warnings, "No test commands were run; approval reflects diff checks only.")
	}

	// 3. Taboo Space Violations in Diff (only taboos with a built-in pattern can be enforced)
	var unchecked []string
	if ctx.SteeringContext != nil {
		for _, taboo := range ctx.SteeringContext.Taboos.ForbiddenArguments {
			lowerTaboo := strings.ToLower(taboo)
			checked := false
			if strings.Contains(lowerTaboo, "raw sqlite") {
				checked = true
				if strings.Contains(strings.ToLower(ctx.Diff), "android.database.sqlite") {
					verdict.Approved = false
					verdict.BlockingIssues = append(verdict.BlockingIssues, fmt.Sprintf("Violates Taboo: %s", taboo))
				}
			}
			if strings.Contains(lowerTaboo, "blocking main thread") {
				checked = true
				if strings.Contains(ctx.Diff, "Thread.sleep") {
					verdict.Approved = false
					verdict.BlockingIssues = append(verdict.BlockingIssues, fmt.Sprintf("Violates Taboo: %s", taboo))
				}
			}
			if !checked {
				unchecked = append(unchecked, taboo)
			}
		}
	}
	if len(unchecked) > 0 {
		verdict.Warnings = append(verdict.Warnings, fmt.Sprintf("%d steering taboo(s) have no automated check and were not enforced: %s", len(unchecked), strings.Join(unchecked, "; ")))
	}

	// 4. Model review (only when a critic is configured)
	if r.critic != nil {
		r.applyCritic(ctx.Ctx, ctx, verdict)
	}

	// 5. Synthesize Summary & Actionable Feedback
	if verdict.Approved && r.critic != nil {
		verdict.Summary = fmt.Sprintf("Approved by rule-based checks (%d test command(s) passed, diff non-empty, no built-in taboo matched) and a model review against %d acceptance criteria.", len(ctx.TestResults), len(ctx.Criteria))
	} else if verdict.Approved {
		verdict.Summary = fmt.Sprintf("Approved by rule-based checks: %d test command(s) passed, diff is non-empty, no built-in taboo pattern matched. Acceptance criteria are not evaluated.", len(ctx.TestResults))
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
