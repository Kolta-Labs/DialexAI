package orchestrator

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"socratix/pkg/graph"
	"socratix/pkg/model"
	"socratix/pkg/runner"
)

// fakeRunner echoes provider + transcript length, no network/process involved.
type fakeRunner struct {
	respond func(agent model.Agent, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error)
}

func (f *fakeRunner) Respond(ctx context.Context, agent model.Agent, topic, commonContext, commonInstructions string, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	if f.respond != nil {
		return f.respond(agent, transcript, modelOverride)
	}
	return runner.AgentReply{Content: fmt.Sprintf("%s-turn-%d", agent.Provider, len(transcript))}, nil
}

func claudeAgent() model.Agent { return model.NewAgent(model.ProviderAnthropic, "claude-x") }
func geminiAgent() model.Agent { return model.NewAgent(model.ProviderGemini, "gemini-x") }

func TestPrimarySpeaksFirstEveryRoundThenGivesFinalDecision(t *testing.T) {
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return &fakeRunner{} }}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 2}

	result, err := o.Run(context.Background(), RunOptions{Config: config})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// 2 agents * 2 rounds = 4 turns, the conclusion call isn't counted in the transcript.
	if len(result.Transcript) != 4 {
		t.Fatalf("len(Transcript) = %d, want 4", len(result.Transcript))
	}
	wantOrder := []model.Provider{model.ProviderAnthropic, model.ProviderGemini, model.ProviderAnthropic, model.ProviderGemini}
	for i, p := range wantOrder {
		if result.Transcript[i].AgentID != p {
			t.Errorf("Transcript[%d].AgentID = %v, want %v", i, result.Transcript[i].AgentID, p)
		}
	}
	if result.Conclusion == nil || *result.Conclusion != "ANTHROPIC-turn-4" {
		t.Errorf("Conclusion = %v, want ANTHROPIC-turn-4", result.Conclusion)
	}
}

func TestUnlimitedModePausesAndResumeContinues(t *testing.T) {
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return &fakeRunner{} }}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeUnlimited}

	turns := 0
	paused, err := o.Run(context.Background(), RunOptions{
		Config:    config,
		IsStopped: func() bool { return turns >= 4 },
		OnMessage: func(model.DebateMessage) { turns++ },
	})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if len(paused.Transcript) != 4 || !paused.Paused || paused.Conclusion != nil || paused.Error != nil {
		t.Fatalf("paused result = %+v, want 4 turns, Paused=true, no conclusion/error", paused)
	}

	resumed, err := o.Run(context.Background(), RunOptions{
		Config:            config,
		InitialTranscript: paused.Transcript,
		IsStopped:         func() bool { return turns >= 6 },
		OnMessage:         func(model.DebateMessage) { turns++ },
	})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if len(resumed.Transcript) != 6 || !resumed.Paused {
		t.Fatalf("resumed result = %+v, want 6 turns, Paused=true", resumed)
	}
	wantRounds := []int{1, 1, 2, 2, 3, 3}
	for i, want := range wantRounds {
		if resumed.Transcript[i].Round != want {
			t.Errorf("Transcript[%d].Round = %d, want %d", i, resumed.Transcript[i].Round, want)
		}
	}
}

func TestAFailedTurnEndsTheDebateImmediately(t *testing.T) {
	calls := 0
	failing := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		calls++
		if calls >= 3 { // persistent from call 3 onward — survives the one retry
			return runner.AgentReply{}, errors.New("CLI exploded")
		}
		return runner.AgentReply{Content: fmt.Sprintf("%s-turn-%d", agent.Provider, len(transcript))}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return failing }}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 5}

	result, err := o.Run(context.Background(), RunOptions{Config: config})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if len(result.Transcript) != 3 {
		t.Fatalf("len(Transcript) = %d, want 3", len(result.Transcript))
	}
	if result.Error == nil || *result.Error != "CLI exploded" {
		t.Errorf("Error = %v, want \"CLI exploded\"", result.Error)
	}
	if !result.Transcript[2].IsError {
		t.Error("last transcript entry should be marked IsError")
	}
	if result.Conclusion != nil {
		t.Error("Conclusion should be nil after a failed turn")
	}
}

