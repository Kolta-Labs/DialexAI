package benchmark

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

type mockAgentRunner struct {
	response string
}

func (m *mockAgentRunner) Respond(
	ctx context.Context,
	agent model.Agent,
	topic string,
	commonContext string,
	commonInstructions string,
	transcript []model.DebateMessage,
	modelOverride string,
) (runner.AgentReply, error) {
	// If judging, check which submission has the superior RocksDB deliverable
	if strings.Contains(topic, "Double-Blind") || strings.Contains(agent.Label(), "Judge") {
		// Detect whether Submission A or B has RocksDB
		subAHasRocksDB := false
		idxA := strings.Index(commonContext, "SUBMISSION A:")
		idxB := strings.Index(commonContext, "SUBMISSION B:")
		if idxA != -1 && idxB != -1 {
			subAText := commonContext[idxA:idxB]
			if strings.Contains(subAText, "RocksDB") {
				subAHasRocksDB = true
			}
		}

		scoreA, scoreB := 6.5, 9.2
		if subAHasRocksDB {
			scoreA, scoreB = 9.2, 6.5
		}

		jsonContent := fmt.Sprintf(`{
			"submission_a": {
				"factuality": %f,
				"blind_spots": %f,
				"trade_offs": %f,
				"actionability": %f,
				"critique": "Analysis of A"
			},
			"submission_b": {
				"factuality": %f,
				"blind_spots": %f,
				"trade_offs": %f,
				"actionability": %f,
				"critique": "Analysis of B"
			},
			"overall_verdict": "Superior submission evaluated.",
			"detailed_critique": "Detailed critique."
		}`, scoreA, scoreA, scoreA, scoreA, scoreB, scoreB, scoreB, scoreB)

		return runner.AgentReply{
			Content: jsonContent,
		}, nil
	}

	// For Solo / Council arms
	return runner.AgentReply{
		Content: m.response,
	}, nil
}

func TestExecuteRun(t *testing.T) {
	bCase := *GetBundledCaseByID("DB01")

	runnerFactory := func(agent model.Agent) runner.AgentRunner {
		if strings.Contains(agent.Label(), "Council") || agent.Role == "Moderator" {
			return &mockAgentRunner{response: "Council Deliverable: SQLite WAL is single-writer. RocksDB provides concurrent write pipelines."}
		}
		if strings.Contains(agent.Label(), "Judge") {
			return &mockAgentRunner{response: ""}
		}
		return &mockAgentRunner{response: "Solo Deliverable: SQLite WAL allows multiple concurrent writers."}
	}

	r := NewRunner(runnerFactory)

	soloAgent := model.Agent{
		DisplayName: "Solo Claude",
		Provider:    model.ProviderAnthropic,
		Model:       "claude-3-7-sonnet",
		RunMode:     model.RunModeAPI,
	}

	councilAgents := []model.Agent{
		{DisplayName: "Moderator Claude", Role: "Moderator", Provider: model.ProviderAnthropic, Model: "claude-3-7-sonnet", RunMode: model.RunModeAPI},
		{DisplayName: "Council Pragmatist", Role: "Pragmatist", Provider: model.ProviderOpenAI, Model: "gpt-4o", RunMode: model.RunModeAPI},
	}

	judgeAgent := model.Agent{
		DisplayName: "Judge Frontier",
		Provider:    model.ProviderGrok,
		Model:       "grok-3",
		RunMode:     model.RunModeAPI,
	}

	progressEvents := 0
	run, err := r.ExecuteRun(
		context.Background(),
		bCase,
		soloAgent,
		councilAgents,
		judgeAgent,
		1,
		func(phase string, progress float64) {
			progressEvents++
		},
	)

	if err != nil {
		t.Fatalf("expected successful run, got %v", err)
	}

	if run.CaseID != "DB01" {
		t.Fatalf("expected CaseID DB01, got %s", run.CaseID)
	}
	if len(run.Evaluations) != 2 {
		t.Fatalf("expected 2 evaluations (order swap), got %d", len(run.Evaluations))
	}
	if run.DeltaQ <= 0 {
		t.Fatalf("expected positive DeltaQ, got %f", run.DeltaQ)
	}
	if run.Winner != "COUNCIL" {
		t.Fatalf("expected Winner COUNCIL, got %s", run.Winner)
	}
	if progressEvents < 2 {
		t.Fatalf("expected at least 2 progress events, got %d", progressEvents)
	}
}

