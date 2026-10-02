package quickstart

import (
	"fmt"
	"strings"

	"dialex/pkg/model"
)

// Confidence is a deliberately blunt, rule-based trust level for a council's verdict.
// It is a heuristic, not a probability.
type Confidence struct {
	Level   string // "Low", "Medium" or "High"
	Reasons []string
}

// AssessConfidence caps trust by what is unresolved, not by how fluent the answer sounds.
// Rules: no verdict or a failed run = Low; any open tension of severity >= 0.7 = Low; any other
// open tension = Medium; and when every seat is the same model the level never exceeds Medium,
// because personas on one model share its blind spots.
func AssessConfidence(res model.DebateResult, cfg model.DebateConfig) Confidence {
	c := Confidence{Level: "High"}
	lower := func(to, why string) {
		rank := map[string]int{"Low": 0, "Medium": 1, "High": 2}
		if rank[to] < rank[c.Level] {
			c.Level = to
		}
		c.Reasons = append(c.Reasons, why)
	}
	if res.Error != nil {
		lower("Low", "the run ended with an error")
	}
	if res.Conclusion == nil || strings.TrimSpace(*res.Conclusion) == "" {
		lower("Low", "no final verdict was produced")
	}
	open, severe := 0, 0
	for _, t := range res.TensionPairs {
		if t.Status == model.TensionStatusOpen || t.Status == model.TensionStatusExplored {
			open++
			if t.Severity >= 0.7 {
				severe++
			}
		}
	}
	if severe > 0 {
		lower("Low", fmt.Sprintf("%d serious disagreement(s) are still open", severe))
	} else if open > 0 {
		lower("Medium", fmt.Sprintf("%d disagreement(s) are still open", open))
	}
	if sameModel(cfg) {
		lower("Medium", "all seats are personas on one model, which share its blind spots")
	}
	return c
}

func sameModel(cfg model.DebateConfig) bool {
	agents := cfg.Agents()
	for _, a := range agents[1:] {
		if a.Provider != agents[0].Provider || a.Model != agents[0].Model {
			return false
		}
	}
	return len(agents) > 1
}

// Memo renders a decision memo: the question, what each seat first argued independently, the
// disagreements left open, the verdict, and how far to trust it.
func Memo(mode Mode, topic string, cfg model.DebateConfig, res model.DebateResult) string {
	var b strings.Builder
	fmt.Fprintf(&b, "# Decision memo: %s\n\n", mode.Name)
	fmt.Fprintf(&b, "**Question:** %s\n\n", strings.TrimSpace(topic))
	conf := AssessConfidence(res, cfg)
	fmt.Fprintf(&b, "**Trust:** %s", conf.Level)
	if len(conf.Reasons) > 0 {
		fmt.Fprintf(&b, " (%s)", strings.Join(conf.Reasons, "; "))
	}
	b.WriteString("\n\n## Verdict\n\n")
	if res.Conclusion != nil && strings.TrimSpace(*res.Conclusion) != "" {
		b.WriteString(strings.TrimSpace(*res.Conclusion) + "\n")
	} else if res.Error != nil {
		fmt.Fprintf(&b, "_No verdict. The run stopped: %s_\n", *res.Error)
	} else {
		b.WriteString("_No verdict was produced._\n")
	}

	b.WriteString("\n## Independent first positions\n\n")
	b.WriteString("Written before the seats saw each other's answers.\n\n")
	seen := false
	for _, m := range res.Transcript {
		if m.Round == 1 && !m.IsError && !m.IsSystem && !m.IsUserComment && m.SeatID != cfg.Primary.ID {
			fmt.Fprintf(&b, "### %s\n\n%s\n\n", m.AuthorDisplayName, strings.TrimSpace(m.Content))
			seen = true
		}
	}
	if !seen {
		b.WriteString("_None recorded._\n\n")
	}

	b.WriteString("## Open disagreements\n\n")
	n := 0
	for _, t := range res.TensionPairs {
		if t.Status == model.TensionStatusOpen || t.Status == model.TensionStatusExplored {
			fmt.Fprintf(&b, "- %s (severity %.1f)\n", t.UnderlyingConflict, t.Severity)
			n++
		}
	}
	if n == 0 {
		b.WriteString("_None detected. That is not proof there were none: the detector is itself a model._\n")
	}

	b.WriteString("\n## How this was made\n\n")
	var names []string
	for _, a := range cfg.Agents() {
		names = append(names, a.Label())
	}
	p := cfg.Primary
	fmt.Fprintf(&b, "- Council: %s, on %s (%s)\n", strings.Join(names, ", "), p.Provider.BrandName(), p.Model)
	fmt.Fprintf(&b, "- Rounds: %d. Blind first round: %v. Anonymized peers: %v.\n", cfg.MaxRounds,
		cfg.Independence != nil && cfg.Independence.BlindFirstRound, cfg.Independence != nil && cfg.Independence.AnonymizeTranscript)
	b.WriteString("- Personas on one model are a thinking aid, not independent experts. Verify the facts that matter.\n")
	return b.String()
}