func TestUnanimousAgreementEndsFixedDebateBeforeMaxRounds(t *testing.T) {
	agreeing := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		currentRound := len(transcript)/2 + 1
		if currentRound >= 2 {
			return runner.AgentReply{Content: "AGREED: makes sense"}, nil
		}
		return runner.AgentReply{Content: fmt.Sprintf("%s-disagrees", agent.Provider)}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return agreeing }}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 5}

	result, err := o.Run(context.Background(), RunOptions{Config: config})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	agentTurns := 0
	for _, m := range result.Transcript {
		if !m.IsSystem {
			agentTurns++
		}
	}
	// Round 1 (2 turns) + round 2 (2 turns, both AGREED) = 4 turns, well short of maxRounds=5.
	if agentTurns != 4 {
		t.Fatalf("agent turns = %d, want 4 (total transcript entries: %d)", agentTurns, len(result.Transcript))
	}
	if !result.IsConsensusReached {
		t.Errorf("IsConsensusReached = false, want true")
	}
	if result.Conclusion == nil || *result.Conclusion != "AGREED: makes sense" {
		t.Errorf("Conclusion = %v, want \"AGREED: makes sense\"", result.Conclusion)
	}
}

func TestHardStopCancellationPropagatesInsteadOfBeingRecordedAsATurnError(t *testing.T) {
	calls := 0
	cancelling := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		calls++
		if calls == 2 {
			return runner.AgentReply{}, context.Canceled
		}
		return runner.AgentReply{Content: fmt.Sprintf("%s-turn-%d", agent.Provider, len(transcript))}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return cancelling }}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 5}

	_, err := o.Run(context.Background(), RunOptions{Config: config})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("Run() error = %v, want context.Canceled to propagate", err)
	}
}

func TestLongDebatesGetCompactedButPersistedTranscriptStaysFull(t *testing.T) {
	var seenContextSizes []int
	recording := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		seenContextSizes = append(seenContextSizes, len(transcript))
		return runner.AgentReply{Content: fmt.Sprintf("%s-turn-%d", agent.Provider, len(transcript))}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return recording }}

	prior := make([]model.DebateMessage, 22)
	for i := range prior {
		p := model.ProviderAnthropic
		if (i+1)%2 == 0 {
			p = model.ProviderGemini
		}
		prior[i] = model.DebateMessage{AgentID: p, Round: i/2 + 1, Content: fmt.Sprintf("turn %d", i+1)}
	}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 13}

	result, err := o.Run(context.Background(), RunOptions{Config: config, InitialTranscript: prior})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if len(result.Transcript) != 26 {
		t.Fatalf("len(Transcript) = %d, want 26 (persisted transcript untouched by compaction)", len(result.Transcript))
	}
	if result.Transcript[0].Content != "turn 1" {
		t.Errorf("Transcript[0].Content = %q, want %q", result.Transcript[0].Content, "turn 1")
	}
	if len(seenContextSizes) == 0 || seenContextSizes[0] != 14 {
		t.Fatalf("seenContextSizes[0] = %v, want 14 (summarizing turns 1..14)", seenContextSizes)
	}
	for _, size := range seenContextSizes[1:] {
		if size >= 14 {
			t.Errorf("post-compaction context size = %d, want < 14 every turn after the summary call", size)
		}
	}
}

func TestCompactionCallUsesConfiguredModelOverrideButNormalTurnsDont(t *testing.T) {
	var seenOverrides []string
	recording := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
		seenOverrides = append(seenOverrides, modelOverride)
		return runner.AgentReply{Content: fmt.Sprintf("%s-turn-%d", agent.Provider, len(transcript))}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return recording }}

	prior := make([]model.DebateMessage, 22)
	for i := range prior {
		p := model.ProviderAnthropic
		if (i+1)%2 == 0 {
			p = model.ProviderGemini
		}
		prior[i] = model.DebateMessage{AgentID: p, Round: i/2 + 1, Content: fmt.Sprintf("turn %d", i+1)}
	}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 13}

	_, err := o.Run(context.Background(), RunOptions{Config: config, InitialTranscript: prior, CompactionModel: "claude-haiku-4-5-20251001"})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if len(seenOverrides) == 0 || seenOverrides[0] != "claude-haiku-4-5-20251001" {
		t.Fatalf("seenOverrides[0] = %q, want the configured compaction model", seenOverrides)
	}
	for _, o := range seenOverrides[1:] {
		if o != "" {
			t.Errorf("normal turn saw modelOverride = %q, want empty", o)
		}
	}
}

