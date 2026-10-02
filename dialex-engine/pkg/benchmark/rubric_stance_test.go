package benchmark

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

type scriptedJudge struct {
	byTopic map[string]string // substring of topic -> reply
	err     error
}

func (s *scriptedJudge) Respond(ctx context.Context, agent model.Agent, topic, commonContext, commonInstructions string, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	if s.err != nil {
		return runner.AgentReply{}, s.err
	}
	for k, v := range s.byTopic {
		if strings.Contains(topic, k) {
			return runner.AgentReply{Content: v}, nil
		}
	}
	return runner.AgentReply{Content: "draft"}, nil
}

func TestRubricScoresTrapsInverted(t *testing.T) {
	c := BenchmarkCase{GroundTruthTraps: []string{"trap1", "trap2"}, RequiredTradeOffAxes: []string{"axis1"}, MandatoryBoundaryConditions: []string{"edge1"}}
	items := BuildRubric(c)
	if len(items) != 4 || items[0].Kind != "TRAP" || items[2].Kind != "AXIS" || items[3].Kind != "BOUNDARY" {
		t.Fatalf("bad rubric: %+v", items)
	}
	// avoids both traps (no, no), covers the axis and the edge (yes, yes) => perfect
	if got, ok := scoreRubric(items, map[int]bool{1: false, 2: false, 3: true, 4: true}); !ok || got != 1 {
		t.Fatalf("perfect = %v %v", got, ok)
	}
	// commits one trap, misses the edge => 2 of 4
	if got, _ := scoreRubric(items, map[int]bool{1: true, 2: false, 3: true, 4: false}); got != 0.5 {
		t.Fatalf("got %v want 0.5", got)
	}
	if _, ok := scoreRubric(items, map[int]bool{1: false}); ok {
		t.Fatal("an incomplete answer must be unusable, not scored")
	}
}

func TestRubricIsUnavailableNotGuessedWhenJudgeFails(t *testing.T) {
	c := *GetBundledCaseByID("DB01")
	good := make([]string, 0)
	for i, it := range BuildRubric(c) {
		good = append(good, fmt.Sprintf(`{"id":%d,"yes":%v}`, i+1, it.Kind != "TRAP"))
	}
	ok := EvaluateRubric(context.Background(), &scriptedJudge{byTopic: map[string]string{"Rubric": `{"items":[` + strings.Join(good, ",") + `]}`}}, model.Agent{}, c, "an answer")
	if !ok.Available || ok.Score != 1 {
		t.Fatalf("clean answer should score 1, got %+v", ok)
	}
	for name, j := range map[string]*scriptedJudge{
		"garbage": {byTopic: map[string]string{"Rubric": "I think it is fine"}},
		"error":   {err: fmt.Errorf("rate limited")},
	} {
		if r := EvaluateRubric(context.Background(), j, model.Agent{}, c, "x"); r.Available {
			t.Errorf("%s: must be unavailable, got %+v", name, r)
		}
	}
}

func TestStanceMetricsFromLabels(t *testing.T) {
	// 4 seats: round 1 split two/two; by the end three agree after seat 3 switched
	m := stanceFromLabels([]string{"postgres", "postgres", "sqlite", "sqlite"}, []string{"postgres", "postgres", "postgres", "sqlite"})
	if m.PositionChanges != 1 || m.ChangedToMajority != 1 {
		t.Fatalf("changes=%d toMajority=%d", m.PositionChanges, m.ChangedToMajority)
	}
	if m.AgreementFirst >= m.AgreementFinal {
		t.Fatalf("agreement should rise: %v -> %v", m.AgreementFirst, m.AgreementFinal)
	}
	// nobody moved
	if m2 := stanceFromLabels([]string{"a", "b"}, []string{"a", "b"}); m2.PositionChanges != 0 || m2.AgreementFinal != 0 {
		t.Fatalf("%+v", m2)
	}
}

func TestAnalyzeStanceUsesAnonymousSeatsAndRejectsBadReplies(t *testing.T) {
	c := *GetBundledCaseByID("DB01")
	tr := []model.DebateMessage{
		{SeatID: "a", Round: 1, Content: "use postgres"}, {SeatID: "b", Round: 1, Content: "use sqlite"},
		{SeatID: "a", Round: 2, Content: "postgres"}, {SeatID: "b", Round: 2, Content: "ok postgres"},
	}
	var sawPrompt string
	judge := &promptCapture{reply: `{"round1":["Postgres","SQLite"],"final":["Postgres.","postgres"]}`, saw: &sawPrompt}
	m := AnalyzeStance(context.Background(), judge, model.Agent{}, c, tr, 2)
	if !m.Available || m.AgreementFirst != 0 || m.AgreementFinal != 1 || m.ChangedToMajority != 1 {
		t.Fatalf("unexpected metrics: %+v", m)
	}
	if strings.Contains(sawPrompt, "Skeptic") || !strings.Contains(sawPrompt, "S1:") {
		t.Fatalf("seats must be shown as S1..Sn: %s", sawPrompt)
	}
	bad := AnalyzeStance(context.Background(), &promptCapture{reply: `{"round1":["x"],"final":["y"]}`, saw: &sawPrompt}, model.Agent{}, c, tr, 2)
	if bad.Available {
		t.Fatal("a reply with the wrong number of seats must be unusable")
	}
}

