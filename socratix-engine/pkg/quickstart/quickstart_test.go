package quickstart

import (
	"context"
	"os"
	"strings"
	"testing"

	"socratix/pkg/model"
	"socratix/pkg/orchestrator"
	"socratix/pkg/runner"
)

type scripted struct {
	reply func(agent model.Agent, calls int) string
}

func (s *scripted) Respond(ctx context.Context, agent model.Agent, topic, commonContext, commonInstructions string, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	return runner.AgentReply{Content: s.reply(agent, len(transcript))}, nil
}

func TestEveryModeBuildsOneProviderCouncilWithUniqueSeats(t *testing.T) {
	for _, m := range Modes() {
		cfg, err := Build(m, "launch in EU first", model.ProviderAnthropic, "", 0)
		if err != nil {
			t.Fatalf("%s: %v", m.ID, err)
		}
		seen := map[string]bool{}
		for _, a := range cfg.Agents() {
			if a.Provider != model.ProviderAnthropic || a.Model == "" || a.RunMode != model.RunModeAPI {
				t.Errorf("%s: seat %s not a one-key API seat: %+v", m.ID, a.Label(), a)
			}
			if seen[a.ID] {
				t.Errorf("%s: duplicate seat ID %s", m.ID, a.ID)
			}
			seen[a.ID] = true
			if !strings.Contains(a.SystemPrompt, "do not agree just to be agreeable") {
				t.Errorf("%s/%s: missing the anti-conformity rule", m.ID, a.Label())
			}
		}
		if cfg.Independence == nil || !cfg.Independence.BlindFirstRound || !cfg.Independence.AnonymizeTranscript {
			t.Errorf("%s: blind first round and anonymized peers must be on", m.ID)
		}
		if !strings.Contains(cfg.Topic, "launch in EU first") {
			t.Errorf("%s: topic lost: %q", m.ID, cfg.Topic)
		}
	}
	if _, err := Build(Modes()[0], "  ", model.ProviderAnthropic, "", 0); err == nil {
		t.Fatal("an empty question must be refused")
	}
}

func TestTenthManKeepsDissentAndNeverOpensWithAgreed(t *testing.T) {
	m, _ := Get("tenthman")
	cfg, _ := Build(m, "ship the rewrite", model.ProviderOpenAI, "", 0)
	if cfg.Consensus == nil || cfg.Consensus.Mode != model.ConsensusModeDisabled {
		t.Fatal("tenth man must disable early consensus")
	}
	var dissenter model.Agent
	for _, a := range cfg.Agents() {
		if a.DisplayName == "Tenth Man" {
			dissenter = a
		}
	}
	if !strings.Contains(dissenter.SystemPrompt, "never open a reply with AGREED") {
		t.Fatal("the dissenter must be forbidden from conceding by AGREED")
	}
}

func TestEveryModeRunsEndToEndAndProducesAMemo(t *testing.T) {
	for _, m := range Modes() {
		cfg, _ := Build(m, "should we migrate to Postgres?", model.ProviderAnthropic, "", 0)
		fake := &scripted{reply: func(a model.Agent, _ int) string { return "AGREED: " + a.Label() + " position" }}
		o := &orchestrator.Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return fake }}
		res, err := o.Run(context.Background(), orchestrator.RunOptions{Config: cfg})
		if err != nil {
			t.Fatalf("%s: %v", m.ID, err)
		}
		if m.KeepDissent {
			want := len(cfg.Agents()) * cfg.MaxRounds
			if len(res.Transcript) != want {
				t.Errorf("%s: even when everyone says AGREED all %d turns must run, got %d", m.ID, want, len(res.Transcript))
			}
		}
		memo := Memo(m, "should we migrate to Postgres?", cfg, res)
		for _, want := range []string{"# Decision memo: " + m.Name, "## Verdict", "## Independent first positions", "## Open disagreements", "one model"} {
			if !strings.Contains(memo, want) {
				t.Errorf("%s: memo missing %q", m.ID, want)
			}
		}
	}
}