func TestFiveWayDebateSpeaksInSeatOrderEveryRound(t *testing.T) {
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return &fakeRunner{} }}
	config := model.DebateConfig{
		Topic:      "t",
		Primary:    claudeAgent(),
		Secondary:  agentPtr(geminiAgent()),
		Tertiary:   agentPtr(model.NewAgent(model.ProviderOpenAI, "gpt-x")),
		Quaternary: agentPtr(model.NewAgent(model.ProviderGrok, "grok-x")),
		Quinary:    agentPtr(model.NewAgent(model.ProviderDeepSeek, "deepseek-x")),
		RoundMode:  model.RoundModeFixed,
		MaxRounds:  2,
	}

	result, err := o.Run(context.Background(), RunOptions{Config: config})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	order := []model.Provider{model.ProviderAnthropic, model.ProviderGemini, model.ProviderOpenAI, model.ProviderGrok, model.ProviderDeepSeek}
	if len(result.Transcript) != 10 {
		t.Fatalf("len(Transcript) = %d, want 10", len(result.Transcript))
	}
	for round := 0; round < 2; round++ {
		for i, p := range order {
			got := result.Transcript[round*5+i].AgentID
			if got != p {
				t.Errorf("round %d seat %d = %v, want %v", round+1, i, got, p)
			}
		}
	}
}

func TestTokenBudgetStopsTheDebate(t *testing.T) {
	tokensPerTurn := 60
	spending := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		in, out := tokensPerTurn, tokensPerTurn
		return runner.AgentReply{Content: "reply", TokensIn: &in, TokensOut: &out}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return spending }}
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 20}

	result, err := o.Run(context.Background(), RunOptions{Config: config, TokenBudget: 200})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	// 120 tokens/turn (60 in + 60 out) — stops once cumulative usage hits 200, i.e. after
	// the 2nd turn (240 >= 200), not the full 40 turns MaxRounds=20 would otherwise allow.
	if len(result.Transcript) != 2 {
		t.Fatalf("len(Transcript) = %d, want 2 (stopped by token budget)", len(result.Transcript))
	}
	if result.Error == nil {
		t.Fatal("Error = nil, want a token-budget message")
	}
}

func TestDynamicMidLoopCompaction(t *testing.T) {
	compactCalls := 0
	sawCompactedContext := false

	compactor := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		if agent.SystemPrompt == compactDirective {
			compactCalls++
			return runner.AgentReply{Content: "compacted-summary-mid-loop"}, nil
		}
		if len(transcript) > 0 && len(transcript[0].Content) > 0 {
			if transcript[0].Content == "[Summary of earlier discussion]\n\ncompacted-summary-mid-loop" {
				sawCompactedContext = true
			}
		}
		return runner.AgentReply{Content: fmt.Sprintf("reply-%d", len(transcript))}, nil
	}}

	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return compactor }}
	// 2 agents * 5 rounds = 10 turns, starting with empty transcript
	config := model.DebateConfig{Topic: "t", Primary: claudeAgent(), Secondary: agentPtr(geminiAgent()), RoundMode: model.RoundModeFixed, MaxRounds: 5}

	result, err := o.Run(context.Background(), RunOptions{
		Config:              config,
		CompactionThreshold: 4,
	})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	if compactCalls == 0 {
		t.Errorf("compactCalls = 0, want compaction to trigger dynamically mid-loop")
	}
	if !sawCompactedContext {
		t.Errorf("sawCompactedContext = false, want agents to see compacted context after threshold exceeded")
	}
	if len(result.Transcript) != 10 {
		t.Errorf("len(result.Transcript) = %d, want full 10 turns persisted", len(result.Transcript))
	}
}

