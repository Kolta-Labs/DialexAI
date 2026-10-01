package orchestrator

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

func seat(id, name string) model.Agent {
	a := model.NewAgent(model.ProviderAnthropic, "m") // all three share ONE provider
	a.ID, a.DisplayName = id, name
	return a
}

func msg(s model.Agent, round int, text string) model.DebateMessage {
	return model.DebateMessage{SeatID: s.ID, AgentID: s.Provider, Provider: s.Provider, AuthorDisplayName: s.Label(), Round: round, Content: text}
}

func TestBlindFirstRoundHidesOnlyPeersRoundOneAnswers(t *testing.T) {
	a, b, c := seat("a", "Skeptic"), seat("b", "Optimist"), seat("c", "Pragmatist")
	seats := []model.Agent{a, b, c}
	views := []model.DebateMessage{
		msg(a, 1, "A1"), msg(b, 1, "B1"),
		{SeatID: "system", IsSystem: true, Round: 1, Content: "note"},
		{SeatID: "mod", IsModeratorIntervention: true, Round: 1, Content: "steer"},
		msg(a, 2, "A2"),
	}
	cfg := &model.IndependenceConfig{BlindFirstRound: true}

	got := applyIndependence(views, c, 1, cfg, seats)
	var texts []string
	for _, m := range got {
		texts = append(texts, m.Content)
	}
	if strings.Join(texts, ",") != "note,steer,A2" {
		t.Fatalf("round 1, seat c should see only system/moderator notes (and later rounds), got %v", texts)
	}
	own := applyIndependence(views, a, 1, cfg, seats)
	if own[0].Content != "A1" {
		t.Fatalf("a seat keeps its own round-1 answer, got %+v", own[0])
	}
	if len(applyIndependence(views, c, 2, cfg, seats)) != len(views) {
		t.Fatal("from round 2 on nothing is hidden")
	}
}

func TestAnonymizeUsesStableSeatLetters(t *testing.T) {
	a, b, c := seat("a", "Skeptic"), seat("b", "Optimist"), seat("c", "Pragmatist")
	views := []model.DebateMessage{msg(a, 1, "x"), msg(b, 1, "y"), msg(c, 1, "z")}
	got := applyIndependence(views, c, 2, &model.IndependenceConfig{AnonymizeTranscript: true}, []model.Agent{a, b, c})
	want := []string{"Participant A", "Participant B", "Pragmatist"}
	for i, m := range got {
		if m.AuthorDisplayName != want[i] {
			t.Errorf("line %d labelled %q, want %q", i, m.AuthorDisplayName, want[i])
		}
	}
	if views[0].AuthorDisplayName != "Skeptic" {
		t.Fatal("the persisted transcript must not be mutated")
	}
}

func TestNoConfigChangesNothing(t *testing.T) {
	a := seat("a", "A")
	views := []model.DebateMessage{msg(a, 1, "x")}
	if got := applyIndependence(views, seat("b", "B"), 1, nil, []model.Agent{a}); len(got) != 1 || got[0].AuthorDisplayName != "A" {
		t.Fatalf("got %+v", got)
	}
}

// End to end: three personas on ONE provider, blind round 1 + anonymized peers. Seats must be
// told apart (own turns only are "mine"), round 1 must be independent, later rounds anonymous.
func TestSingleProviderCouncilRunsBlindAndAnonymous(t *testing.T) {
	var mu sync.Mutex
	seen := map[string][][]model.DebateMessage{} // seat name -> views it was given, per call
	fake := &fakeRunner{respond: func(agent model.Agent, tr []model.DebateMessage, _ string) (runner.AgentReply, error) {
		mu.Lock()
		defer mu.Unlock()
		seen[agent.Label()] = append(seen[agent.Label()], append([]model.DebateMessage(nil), tr...))
		return runner.AgentReply{Content: fmt.Sprintf("%s says %d", agent.Label(), len(seen[agent.Label()]))}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return fake }}
	a, b, c := seat("a", "Skeptic"), seat("b", "Optimist"), seat("c", "Pragmatist")
	cfg := model.DebateConfig{
		Topic: "t", Primary: a, Secondary: &b, Tertiary: &c,
		RoundMode: model.RoundModeFixed, MaxRounds: 2,
		Independence: &model.IndependenceConfig{BlindFirstRound: true, AnonymizeTranscript: true},
	}
	if _, err := o.Run(context.Background(), RunOptions{Config: cfg}); err != nil {
		t.Fatal(err)
	}

	for _, name := range []string{"Skeptic", "Optimist", "Pragmatist"} {
		first := seen[name][0] // round 1 view
		for _, m := range first {
			if m.Round == 1 && m.AuthorDisplayName != name && m.SeatID != "system" {
				t.Errorf("%s saw another seat's round-1 turn %q: not blind", name, m.Content)
			}
		}
	}
	// Round 2: the Pragmatist sees A and B anonymized, not by persona name.
	second := seen["Pragmatist"][1]
	var labels []string
	for _, m := range second {
		labels = append(labels, m.AuthorDisplayName)
	}
	joined := strings.Join(labels, "|")
	if strings.Contains(joined, "Skeptic") || strings.Contains(joined, "Optimist") || !strings.Contains(joined, "Participant A") {
		t.Errorf("round-2 view not anonymized: %s", joined)
	}
}
