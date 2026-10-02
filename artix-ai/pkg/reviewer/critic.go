package reviewer

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"unicode/utf8"

	"socratix/pkg/model"
	"socratix/pkg/runner"
)

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

const maxCriticDiffBytes = 60_000

type criticReply struct {
	Approved bool     `json:"approved"`
	Blocking []string `json:"blocking"`
	Warnings []string `json:"warnings"`
}

// applyCritic runs the model pass. It fails closed: a critic error or an unparsable reply
// rejects the change, because "the reviewer was unavailable" must never read as "approved".
func (r *AdversarialReviewer) applyCritic(ctx context.Context, rc *ReviewContext, v *ReviewVerdict) {
	if ctx == nil {
		ctx = context.Background()
	}
	raw, err := r.critic(ctx, buildCriticPrompt(rc))
	if err != nil {
		v.Approved = false
		v.BlockingIssues = append(v.BlockingIssues, fmt.Sprintf("Model review failed (%v); change not approved.", err))
		return
	}
	parsed, err := parseCriticReply(raw)
	if err != nil {
		v.Approved = false
		v.BlockingIssues = append(v.BlockingIssues, "Model review returned an unparsable reply; change not approved.")
		return
	}
	v.Warnings = append(v.Warnings, parsed.Warnings...)
	if !parsed.Approved || len(parsed.Blocking) > 0 {
		v.Approved = false
		if len(parsed.Blocking) == 0 {
			parsed.Blocking = []string{"Model review rejected the change without giving a reason."}
		}
		for _, b := range parsed.Blocking {
			v.BlockingIssues = append(v.BlockingIssues, "Model review: "+b)
		}
	}
}

func buildCriticPrompt(rc *ReviewContext) string {
	var sb strings.Builder
	sb.WriteString("Review this diff against the acceptance criteria. Look for unmet criteria, bugs, missing edge cases and tests that would pass without proving the behaviour.\n\n")
	sb.WriteString("ACCEPTANCE CRITERIA:\n")
	if len(rc.Criteria) == 0 {
		sb.WriteString("(none provided; review for defects only)\n")
	}
	for i, c := range rc.Criteria {
		fmt.Fprintf(&sb, "%d. %s\n", i+1, c)
	}
	sb.WriteString("\nTEST RESULTS:\n")
	for _, t := range rc.TestResults {
		fmt.Fprintf(&sb, "- %q exit=%d timedOut=%v\n", t.Command, t.ExitCode, t.TimedOut)
	}
	diff := rc.Diff
	if len(diff) > maxCriticDiffBytes {
		diff = strings.ToValidUTF8(diff[:maxCriticDiffBytes], "") + "\n...[diff truncated]"
	}
	sb.WriteString("\nDIFF:\n" + diff + "\n\n")
	sb.WriteString(`Reply with ONLY this JSON: {"approved": bool, "blocking": ["..."], "warnings": ["..."]}. Set approved=false and list blocking issues if any criterion is unmet or a real defect exists.`)
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