func TestSharedMemoryExecution(t *testing.T) {
	sawBlackboard := false
	runnerWithSM := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		for _, m := range transcript {
			if len(m.Content) >= 27 && m.Content[:27] == "[SHARED MEMORY BLACKBOARD]\n" {
				sawBlackboard = true
				break
			}
		}
		return runner.AgentReply{Content: fmt.Sprintf("%s-turn", agent.Provider)}, nil
	}}

	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return runnerWithSM }}
	config := model.DebateConfig{
		Topic:     "t",
		Primary:   claudeAgent(),
		Secondary: agentPtr(geminiAgent()),
		RoundMode: model.RoundModeFixed,
		MaxRounds: 3,
		SharedMemory: &model.SharedMemoryConfig{
			Enabled:            true,
			IncludeFullRound1:  true,
			IncludeOwnLastTurn: true,
			MaxSummaryTokens:   500,
		},
	}

	result, err := o.Run(context.Background(), RunOptions{Config: config})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	if len(result.Transcript) != 6 {
		t.Fatalf("len(result.Transcript) = %d, want 6", len(result.Transcript))
	}
	if !sawBlackboard {
		t.Errorf("sawBlackboard = false, want agents to receive the Shared Memory Blackboard")
	}
}

func TestAttachmentDeduplication(t *testing.T) {
	files := []model.AttachedFile{
		{Name: "global.txt", Content: "Global Data", Scope: "all"},
		{Name: "specific.txt", Content: "Specific Data", Scope: "topic"},
	}

	topicPrompt := formatWithAttachments("Topic Base", files, "topic")
	contextPrompt := formatWithAttachments("Context Base", files, "common_context")
	infoPrompt := formatWithAttachments("Info Base", files, "common_info")

	// "all" scope must only appear in common_context, NOT in topic or common_info
	if !containsStr(contextPrompt, "global.txt") {
		t.Errorf("contextPrompt missing global.txt: %s", contextPrompt)
	}
	if containsStr(topicPrompt, "global.txt") {
		t.Errorf("topicPrompt should not contain global.txt: %s", topicPrompt)
	}
	if containsStr(infoPrompt, "global.txt") {
		t.Errorf("infoPrompt should not contain global.txt: %s", infoPrompt)
	}

	// "topic" scope must only appear in topic
	if !containsStr(topicPrompt, "specific.txt") {
		t.Errorf("topicPrompt missing specific.txt: %s", topicPrompt)
	}
	if containsStr(contextPrompt, "specific.txt") {
		t.Errorf("contextPrompt should not contain specific.txt: %s", contextPrompt)
	}
}

func containsStr(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func agentPtr(a model.Agent) *model.Agent { return &a }

func TestParaconsistentConsensusGatekeeper_BlocksSuperficialConsensus(t *testing.T) {
	fakeR := &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage, _ string) (runner.AgentReply, error) {
		return runner.AgentReply{Content: "AGREED: superficial agreement"}, nil
	}}
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return fakeR }}
	config := model.DebateConfig{
		Topic:     "Storage Architecture",
		Primary:   claudeAgent(),
		Secondary: agentPtr(geminiAgent()),
		RoundMode: model.RoundModeFixed,
		MaxRounds: 2,
		Consensus: &model.ConsensusConfig{
			Mode:                     model.ConsensusModeUnanimous,
			MinRoundsBeforeExit:      2,
			AllowMidRoundTermination: false,
		},
	}

	// Given an initial OPEN tension pair that is never synthesized or accepted as a trade-off
	openTension := model.TensionPair{
		ID:                 "tension_acid_vs_latency",
		UnderlyingConflict: "ACID Quorum vs Low-Latency SLA",
		Status:             model.TensionStatusOpen,
		Severity:           0.9,
		DetectedInRound:    1,
	}

	result, err := o.Run(context.Background(), RunOptions{
		Config:          config,
		InitialTensions: []model.TensionPair{openTension},
	})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// Invariant: Consensus must NOT be certified because the tension remained OPEN!
	if result.IsConsensusReached {
		t.Errorf("IsConsensusReached should be false due to open paraconsistent tension, got true")
	}

	hasWarningPill := false
	for _, m := range result.Transcript {
		if m.IsSystem && strings.Contains(m.Content, "Superficial consensus detected") {
			hasWarningPill = true
			break
		}
	}
	if !hasWarningPill {
		t.Errorf("expected moderator system intervention for superficial consensus with open tensions")
	}
}

type contextTrackingRunner struct {
	receivedContexts []string
	turnCount        int
}