func TestStoreOperations(t *testing.T) {
	st := NewMemoryStore()

	// Initial bundled cases
	cases := st.ListCases()
	if len(cases) != 10 {
		t.Fatalf("expected 10 bundled cases, got %d", len(cases))
	}

	// Add custom case
	custom := BenchmarkCase{
		ID:      "CUSTOM01",
		Title:   "Custom Microservice Sharding Dilemma",
		Domain:  "CUSTOM",
		Dilemma: "Testing custom dilemma injection",
	}
	if err := st.AddCustomCase(custom); err != nil {
		t.Fatalf("failed to add custom case: %v", err)
	}

	if len(st.ListCases()) != 11 {
		t.Fatalf("expected 11 cases, got %d", len(st.ListCases()))
	}

	retrieved := st.GetCase("CUSTOM01")
	if retrieved == nil || retrieved.Title != custom.Title {
		t.Fatalf("failed to retrieve custom case")
	}

	// Save and list runs
	run := BenchmarkRun{
		ID:                "test-run-1",
		CaseID:            "DB01",
		Winner:            "COUNCIL",
		SoloTotalScore:    6.0,
		CouncilTotalScore: 8.8,
	}
	if err := st.SaveRun(run); err != nil {
		t.Fatalf("failed to save run: %v", err)
	}

	runs := st.ListRuns()
	if len(runs) != 1 {
		t.Fatalf("expected 1 run, got %d", len(runs))
	}

	summary := st.GetSummary()
	if summary.TotalRuns != 1 || summary.CouncilWins != 1 {
		t.Fatalf("unexpected summary: %+v", summary)
	}
}

type countingRunner struct {
	mu    *sync.Mutex
	calls *int
	err   error
}

func (c *countingRunner) Respond(ctx context.Context, agent model.Agent, topic, commonContext, commonInstructions string, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	if strings.Contains(topic, "Double-Blind") {
		return runner.AgentReply{}, c.err // nil err + empty content => unparsable judge reply
	}
	c.mu.Lock()
	*c.calls++
	c.mu.Unlock()
	return runner.AgentReply{Content: "draft"}, nil
}

func TestSelfConsistencyArmMatchesCouncilCallCount(t *testing.T) {
	var mu sync.Mutex
	var calls int
	r := NewRunner(func(model.Agent) runner.AgentRunner { return &countingRunner{mu: &mu, calls: &calls} })
	agents := []model.Agent{{DisplayName: "A"}, {DisplayName: "B"}}
	const rounds = 2

	arm, err := r.runSelfConsistencyArm(context.Background(), *GetBundledCaseByID("DB01"), model.Agent{DisplayName: "S"}, rounds*len(agents))
	if err != nil {
		t.Fatal(err)
	}
	if calls != rounds*len(agents)+1 {
		t.Fatalf("baseline calls = %d, want draws+1 = %d", calls, rounds*len(agents)+1)
	}
	if arm.ArmType != ArmSelfConsistency || arm.TokensUsed == 0 {
		t.Fatalf("bad arm result: %+v", arm)
	}

	// council makes rounds*agents turns + 1 synthesis: same call count
	calls = 0
	if _, err := r.runCouncilArm(context.Background(), *GetBundledCaseByID("DB01"), agents, rounds); err != nil {
		t.Fatal(err)
	}
	if calls != rounds*len(agents)+1 {
		t.Fatalf("council calls = %d, want %d", calls, rounds*len(agents)+1)
	}
}

