package decomposition

import (
	"context"
	"errors"
	"strings"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

func TestDecomposeProblemRejectsBlankTopic(t *testing.T) {
	for _, topic := range []string{"", "   \n\t"} {
		if _, err := DecomposeProblem(context.Background(), nil, model.Agent{}, topic, "", ""); err == nil {
			t.Errorf("topic %q must be rejected", topic)
		}
	}
}

func TestDecomposeProblemFallsBackWhenNoRunnerOrAgent(t *testing.T) {
	res, err := DecomposeProblem(context.Background(), nil, model.Agent{}, "Pick a database", "", "")
	if err != nil || len(res.PerspectiveA.Axes) < 3 || len(res.PerspectiveB.Axes) < 3 {
		t.Fatalf("expected rich heuristic fallback, got %+v, %v", res, err)
	}
	m := &mockRunner{}
	res, err = DecomposeProblem(context.Background(), m, model.Agent{}, "Pick a database", "", "")
	if err != nil || res == nil {
		t.Fatalf("agent without provider must fall back: %v", err)
	}
}

func TestDecomposeProblemFallsBackOnRunnerErrorAndGarbage(t *testing.T) {
	agent := model.NewAgent(model.ProviderAnthropic, "m")
	for name, m := range map[string]*mockRunner{
		"runner error": {err: errors.New("down")},
		"garbage":      {reply: runner.AgentReply{Content: "I refuse."}},
		"no axes":      {reply: runner.AgentReply{Content: `{"perspectiveA":{"axes":[]},"perspectiveB":{"axes":[]}}`}},
	} {
		res, err := DecomposeProblem(context.Background(), m, agent, "Topic", "", "")
		if err != nil || res == nil || len(res.PerspectiveA.Axes) == 0 || len(res.PerspectiveB.Axes) == 0 {
			t.Errorf("%s: want usable heuristic fallback, got %+v, %v", name, res, err)
		}
	}
}

func TestParseDecompositionJSON(t *testing.T) {
	body := `{"perspectiveA":{"id":"a","axes":[{"id":"x","title":"t","weight":0}]},"perspectiveB":{"id":"b","axes":[{"id":"y","title":"u","weight":0.4}]}}`
	for name, in := range map[string]string{
		"bare":   body,
		"fenced": "```json\n" + body + "\n```",
		"prose":  "Sure! Here you go:\n" + body + "\nLet me know.",
	} {
		r, err := parseDecompositionJSON(in, "fallback topic")
		if err != nil {
			t.Errorf("%s: %v", name, err)
			continue
		}
		if r.Topic != "fallback topic" {
			t.Errorf("%s: empty topic must take the fallback, got %q", name, r.Topic)
		}
		if a := r.PerspectiveA.Axes[0]; !a.Selected || a.Weight != 0.8 {
			t.Errorf("%s: zero weight must default to 0.8 and axes be selected: %+v", name, a)
		}
		if b := r.PerspectiveB.Axes[0]; b.Weight != 0.4 {
			t.Errorf("%s: explicit weight must be kept: %+v", name, b)
		}
	}
	if _, err := parseDecompositionJSON(`{"perspectiveA":{"axes":[{"id":"x"}]},"perspectiveB":{"axes":[]}}`, "t"); err == nil {
		t.Error("one empty perspective must be an error")
	}
}

// "go" and "rust" as substrings match almost any text ("algorithm", "good", "trust"); only
// the standalone words should trigger the language-specific axis titles.
func TestHeuristicLanguageBranchNeedsWholeWords(t *testing.T) {
	plain := GenerateHeuristicDecomposition("Is it a good algorithm to trust our vendor?", "going forward")
	if strings.Contains(plain.PerspectiveA.Axes[0].Title, "Memory Safety") {
		t.Errorf("unrelated topic got language-specific titles: %q", plain.PerspectiveA.Axes[0].Title)
	}
	for _, topic := range []string{"Rust vs Go for the gateway", "Should we use Golang?", "Rewrite the service"} {
		if got := GenerateHeuristicDecomposition(topic, "").PerspectiveA.Axes[0].Title; !strings.Contains(got, "Memory Safety") {
			t.Errorf("%q should get language titles, got %q", topic, got)
		}
	}
	db := GenerateHeuristicDecomposition("Migrate MongoDB to Postgres", "")
	if !strings.Contains(db.PerspectiveA.Axes[0].Title, "ACID") {
		t.Errorf("database topic: %q", db.PerspectiveA.Axes[0].Title)
	}
}