func (c *contextTrackingRunner) Respond(ctx context.Context, agent model.Agent, topic, commonContext, commonInstructions string, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	c.receivedContexts = append(c.receivedContexts, commonContext)
	c.turnCount++
	round := 1
	if len(transcript) >= 2 {
		round = 2
	}
	if round == 1 {
		if agent.Provider == model.ProviderAnthropic {
			return runner.AgentReply{Content: "I assert that SQLite WAL concurrency benchmark throughput outpaces traditional databases under high load."}, nil
		}
		return runner.AgentReply{Content: "I disagree. SQLite concurrency locks can create bottlenecks if writers are prolonged."}, nil
	}
	return runner.AgentReply{Content: "AGREED: In Round 2, the evidence clearly shows readers never block writers in WAL mode."}, nil
}

func TestRoundAwareDynamicGraphRetrieval_InjectsEvidenceIntoSubsequentRound(t *testing.T) {
	store, err := graph.OpenSQLite(":memory:")
	if err != nil {
		t.Fatalf("failed to open memory sqlite: %v", err)
	}
	defer store.Close()

	ctx := context.Background()
	now := time.Now().UTC()

	// Seed knowledge graph with authoritative evidence
	node := &graph.Node{
		ID:             "node_wal_spec",
		ProjectID:      "proj_rag",
		Type:           graph.NodeTypeConcept,
		Title:          "SQLite WAL Concurrency",
		Content:        "Write-Ahead Logging permits readers to read while writer writes. Readers never block writers and writers never block readers.",
		Weight:         1.0,
		CurrentWeight:  1.0,
		CreatedAt:      now.Unix(),
		LastAccessedAt: now.Unix(),
	}
	if err := store.UpsertNode(ctx, node); err != nil {
		t.Fatalf("failed to insert graph node: %v", err)
	}

	tracker := &contextTrackingRunner{}
	o := &Orchestrator{
		RunnerFor: func(agent model.Agent) runner.AgentRunner {
			return tracker
		},
	}

	sec := model.Agent{ID: "gemini_seat", Provider: model.ProviderGemini, Model: "gemini-test"}
	config := model.DebateConfig{
		RoundMode: model.RoundModeFixed,
		MaxRounds: 2,
		Topic:     "Storage Engine Deliberation",
		Primary:   model.Agent{ID: "claude_seat", Provider: model.ProviderAnthropic, Model: "claude-test"},
		Secondary: &sec,
	}

	result, err := o.Run(ctx, RunOptions{
		Config:     config,
		GraphStore: store,
		ProjectID:  "proj_rag",
	})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}

	// 1. Verify that dynamic evidence was retrieved for Round 2
	if len(result.RetrievedEvidence) == 0 {
		t.Fatalf("expected RetrievedEvidence in result, got 0")
	}

	round2Evidence := result.RetrievedEvidence[0]
	if round2Evidence.Round != 2 {
		t.Errorf("expected Round 2 evidence, got round %d", round2Evidence.Round)
	}
	if len(round2Evidence.Items) == 0 {
		t.Fatalf("expected evidence items for round 2, got 0")
	}
	if round2Evidence.Items[0].SourceID != "node_wal_spec" {
		t.Errorf("expected source ID node_wal_spec, got %s", round2Evidence.Items[0].SourceID)
	}

	// 2. Verify that Round 2 turns received the grounding context
	hasInjectedContext := false
	for _, ctxStr := range tracker.receivedContexts {
		if strings.Contains(ctxStr, "[DYNAMIC GROUNDING EVIDENCE FOR ROUND 2]") && strings.Contains(ctxStr, "SQLite WAL Concurrency") {
			hasInjectedContext = true
			break
		}
	}
	if !hasInjectedContext {
		t.Errorf("expected Round 2 runner to receive injected evidence context")
	}

	// 3. Verify that a system marker was added to the transcript
	hasSystemMarker := false
	for _, m := range result.Transcript {
		if m.IsSystem && strings.Contains(m.Content, "Grounding evidence retrieved for Round 2") {
			hasSystemMarker = true
			break
		}
	}
	if !hasSystemMarker {
		t.Errorf("expected system notification marker in transcript for retrieved evidence")
	}
}


