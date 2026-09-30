package consensus

import (
	"context"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

func TestHeuristicTensionExtraction(t *testing.T) {
	detector := NewTensionDetector()

	topic := "Should we adopt Rust or Go for our high-frequency trading gateway?"
	round1Turns := []model.DebateMessage{
		{
			SeatID:            "seat_claude",
			AgentID:           model.ProviderAnthropic,
			AuthorDisplayName: "Claude (Systems)",
			Round:             1,
			Content:           "We must choose Rust. Zero-cost abstractions and deterministic memory management without GC pauses are non-negotiable for sub-millisecond p99 latency.",
		},
		{
			SeatID:            "seat_gemini",
			AgentID:           model.ProviderGemini,
			AuthorDisplayName: "Gemini (Pragmatist)",
			Round:             1,
			Content:           "However, I strongly disagree. Rust's steep learning curve and borrow checker friction will crater team velocity. Go delivers acceptable throughput with 10x faster shipping speed.",
		},
	}

	tensions := detector.HeuristicAnalyzeRound(topic, 1, round1Turns, nil)
	if len(tensions) == 0 {
		t.Fatalf("expected at least 1 heuristic tension pair extracted, got 0")
	}

	tension := tensions[0]
	if tension.Status != model.TensionStatusOpen {
		t.Errorf("expected status OPEN, got %s", tension.Status)
	}
	if tension.Thesis.AuthorDisplayName != "Claude (Systems)" {
		t.Errorf("expected thesis author Claude, got %s", tension.Thesis.AuthorDisplayName)
	}
	if tension.Antithesis.AuthorDisplayName != "Gemini (Pragmatist)" {
		t.Errorf("expected antithesis author Gemini, got %s", tension.Antithesis.AuthorDisplayName)
	}
	if tension.DetectedInRound != 1 {
		t.Errorf("expected detectedInRound 1, got %d", tension.DetectedInRound)
	}
}

func TestHeuristicTensionResolution(t *testing.T) {
	detector := NewTensionDetector()

	existing := []model.TensionPair{
		{
			ID:                 "tension_test_1",
			UnderlyingConflict: "Low-Latency Performance vs. Architectural Durability",
			Status:             model.TensionStatusOpen,
			DetectedInRound:    1,
		},
	}

	round2Turns := []model.DebateMessage{
		{
			SeatID:            "seat_claude",
			AgentID:           model.ProviderAnthropic,
			AuthorDisplayName: "Claude",
			Round:             2,
			Content:           "I agree with Gemini's point on shipping velocity. We can synthesiz a hybrid approach: Go for the API orchestration layer, and Rust FFI for the core order-matching ring buffer.",
		},
		{
			SeatID:            "seat_gemini",
			AgentID:           model.ProviderGemini,
			AuthorDisplayName: "Gemini",
			Round:             2,
			Content:           "Agreed, this compromise satisfies both requirements completely.",
		},
	}

	updated := detector.HeuristicAnalyzeRound("Architecture", 2, round2Turns, existing)
	if len(updated) != 1 {
		t.Fatalf("expected 1 tension pair, got %d", len(updated))
	}
	if updated[0].Status != model.TensionStatusResolved {
		t.Errorf("expected status RESOLVED, got %s", updated[0].Status)
	}
	if updated[0].Synthesis == nil || *updated[0].Synthesis == "" {
		t.Errorf("expected non-empty synthesis note")
	}
	if updated[0].ResolvedInRound == nil || *updated[0].ResolvedInRound != 2 {
		t.Errorf("expected resolvedInRound 2, got %v", updated[0].ResolvedInRound)
	}
}

type mockRunner struct {
	reply runner.AgentReply
	err   error
}

func (m *mockRunner) Respond(
	ctx context.Context,
	agent model.Agent,
	topic string,
	commonContext string,
	commonInstructions string,
	transcript []model.DebateMessage,
	modelOverride string,
) (runner.AgentReply, error) {
	return m.reply, m.err
}

func TestAnalyzeRound_WithMockRunner(t *testing.T) {
	detector := NewTensionDetector()

	mockJSON := `{
  "newTensions": [
    {
      "thesis": {
        "seatId": "seat_1",
        "provider": "ANTHROPIC",
        "authorDisplayName": "Claude (Architect)",
        "statement": "Zero data loss requires synchronous raft replication on every write.",
        "quote": "...must commit to quorum before acking...",
        "round": 1
      },
      "antithesis": {
        "seatId": "seat_2",
        "provider": "OPENAI",
        "authorDisplayName": "ChatGPT (SRE)",
        "statement": "Synchronous quorum violates the 5ms p99 latency SLA.",
        "quote": "...p99 SLA cannot tolerate cross-datacenter roundtrips...",
        "round": 1
      },
      "underlyingConflict": "Immediate Consistency vs. Low-Latency SLA",
      "severity": 0.85
    }
  ],
  "resolvedTensionUpdates": []
}`

	mock := &mockRunner{
		reply: runner.AgentReply{
			Content: "```json\n" + mockJSON + "\n```",
		},
	}

	agent := model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5")
	roundMessages := []model.DebateMessage{
		{AgentID: model.ProviderAnthropic, Content: "Thesis statement", Round: 1},
		{AgentID: model.ProviderOpenAI, Content: "Antithesis statement", Round: 1},
	}

	tensions, err := detector.AnalyzeRound(context.Background(), mock, agent, "Data storage replication", 1, roundMessages, nil, "")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(tensions) != 1 {
		t.Fatalf("expected 1 tension pair, got %d", len(tensions))
	}

	t0 := tensions[0]
	if t0.UnderlyingConflict != "Immediate Consistency vs. Low-Latency SLA" {
		t.Errorf("unexpected underlying conflict: %s", t0.UnderlyingConflict)
	}
	if t0.Status != model.TensionStatusOpen {
		t.Errorf("expected OPEN status, got %s", t0.Status)
	}
	if t0.Severity != 0.85 {
		t.Errorf("expected severity 0.85, got %f", t0.Severity)
	}
}

func TestModelTensionHelpers(t *testing.T) {
	tensions := []model.TensionPair{
		{ID: "t1", Status: model.TensionStatusOpen},
		{ID: "t2", Status: model.TensionStatusResolved},
		{ID: "t3", Status: model.TensionStatusAcceptedTradeOff},
	}

	if !model.HasOpenTensions(tensions) {
		t.Errorf("expected HasOpenTensions true")
	}
	if count := model.OpenTensionCount(tensions); count != 1 {
		t.Errorf("expected OpenTensionCount 1, got %d", count)
	}

	tensions[0].Status = model.TensionStatusResolved
	if model.HasOpenTensions(tensions) {
		t.Errorf("expected HasOpenTensions false when all resolved")
	}
}
