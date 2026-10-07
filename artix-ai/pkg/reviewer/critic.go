package reviewer

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf8"

	"artix/pkg/policy"
	"socratix/pkg/model"
	"socratix/pkg/runner"
)

// DefaultMaxCriticDiffBytes is the default maximum unified diff size submitted for model review (500 KB).
const DefaultMaxCriticDiffBytes = 500_000

// Critic asks a model one question and returns its raw reply. Any engine AgentRunner can back it.
type Critic func(ctx context.Context, prompt string) (string, error)

// RunnerCritic adapts an engine AgentRunner (API or CLI) into a Critic.
func RunnerCritic(r runner.AgentRunner, agent model.Agent) Critic {
	return func(ctx context.Context, prompt string) (string, error) {
		reply, err := r.Respond(ctx, agent, "Adversarial code review", prompt, "You are an adversarial code reviewer. Reply with JSON only.", nil, "")
		if err != nil {
			return "", err
		}
		return reply.Content, nil
	}
}

// SetCritic adds a model-backed review pass on top of the rule-based checks.
func (r *AdversarialReviewer) SetCritic(c Critic) { r.critic = c }

// SetCriticWithFamily registers a Critic along with its model family and verifies policy constraints.
func (r *AdversarialReviewer) SetCriticWithFamily(c Critic, family string) error {
	trimmed := strings.TrimSpace(family)
	pol := policy.Active()
	if pol.Reviewer.EnforceDisjointModelFamilies && r.coderFamily != "" && trimmed != "" {
		if strings.EqualFold(r.coderFamily, trimmed) {
			return fmt.Errorf("reviewer policy violation: critic model family %q matches coder family %q (disjoint model families required)", trimmed, r.coderFamily)
		}
	}
	if len(pol.Reviewer.AllowedCriticFamilies) > 0 && trimmed != "" {
		allowed := false
		for _, f := range pol.Reviewer.AllowedCriticFamilies {
			if strings.EqualFold(f, trimmed) {
				allowed = true
				break
			}
		}
		if !allowed {
			return fmt.Errorf("reviewer policy violation: critic model family %q not in allowedCriticFamilies", trimmed)
		}
	}
	r.critic = c
	r.criticFamily = trimmed
	return nil
}

// SetMaxCriticDiffBytes configures the maximum diff size in bytes accepted for model review.
func (r *AdversarialReviewer) SetMaxCriticDiffBytes(n int) {
	if n > 0 {
		r.maxCriticDiffBytes = n
	}
}

type criticReply struct {
	Approved bool     `json:"approved"`
	Blocking []string `json:"blocking"`
	Warnings []string `json:"warnings"`
}

// applyCritic runs the model pass. It fails closed:
// 1. If the diff exceeds maxCriticDiffBytes, it HARD REJECTS immediately with DIFF_TRUNCATED.
// 2. A critic error or unparsable reply marks the verdict as StatusUnreviewed and not approved.
func (r *AdversarialReviewer) applyCritic(ctx context.Context, rc *ReviewContext, v *ReviewVerdict) {
	if ctx == nil {
		ctx = context.Background()
	}

	maxLimit := r.maxCriticDiffBytes
	if maxLimit <= 0 {
		maxLimit = DefaultMaxCriticDiffBytes
	}

	// Hard rejection on diff truncation: model review cannot be authoritative on an incomplete diff
	if len(rc.Diff) > maxLimit {
		v.Approved = false
		v.Status = StatusRejected
		v.BlockingIssues = append(v.BlockingIssues, fmt.Sprintf("DIFF_TRUNCATED: model review cannot be authoritative on an incomplete diff (diff size %d bytes exceeds maximum review limit %d bytes)", len(rc.Diff), maxLimit))
		return
	}

	raw, err := r.critic(ctx, buildCriticPrompt(rc))
	if err != nil {
		v.Approved = false
		v.Status = StatusUnreviewed
		v.BlockingIssues = append(v.BlockingIssues, fmt.Sprintf("Model review failed or unreachable (%v); change marked as unreviewed.", err))
		return
	}
	parsed, err := parseCriticReply(raw)
	if err != nil {
		v.Approved = false
		v.Status = StatusUnreviewed
		v.BlockingIssues = append(v.BlockingIssues, fmt.Sprintf("Model review returned an unparsable reply (%v); change marked as unreviewed.", err))
		return
	}
	v.Warnings = append(v.Warnings, parsed.Warnings...)
	if !parsed.Approved || len(parsed.Blocking) > 0 {
		v.Approved = false
		v.Status = StatusRejected
		if len(parsed.Blocking) == 0 {
			parsed.Blocking = []string{"Model review rejected the change without giving a reason."}
		}
		for _, b := range parsed.Blocking {
			v.BlockingIssues = append(v.BlockingIssues, "Model review: "+b)
		}
	} else {
		v.Approved = true
		v.Status = StatusApproved
	}
}

