package model

import (
	"encoding/json"
	"testing"
)

// Old field names (claude/gemini/chatgpt) must still decode into the renamed seats — a
// state.json written by the Kotlin app (or an earlier Go engine version) has to keep
// loading without a migration step.
func TestOldFieldNamesStillDecode(t *testing.T) {
	old := `{"topic":"t","claude":{"provider":"ANTHROPIC","model":"claude-x"},
	         "gemini":{"provider":"GEMINI","model":"gemini-x"}}`

	var config DebateConfig
	if err := json.Unmarshal([]byte(old), &config); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if config.Primary.Provider != ProviderAnthropic {
		t.Errorf("Primary.Provider = %v, want ANTHROPIC", config.Primary.Provider)
	}
	if config.Secondary == nil || config.Secondary.Provider != ProviderGemini {
		t.Errorf("Secondary = %+v, want GEMINI", config.Secondary)
	}
	if config.Tertiary != nil {
		t.Errorf("Tertiary = %+v, want nil", config.Tertiary)
	}
}

func TestAgentsIsCompactedSpeakingOrder(t *testing.T) {
	config := DebateConfig{
		Primary:  NewAgent(ProviderAnthropic, "claude-sonnet-5"),
		Tertiary: ptr(NewAgent(ProviderOpenAI, "gpt-5.6-sol")),
		Quinary:  ptr(NewAgent(ProviderMistral, "mistral-medium-latest")),
	}

	agents := config.Agents()
	if len(agents) != 3 {
		t.Fatalf("len(Agents()) = %d, want 3", len(agents))
	}
	want := []Provider{ProviderAnthropic, ProviderOpenAI, ProviderMistral}
	for i, p := range want {
		if agents[i].Provider != p {
			t.Errorf("Agents()[%d].Provider = %v, want %v", i, agents[i].Provider, p)
		}
	}
}

func TestAgentLabelFallsBackToBrandName(t *testing.T) {
	claude := NewAgent(ProviderAnthropic, "claude-sonnet-5")
	if got := claude.Label(); got != "Claude" {
		t.Errorf("Label() = %q, want %q", got, "Claude")
	}

	custom := Agent{Provider: ProviderCustom, DisplayName: "Aider"}
	if got := custom.Label(); got != "Aider" {
		t.Errorf("Label() = %q, want %q", got, "Aider")
	}
}

// A fully populated AppState survives encode then decode — covers the whole tree
// (projects, discussions with a full transcript and every optional seat, settings), not
// just one corner of the model.
func TestAppStateRoundTrips(t *testing.T) {
	tokensIn, tokensOut := 100, 50
	config := DebateConfig{
		Topic:         "Is a hot dog a sandwich?",
		CommonContext: "context",
		CommonInfo:    "info",
		Primary:       Agent{Provider: ProviderAnthropic, Model: "claude-sonnet-5", RunMode: RunModeCLI, CliCommand: strPtr("claude --print"), Caveman: true},
		Secondary:     ptr(NewAgent(ProviderGemini, "gemini-3.7-flash")),
		Tertiary:      ptr(Agent{Provider: ProviderCustom, DisplayName: "Aider", RunMode: RunModeCLI}),
		RoundMode:     RoundModeUnlimited,
		MaxRounds:     7,
	}
	discussion := Discussion{
		ID:        "d1",
		ProjectID: "p1",
		Name:      "Discussion One",
		Config:    config,
		Status:    DiscussionPaused,
		Transcript: []DebateMessage{
			{AgentID: ProviderAnthropic, Round: 1, Content: "opening"},
			{AgentID: ProviderGemini, Round: 1, Content: "reply", TokensIn: &tokensIn, TokensOut: &tokensOut},
			{AgentID: ProviderCustom, Round: 1, Content: "boom", IsError: true},
		},
		HandoffPrompt: strPtr("handoff text"),
	}
	original := AppState{
		Projects:        []Project{{ID: "p1", Name: "Project One"}},
		Discussions:     []Discussion{discussion},
		ApiKeys:         ApiKeys{Anthropic: "sk-ant-x", OpenAI: "sk-oa-x", Gemini: "sk-g-x"},
		CliCommands:     CliCommands{Custom: "aider --message"},
		CompactionModel: "claude-haiku-4-5-20251001",
		TokenBudget:     50_000,
		AgentLimit:      4,
		Users:           []User{{Username: "admin", PasswordHash: "$2a$..."}},
	}

	data, err := json.Marshal(original)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	var roundTripped AppState
	if err := json.Unmarshal(data, &roundTripped); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	rt, _ := json.Marshal(roundTripped)
	orig, _ := json.Marshal(original)
	if string(rt) != string(orig) {
		t.Errorf("round trip mismatch:\n got: %s\nwant: %s", rt, orig)
	}
}

func ptr(a Agent) *Agent      { return &a }
func strPtr(s string) *string { return &s }