type promptCapture struct {
	reply string
	saw   *string
}

func (p *promptCapture) Respond(ctx context.Context, agent model.Agent, topic, commonContext, commonInstructions string, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	*p.saw = commonContext
	return runner.AgentReply{Content: p.reply}, nil
}

func TestRunRecordsRubricStanceAndLeaksEndToEnd(t *testing.T) {
	c := *GetBundledCaseByID("DB01")
	var rub []string
	for i, it := range BuildRubric(c) {
		rub = append(rub, fmt.Sprintf(`{"id":%d,"yes":%v}`, i+1, it.Kind != "TRAP"))
	}
	judge := &scriptedJudge{byTopic: map[string]string{
		"Rubric":         `{"items":[` + strings.Join(rub, ",") + `]}`,
		"Stance":         `{"round1":["a","b"],"final":["a","a"]}`,
		"Double-Blind":   "not json",
		"Council debate": "x",
	}}
	r := NewRunner(func(a model.Agent) runner.AgentRunner {
		if strings.Contains(a.DisplayName, "Judge") {
			return judge
		}
		return &scriptedJudge{byTopic: map[string]string{}} // arms reply "draft"
	})
	agents := []model.Agent{{DisplayName: "Skeptic", Provider: model.ProviderAnthropic}, {DisplayName: "Optimist", Provider: model.ProviderAnthropic}}
	run, err := r.ExecuteRunWithBaseline(context.Background(), c, BaselineSelfConsistency, model.Agent{DisplayName: "S", Provider: model.ProviderAnthropic},
		agents, model.Agent{DisplayName: "Judge", Provider: model.ProviderGrok}, 2, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !run.RubricBaseline.Available || !run.RubricCouncil.Available || run.RubricCouncil.Score != 1 {
		t.Fatalf("rubric not recorded: %+v %+v", run.RubricBaseline, run.RubricCouncil)
	}
	st := run.CouncilResult.Stance
	if st == nil || !st.Available || st.AgreementFinal != 1 || st.ChangedToMajority != 1 {
		t.Fatalf("stance not recorded: %+v", st)
	}
	if len(run.CouncilResult.Transcript) != 4 {
		t.Fatalf("council transcript should be kept for spot-checks, got %d turns", len(run.CouncilResult.Transcript))
	}
}

func TestGroupRunsAggregatesRubricStanceAndLeaks(t *testing.T) {
	mk := func(rb, rc float64, avail bool, agree1, agree2 float64, moved int) BenchmarkRun {
		return BenchmarkRun{Baseline: BaselineSelfConsistency, Independence: "blind+anon",
			RubricBaseline: RubricResult{Available: avail, Score: rb}, RubricCouncil: RubricResult{Available: avail, Score: rc},
			CouncilResult: ArmResult{IdentityLeaks: 2, Stance: &StanceMetrics{Available: true, AgreementFirst: agree1, AgreementFinal: agree2, ChangedToMajority: moved}}}
	}
	g := GroupRuns([]BenchmarkRun{mk(0.5, 0.75, true, 0.2, 0.6, 1), mk(0.5, 0.25, true, 0.4, 1, 3), mk(0, 0, false, 0, 0, 0)})
	if len(g) != 1 {
		t.Fatalf("groups = %d", len(g))
	}
	x := g[0]
	if x.RubricRuns != 2 || x.MeanRubricDelta != 0 { // (+0.25 and -0.25), the unavailable run is ignored
		t.Fatalf("rubric aggregation wrong: %+v", x)
	}
	if x.StanceRuns != 3 || x.MeanIdentityLeaks != 2 {
		t.Fatalf("stance/leak aggregation wrong: %+v", x)
	}
	if md := CompareMarkdown(g); !strings.Contains(md, "checklist Δ") || !strings.Contains(md, "capitulation proxy") {
		t.Fatalf("markdown missing new columns or caveat:\n%s", md)
	}
}