func buildCriticPrompt(rc *ReviewContext) string {
	var sb strings.Builder
	sb.WriteString(`You are an Adversarial Code Reviewer and Security Auditor. Evaluate the provided diff against the following mandatory security and correctness rubric:

### 1. SECURITY RUBRIC (Zero Tolerance)
- Injection & Memory Safety: No SQL/command injection, path traversal, buffer overflows, or unchecked allocations.
- Concurrency & Race Conditions: No data races, deadlocks, goroutine leaks, or unconfined thread blocking.
- Auth & Secrets: No hardcoded credentials, plain-text tokens, or bypass of authentication/authorization gates.
- Sandbox & Resource Confinement: Respect process boundaries and resource limits.

### 2. CORRECTNESS & SPEC ALIGNMENT RUBRIC
- Acceptance Criteria: Satisfies all specified Given/When/Then scenarios.
- Regression Risk: Does not break existing interfaces, invariants, or edge cases.
- Invariants & Standards: Adheres strictly to project coding invariants and architectural constraints.
`)

	if len(rc.Criteria) > 0 {
		sb.WriteString("\nACCEPTANCE CRITERIA:\n")
		for i, c := range rc.Criteria {
			fmt.Fprintf(&sb, "%d. %s\n", i+1, c)
		}
	} else {
		sb.WriteString("\nACCEPTANCE CRITERIA:\n(none provided; review for defects and security issues only)\n")
	}

	if len(rc.AnalyzerFindings) > 0 {
		sb.WriteString("\nSTATIC ANALYZER FINDINGS (Mandatory Evidence from Konsist/Detekt/Semgrep/Vet):\n")
		for _, f := range rc.AnalyzerFindings {
			fmt.Fprintf(&sb, "- [%s] (%s): %s\n", f.Tool, f.Severity, f.Message)
		}
	}

	if len(rc.TestResults) > 0 {
		sb.WriteString("\nTEST RESULTS:\n")
		for _, t := range rc.TestResults {
			fmt.Fprintf(&sb, "- %q exit=%d timedOut=%v\n", t.Command, t.ExitCode, t.TimedOut)
		}
	}

	sb.WriteString("\nUNIFIED PATCH DIFF:\n" + rc.Diff + "\n\n")
	sb.WriteString(`Reply with ONLY this JSON: {"approved": bool, "blocking": ["..."], "warnings": ["..."]}. Set approved=false and list blocking issues if any criterion is unmet or a security/correctness defect exists.`)
	return sb.String()
}

// parseCriticReply pulls the JSON object out of a reply that may wrap it in prose or fences.
func parseCriticReply(raw string) (criticReply, error) {
	var out criticReply
	start, end := strings.Index(raw, "{"), strings.LastIndex(raw, "}")
	if start < 0 || end <= start || !utf8.ValidString(raw) {
		return out, fmt.Errorf("no JSON object in reply")
	}
	dec := json.NewDecoder(strings.NewReader(raw[start : end+1]))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&out); err != nil {
		return out, err
	}
	return out, nil
}