func TestConfidenceIsCappedByWhatIsUnresolved(t *testing.T) {
	m, _ := Get("redteam")
	one, _ := Build(m, "x", model.ProviderAnthropic, "", 0)
	concl := "ship it"
	clean := model.DebateResult{Conclusion: &concl}

	if got := AssessConfidence(clean, one).Level; got != "Medium" {
		t.Errorf("same-model council is capped at Medium, got %s", got)
	}
	severe := clean
	severe.TensionPairs = []model.TensionPair{{Status: model.TensionStatusOpen, Severity: 0.9, UnderlyingConflict: "cost vs speed"}}
	if got := AssessConfidence(severe, one).Level; got != "Low" {
		t.Errorf("a serious open tension must give Low, got %s", got)
	}
	if got := AssessConfidence(model.DebateResult{}, one).Level; got != "Low" {
		t.Errorf("no verdict must give Low, got %s", got)
	}

	// a mixed-provider council with a clean result may reach High
	mixed := one
	other := model.NewAgent(model.ProviderOpenAI, "x")
	mixed.Secondary = &other
	if got := AssessConfidence(clean, mixed).Level; got != "High" {
		t.Errorf("clean verdict from different models should be High, got %s", got)
	}
}

func TestDetectProviderAndRunner(t *testing.T) {
	env := func(m map[string]string) func(string) string { return func(k string) string { return m[k] } }
	if p, err := DetectProvider(env(map[string]string{"OPENAI_API_KEY": "k"})); err != nil || p != model.ProviderOpenAI {
		t.Fatalf("got %v %v", p, err)
	}
	if _, err := DetectProvider(env(nil)); err == nil || !strings.Contains(err.Error(), "ANTHROPIC_API_KEY") {
		t.Fatalf("with no key the error must say which variables to set, got %v", err)
	}
	if _, err := RunnerFor(model.ProviderAnthropic, env(nil)); err == nil {
		t.Fatal("missing key must be an error")
	}
	if _, err := RunnerFor(model.ProviderOllama, env(nil)); err != nil {
		t.Fatalf("ollama needs no key: %v", err)
	}
	if _, err := ParseProvider("nope"); err == nil {
		t.Fatal("unknown provider must be refused")
	}
	_ = os.Getenv
}

func TestMemoNeverPresentsAHeuristicGuessAsNoDisagreements(t *testing.T) {
	m, _ := Get("redteam")
	cfg, _ := Build(m, "x", model.ProviderAnthropic, "", 0)
	res := model.DebateResult{TensionFallbackRounds: 2}
	memo := Memo(m, "x", cfg, res)
	if !strings.Contains(memo, "Not reliably assessed") || strings.Contains(memo, "None detected") {
		t.Fatalf("memo must say disagreements were not assessed:\n%s", memo)
	}
	if got := AssessConfidence(res, cfg); got.Level == "High" {
		t.Fatalf("fallback must cap trust, got %s", got.Level)
	}
}

func TestApplyTransformsConfigToOneKeyCouncil(t *testing.T) {
	for _, m := range Modes() {
		// Start with a config that has some context and files
		current := model.DebateConfig{
			Topic:         "original topic",
			CommonContext: "existing context",
			Primary:       model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5"),
		}

		// Apply the mode
		result := Apply(current, m)

		// Verify topic wasn't lost, but mode framing was added
		if !strings.Contains(result.CommonContext, "existing context") {
			t.Errorf("%s: existing context lost", m.ID)
		}
		if !strings.Contains(result.CommonContext, m.Question) {
			t.Errorf("%s: mode framing not merged into commonContext", m.ID)
		}

		// Verify seats structure
		agents := result.Agents()
		if len(agents) < 2 {
			t.Errorf("%s: expected at least 2 seats, got %d", m.ID, len(agents))
		}
		if agents[0].DisplayName != "Chair" {
			t.Errorf("%s: primary should be Chair, got %s", m.ID, agents[0].DisplayName)
		}

		// Verify persona prompts are assigned
		for _, a := range agents {
			if a.SystemPrompt == "" {
				t.Errorf("%s: seat %s has empty SystemPrompt", m.ID, a.DisplayName)
			}
		}

		// Verify independence and roundMode
		if result.RoundMode != model.RoundModeFixed {
			t.Errorf("%s: expected RoundMode FIXED, got %s", m.ID, result.RoundMode)
		}
		if result.MaxRounds != m.Rounds {
			t.Errorf("%s: expected MaxRounds %d, got %d", m.ID, m.Rounds, result.MaxRounds)
		}
		if result.Independence == nil || !result.Independence.BlindFirstRound || !result.Independence.AnonymizeTranscript {
			t.Errorf("%s: expected independence guards enabled", m.ID)
		}

		// Verify quinary/senary are cleared
		if result.Quinary != nil || result.Senary != nil {
			t.Errorf("%s: quinary/senary should be cleared", m.ID)
		}

		// Verify dissent handling
		if m.KeepDissent {
			if result.Consensus == nil || result.Consensus.Mode != model.ConsensusModeDisabled {
				t.Errorf("%s: KeepDissent modes must disable consensus", m.ID)
			}
		}
	}
}