func TestRunRecordsBaselineAndJudgeFallback(t *testing.T) {
	var mu sync.Mutex
	var calls int
	r := NewRunner(func(model.Agent) runner.AgentRunner { return &countingRunner{mu: &mu, calls: &calls} })
	agents := []model.Agent{{DisplayName: "A", Provider: model.ProviderOpenAI}}
	run, err := r.ExecuteRunWithBaseline(context.Background(), *GetBundledCaseByID("DB01"), BaselineSelfConsistency,
		model.Agent{DisplayName: "S", Provider: model.ProviderAnthropic}, agents, model.Agent{DisplayName: "Judge", Provider: model.ProviderGrok}, 1, nil)
	if err != nil {
		t.Fatal(err)
	}
	if run.Baseline != BaselineSelfConsistency || run.SoloResult.ArmType != ArmSelfConsistency {
		t.Fatalf("baseline not recorded: %+v", run)
	}
	if run.JudgeFallbackPasses != 2 || !run.Evaluations[0].Fallback || !run.Evaluations[1].Fallback {
		t.Fatalf("both passes should be flagged as heuristic fallback, got %d", run.JudgeFallbackPasses)
	}
	if run.TokenRatio <= 0 {
		t.Fatalf("token ratio missing: %v", run.TokenRatio)
	}
	if _, err := r.ExecuteRunWithBaseline(context.Background(), *GetBundledCaseByID("DB01"), "nope",
		model.Agent{}, agents, model.Agent{}, 1, nil); err == nil {
		t.Fatal("unknown baseline must error")
	}
}

func TestJudgeMustBeIndependentOfArms(t *testing.T) {
	var mu sync.Mutex
	var calls int
	r := NewRunner(func(model.Agent) runner.AgentRunner { return &countingRunner{mu: &mu, calls: &calls} })
	solo := model.Agent{DisplayName: "S", Provider: model.ProviderAnthropic}
	council := []model.Agent{{DisplayName: "C", Provider: model.ProviderOpenAI}}
	judge := model.Agent{DisplayName: "J", Provider: model.ProviderOpenAI}
	c := *GetBundledCaseByID("DB01")

	_, err := r.ExecuteRun(context.Background(), c, solo, council, judge, 1, nil)
	if !errors.Is(err, ErrJudgeNotIndependent) || calls != 0 {
		t.Fatalf("overlap must be refused before any model call; err=%v calls=%d", err, calls)
	}

	r.AllowJudgeOverlap = true
	run, err := r.ExecuteRun(context.Background(), c, solo, council, judge, 1, nil)
	if err != nil || !run.JudgeOverlap {
		t.Fatalf("allowed overlap must run and be flagged: err=%v run=%+v", err, run)
	}
}

func TestOllamaFamiliesCompareByModel(t *testing.T) {
	a := model.Agent{Provider: model.ProviderOllama, Model: "llama3"}
	if JudgeConflict(model.Agent{Provider: model.ProviderOllama, Model: "qwen"}, a) != nil {
		t.Fatal("different ollama models are different families")
	}
	if JudgeConflict(model.Agent{Provider: model.ProviderOllama, Model: "llama3"}, a) == nil {
		t.Fatal("same ollama model must conflict")
	}
}

func TestPickIndependentJudge(t *testing.T) {
	arms := []model.Agent{{Provider: model.ProviderAnthropic}, {Provider: model.ProviderOpenAI}, {Provider: model.ProviderGrok}}
	j, ok := PickIndependentJudge(arms, func(p model.Provider) bool { return p == model.ProviderGemini })
	if !ok || j.Provider != model.ProviderGemini {
		t.Fatalf("should prefer an independent provider with a key, got %v %v", j.Provider, ok)
	}
	j, ok = PickIndependentJudge(arms, nil)
	if !ok || j.Provider != model.ProviderMistral {
		t.Fatalf("without keys, first independent candidate expected, got %v", j.Provider)
	}
	all := []model.Agent{}
	for _, c := range judgeCandidates {
		all = append(all, model.Agent{Provider: c.Provider})
	}
	if _, ok := PickIndependentJudge(all, nil); ok {
		t.Fatal("no independent judge should exist when every family is an arm")
	}
}
