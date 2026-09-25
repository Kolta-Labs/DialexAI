package benchmark

import (
	"context"
	"fmt"
	"strings"
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
		Provider:    model.ProviderOpenAI,
		Model:       "gpt-4o",
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
