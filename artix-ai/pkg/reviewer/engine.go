package reviewer

import (
	"context"
	"fmt"
	"strings"

	"artix/pkg/persona"
	"artix/pkg/sandbox"
	"artix/pkg/steering"
	"socratix/pkg/model"
)

// ReviewStatus represents the outcome category of a review evaluation.
type ReviewStatus string

const (
	StatusApproved   ReviewStatus = "approved"
	StatusRejected   ReviewStatus = "rejected"
	StatusUnreviewed ReviewStatus = "unreviewed"
)

// ReviewVerdict represents the Adversarial Reviewer's evaluation result.
type ReviewVerdict struct {
	Status             ReviewStatus `json:"status"` // "approved", "rejected", "unreviewed"
	Approved           bool         `json:"approved"`
	Summary            string       `json:"summary"`
	BlockingIssues     []string     `json:"blockingIssues"`
	Warnings           []string     `json:"warnings"`
	ActionableFeedback string       `json:"actionableFeedback"`
}

// AdversarialReviewer evaluates unified diffs and test results.
type AdversarialReviewer struct {
	persona            model.Persona
	registry           *persona.Registry
	critic             Critic
	maxCriticDiffBytes int
	globalTaboos       steering.GlobalTabooSpace
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
		persona:            p,
		registry:           registry,
		maxCriticDiffBytes: DefaultMaxCriticDiffBytes,
	}
}

// SetGlobalTaboos sets workspace-wide taboo constraints.
func (r *AdversarialReviewer) SetGlobalTaboos(gt steering.GlobalTabooSpace) {
	r.globalTaboos = gt
}

// ReviewContext contains everything needed to review an iteration.
type ReviewContext struct {
	Diff             string
	TestResults      []*sandbox.ExecResult
	AnalyzerFindings []AnalyzerFinding
	SteeringContext  *steering.PersonaSteeringContext
	Ctx              context.Context
	Criteria         []string
}

// Evaluate performs deterministic pre-filtering (tests, diff, taboos, static analyzers) and
// runs the model-backed Critic against security and correctness rubrics.
// If no model is configured or reachable, it returns StatusUnreviewed with Approved=false.
func (r *AdversarialReviewer) Evaluate(ctx *ReviewContext) *ReviewVerdict {
	verdict := &ReviewVerdict{
		Status:         StatusApproved,
		Approved:       true,
		BlockingIssues: make([]string, 0),
		Warnings:       make([]string, 0),
	}

	// 1. Deterministic Pre-Filter: Test Validation
	for _, tr := range ctx.TestResults {
		if !tr.Success() {
			verdict.Approved = false
			verdict.Status = StatusRejected
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

	// 2. Deterministic Pre-Filter: Diff Validation (Empty diff check)
	if strings.TrimSpace(ctx.Diff) == "" {
		verdict.Approved = false
		verdict.Status = StatusRejected
		verdict.BlockingIssues = append(verdict.BlockingIssues, "Workspace diff is empty; no code changes were produced.")
	}

	if len(ctx.TestResults) == 0 {
		verdict.Warnings = append(verdict.Warnings, "No test commands were run; pre-filter verified diff only.")
	}

	// 3. Deterministic Pre-Filter: Static Analyzer Findings
	for _, f := range ctx.AnalyzerFindings {
		if f.Severity == "ERROR" {
			verdict.Approved = false
			verdict.Status = StatusRejected
			verdict.BlockingIssues = append(verdict.BlockingIssues, fmt.Sprintf("Static analyzer %s violation: %s", f.Tool, f.Message))
		} else {
			verdict.Warnings = append(verdict.Warnings, fmt.Sprintf("Static analyzer %s warning: %s", f.Tool, f.Message))
		}
	}

	// 4. Deterministic Pre-Filter: Taboo Space Violations (Persona Taboos + Global Taboos)
	tabooList := make([]string, 0)
	if ctx.SteeringContext != nil {
		tabooList = append(tabooList, ctx.SteeringContext.Taboos.ForbiddenArguments...)
		tabooList = append(tabooList, ctx.SteeringContext.GlobalTaboos.ForbiddenArguments...)
	}
	tabooList = append(tabooList, r.globalTaboos.ForbiddenArguments...)

	diffLower := strings.ToLower(ctx.Diff)
	for _, taboo := range tabooList {
		lowerTaboo := strings.ToLower(strings.TrimSpace(taboo))
		if lowerTaboo == "" {
			continue
		}
		matched := false
		if strings.Contains(lowerTaboo, "raw sqlite") && strings.Contains(diffLower, "android.database.sqlite") {
			matched = true
		} else if strings.Contains(lowerTaboo, "blocking main thread") && strings.Contains(ctx.Diff, "Thread.sleep") {
			matched = true
		} else if strings.Contains(diffLower, lowerTaboo) {
			matched = true
		}

		if matched {
			verdict.Approved = false
			verdict.Status = StatusRejected
			verdict.BlockingIssues = append(verdict.BlockingIssues, fmt.Sprintf("Violates Taboo: %s", taboo))
		}
	}

	// If pre-filter failed, reject immediately without calling model
	if !verdict.Approved {
		var sb strings.Builder
		sb.WriteString("REVIEW REJECTED. The following pre-filter issues must be resolved:\n")
		for i, issue := range verdict.BlockingIssues {
			fmt.Fprintf(&sb, "%d. %s\n", i+1, issue)
		}
		verdict.Summary = fmt.Sprintf("Pre-filter failed with %d blocking issue(s).", len(verdict.BlockingIssues))
		verdict.ActionableFeedback = sb.String()
		return verdict
	}

	// 5. Model Review: Critic is the default path
	if r.critic == nil {
		verdict.Approved = false
		verdict.Status = StatusUnreviewed
		verdict.Summary = "UNREVIEWED: No model-backed Critic is configured. Pre-filter passed, but change requires model or human review before approval."
		verdict.ActionableFeedback = "Configure a model-backed Critic or perform manual code review."
		return verdict
	}

	r.applyCritic(ctx.Ctx, ctx, verdict)

	if verdict.Status == StatusUnreviewed {
		verdict.Summary = "UNREVIEWED: Critic model unreachable or failed; autonomous commit blocked."
		verdict.ActionableFeedback = "Retry review when model endpoint is available, or review manually."
		return verdict
	}

	if verdict.Approved {
		verdict.Status = StatusApproved
		verdict.Summary = fmt.Sprintf("Approved by rule-based pre-filter (%d test(s) passed, diff non-empty, no taboos violated) and model review against security/correctness rubric.", len(ctx.TestResults))
	} else {
		verdict.Status = StatusRejected
		var sb strings.Builder
		sb.WriteString("REVIEW REJECTED. The following issues must be resolved:\n")
		for i, issue := range verdict.BlockingIssues {
			fmt.Fprintf(&sb, "%d. %s\n", i+1, issue)
		}
		verdict.Summary = fmt.Sprintf("Review rejected with %d blocking issue(s).", len(verdict.BlockingIssues))
		verdict.ActionableFeedback = sb.String()
	}

	return verdict
}
