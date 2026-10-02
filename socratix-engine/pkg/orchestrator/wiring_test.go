package orchestrator

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"

	"socratix/pkg/model"
	"socratix/pkg/runner"
)

type call struct {
	agent        model.Agent
	instructions string
	modelUsed    string
	n            int // transcript length seen
}

// recRunner records every Respond call and answers via reply (default: unique off-the-wall text).
type recRunner struct {
	mu    sync.Mutex
	calls []call
	reply func(c call, idx int) string
}

func (r *recRunner) Respond(_ context.Context, agent model.Agent, _, _, instr string, tr []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	c := call{agent: agent, instructions: instr, modelUsed: modelOverride, n: len(tr)}
	r.calls = append(r.calls, c)
	idx := len(r.calls) - 1
	if r.reply != nil {
		return runner.AgentReply{Content: r.reply(c, idx)}, nil
	}
	return runner.AgentReply{Content: fmt.Sprintf("Distinct argument number %d about subject %c%c%c", idx, 'a'+rune(idx%26), 'b'+rune(idx%25), 'c'+rune(idx%24))}, nil
}

func (r *recRunner) run(t *testing.T, cfg model.DebateConfig) model.DebateResult {
	t.Helper()
	o := &Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return r }}
	res, err := o.Run(context.Background(), RunOptions{Config: cfg})
	if err != nil {
		t.Fatalf("Run() error = %v", err)
	}
	return res
}

func isModCall(c call) bool { return strings.Contains(c.agent.SystemPrompt, "[TRIGGER:") }

func off(a model.AntiLoopConfig) *model.AntiLoopConfig { a.Enabled = false; return &a }

func baseCfg(rounds int, agents ...model.Agent) model.DebateConfig {
	cfg := model.DebateConfig{Topic: "database sharding strategy", Primary: agents[0], RoundMode: model.RoundModeFixed, MaxRounds: rounds}
	if len(agents) > 1 {
		cfg.Secondary = agentPtr(agents[1])
	}
	al := model.DefaultAntiLoopConfig()
	cfg.AntiLoop = off(al)
	return cfg
}

func openAI() model.Agent { return model.NewAgent(model.ProviderOpenAI, "gpt-4o") }

func TestSamplingStaticReachesRunnerPerRoundAndConclusion(t *testing.T) {
	s := model.DefaultSamplingConfig()
	s.Schedule = model.TemperatureStatic
	s.Temperature, s.FrequencyPenalty, s.PresencePenalty = 0.5, 0.2, 0.15
	cfg := baseCfg(2, openAI())
	cfg.Sampling = &s

	rr := &recRunner{}
	rr.run(t, cfg)

	if len(rr.calls) != 3 {
		t.Fatalf("calls = %d, want 3 (2 turns + conclusion)", len(rr.calls))
	}
	for i, wantT := range []float64{0.5, 0.5, s.ConclusionTemperature} {
		a := rr.calls[i].agent
		if a.Temperature == nil || !near(*a.Temperature, wantT) {
			t.Errorf("call %d temperature = %v, want %v", i, a.Temperature, wantT)
		}
		if a.FrequencyPenalty == nil || a.PresencePenalty == nil {
			t.Errorf("call %d OpenAI penalties not set", i)
		}
	}
	if f := rr.calls[0].agent.FrequencyPenalty; !near(*f, 0.2) {
		t.Errorf("turn freq penalty = %v, want 0.2", *f)
	}
	if f := rr.calls[2].agent.FrequencyPenalty; !near(*f, 0.10) {
		t.Errorf("conclusion freq penalty = %v, want 0.10", *f)
	}
}

func TestSamplingLinearCoolingPerRound(t *testing.T) {
	s := model.DefaultSamplingConfig()
	s.Schedule = model.TemperatureLinearCooling
	s.StartTemperature, s.FloorTemperature = 0.9, 0.3
	cfg := baseCfg(3, openAI())
	cfg.Sampling = &s

	rr := &recRunner{}
	rr.run(t, cfg)

	for i, wantT := range []float64{0.9, 0.6, 0.3, s.ConclusionTemperature} {
		if got := rr.calls[i].agent.Temperature; got == nil || !near(*got, wantT) {
			t.Errorf("call %d temperature = %v, want %v", i, got, wantT)
		}
	}
}

func TestSamplingAnthropicHasNoPenaltiesAndOverrideWins(t *testing.T) {
	s := model.DefaultSamplingConfig()
	s.Schedule = model.TemperatureStatic
	claude := claudeAgent()
	pinned := 0.1
	claude.Temperature = &pinned
	cfg := baseCfg(1, claude)
	cfg.Sampling = &s

	rr := &recRunner{}
	rr.run(t, cfg)

	a := rr.calls[0].agent
	if a.FrequencyPenalty != nil || a.PresencePenalty != nil {
		t.Errorf("Anthropic got penalties: %v %v", a.FrequencyPenalty, a.PresencePenalty)
	}
	if a.Temperature == nil || !near(*a.Temperature, 0.1) {
		t.Errorf("per-agent temperature = %v, want 0.1", a.Temperature)
	}
}

func TestSamplingStyleDirectiveForAnthropicHighFrequency(t *testing.T) {
	s := model.DefaultSamplingConfig()
	s.Schedule = model.TemperatureStatic
	s.FrequencyPenalty = 0.5 // > 0.20 -> Anthropic cannot send it, gets the style directive instead
	cfg := baseCfg(1, claudeAgent())
	cfg.Sampling = &s
	rr := &recRunner{}
	rr.run(t, cfg)
	if !strings.Contains(rr.calls[0].instructions, "STYLE DIRECTIVE: LEXICAL VARIATION") {
		t.Errorf("instructions missing style directive: %q", rr.calls[0].instructions)
	}
}

const loopText = "The shard key must be the tenant identifier because cross tenant joins are rare and rebalancing is cheap when tenants are small and evenly distributed across nodes."

func loopCfg(action model.LoopAction, retries int) model.DebateConfig {
	cfg := baseCfg(2, openAI())
	a := model.DefaultAntiLoopConfig()
	a.FallbackAction, a.MaxRetries = action, retries
	cfg.AntiLoop = &a
	s := model.DefaultSamplingConfig()
	s.Schedule = model.TemperatureStatic
	cfg.Sampling = &s
	return cfg
}

func TestAntiLoopRetriesThenFallsBack(t *testing.T) {
	cases := []struct {
		action model.LoopAction
		check  func(t *testing.T, tr []model.DebateMessage)
	}{
		{model.LoopActionConvertToConcession, func(t *testing.T, tr []model.DebateMessage) {
			m := tr[len(tr)-1]
			if !m.IsStalledConcession || !strings.Contains(m.Content, "Participant maintains their prior position") {
				t.Errorf("want stalled concession, got %+v", m)
			}
		}},
		{model.LoopActionRetryWithDirective, func(t *testing.T, tr []model.DebateMessage) {
			m := tr[len(tr)-1]
			if !m.IsStalledConcession || !strings.Contains(m.Content, "Core position maintained") {
				t.Errorf("want retry concession, got %+v", m)
			}
		}},
		{model.LoopActionModeratorIntervene, func(t *testing.T, tr []model.DebateMessage) {
			m := tr[len(tr)-1]
			if !m.IsModeratorIntervention || m.SeatID != "moderator" || m.Content != ModeratorImpasseText {
				t.Errorf("want moderator impasse message, got %+v", m)
			}
		}},
		{model.LoopActionHaltOrAdvance, func(t *testing.T, tr []model.DebateMessage) {
			if len(tr) != 1 {
				t.Errorf("turn should be dropped, transcript len = %d, want 1", len(tr))
			}
		}},
	}
	for _, c := range cases {
		t.Run(string(c.action), func(t *testing.T) {
			rr := &recRunner{reply: func(call, int) string { return loopText }}
			res := rr.run(t, loopCfg(c.action, 1))
			// round1 (1 call) + round2 (initial + 1 retry) + conclusion
			if len(rr.calls) != 4 {
				t.Fatalf("calls = %d, want 4", len(rr.calls))
			}
			t0, t1 := rr.calls[1].agent.Temperature, rr.calls[2].agent.Temperature
			if !near(*t1-*t0, 0.25) {
				t.Errorf("retry temperature bump = %v, want 0.25", *t1-*t0)
			}
			if !strings.Contains(rr.calls[2].instructions, "REPETITION DETECTED") || strings.Contains(rr.calls[1].instructions, "REPETITION DETECTED") {
				t.Errorf("anti-loop directive only expected on the retry")
			}
			if !near(*rr.calls[2].agent.FrequencyPenalty-*rr.calls[1].agent.FrequencyPenalty, 0.60) {
				t.Errorf("retry frequency bump wrong")
			}
			c.check(t, res.Transcript)
		})
	}
}

func TestAntiLoopRecoveredTurnIsFlagged(t *testing.T) {
	rr := &recRunner{reply: func(c call, _ int) string {
		if strings.Contains(c.instructions, "REPETITION DETECTED") {
			return "Entirely different consideration: operational cost of resharding dominates, so prefer consistent hashing with virtual nodes."
		}
		return loopText
	}}
	res := rr.run(t, loopCfg(model.LoopActionConvertToConcession, 1))
	tr := res.Transcript
	if len(tr) != 2 || !tr[1].IsLoopRecovered || tr[1].IsStalledConcession || tr[0].IsLoopRecovered {
		t.Fatalf("want round-2 turn recovered only, got %+v", tr)
	}
}

func TestAntiLoopDisabledAddsNoRetries(t *testing.T) {
	rr := &recRunner{reply: func(call, int) string { return loopText }}
	cfg := loopCfg(model.LoopActionConvertToConcession, 1)
	cfg.AntiLoop = off(*cfg.AntiLoop)
	res := rr.run(t, cfg)
	if len(rr.calls) != 3 || len(res.Transcript) != 2 || res.Transcript[1].IsStalledConcession {
		t.Fatalf("calls=%d transcript=%d, want 3 calls and the looping turn kept", len(rr.calls), len(res.Transcript))
	}
}

const offTopic = "Banana orchards flourish under generous sunlight while migratory swallows glide past quiet harvest meadows"

func wireModCfg(style model.ModerationStyle, rounds int) model.DebateConfig {
	cfg := baseCfg(rounds, claudeAgent(), geminiAgent())
	m := model.DefaultModeratorConfig()
	m.Enabled, m.Style = true, style
	m.DetectRepetition = false
	m.ModeratorModel = "custom-mod-model"
	cfg.Moderation = &m
	return cfg
}

func driftReply(c call, idx int) string {
	if isModCall(c) {
		return "FOCUS: return to sharding keys"
	}
	return fmt.Sprintf("%s %d", offTopic, idx)
}

func TestModeratorInterveneOnDriftRespectsMaxAndInjectsSteerage(t *testing.T) {
	cfg := wireModCfg(model.ModerationDynamicActiveSteerage, 2)
	cfg.Moderation.MaxInterventions = 1
	cfg.Moderation.Persona = model.PersonaSocraticProbe
	rr := &recRunner{reply: driftReply}
	res := rr.run(t, cfg)

	var mods []model.DebateMessage
	for _, m := range res.Transcript {
		if m.IsModeratorIntervention {
			mods = append(mods, m)
		}
	}
	if len(mods) != 1 {
		t.Fatalf("interventions = %d, want exactly 1 (MaxInterventions)", len(mods))
	}
	if mods[0].SeatID != "moderator" || mods[0].AuthorDisplayName != "Socratic Inquirer" || !strings.HasPrefix(mods[0].Content, "FOCUS") {
		t.Errorf("bad moderator message: %+v", mods[0])
	}
	// First call is turn 1; the moderator call comes right after it.
	modCall := rr.calls[1]
	if !isModCall(modCall) || !strings.Contains(modCall.agent.SystemPrompt, "TOPICAL DRIFT") {
		t.Fatalf("call 1 should be the drift moderator call, got %q", modCall.agent.SystemPrompt)
	}
	if modCall.modelUsed != "custom-mod-model" {
		t.Errorf("moderator model override = %q", modCall.modelUsed)
	}
	if strings.Contains(rr.calls[0].instructions, "MANDATORY MODERATOR STEERAGE") {
		t.Errorf("steerage must not appear before an intervention")
	}
	if !strings.Contains(rr.calls[2].instructions, "[MANDATORY MODERATOR STEERAGE DIRECTIVE]\nFOCUS: return to sharding keys") {
		t.Errorf("next turn instructions missing steerage: %q", rr.calls[2].instructions)
	}
	modCalls := 0
	for _, c := range rr.calls {
		if isModCall(c) {
			modCalls++
		}
	}
	if modCalls != 1 {
		t.Errorf("moderator calls = %d, want 1", modCalls)
	}
	if len(res.Transcript) != 5 { // 4 agent turns + 1 intervention
		t.Errorf("transcript len = %d, want 5", len(res.Transcript))
	}
}

func TestModeratorSteerageNotInjectedWhenNotEnforced(t *testing.T) {
	cfg := wireModCfg(model.ModerationDynamicActiveSteerage, 1)
	cfg.Moderation.EnforceSteerageDirectives = false
	rr := &recRunner{reply: driftReply}
	rr.run(t, cfg)
	for _, c := range rr.calls {
		if !isModCall(c) && strings.Contains(c.instructions, "MANDATORY MODERATOR STEERAGE") {
			t.Fatalf("steerage injected although EnforceSteerageDirectives=false")
		}
	}
}

func TestModeratorCheckpointAfterConfiguredRounds(t *testing.T) {
	cfg := wireModCfg(model.ModerationPeriodicCheckpoint, 3)
	cfg.Moderation.CheckpointFrequencyRounds = 2
	rr := &recRunner{reply: driftReply}
	res := rr.run(t, cfg)

	var idx []int
	for i, m := range res.Transcript {
		if m.IsModeratorIntervention {
			idx = append(idx, i)
			if m.Round != 2 {
				t.Errorf("checkpoint round = %d, want 2", m.Round)
			}
		}
	}
	// 2 agents * 2 rounds = indices 0..3, checkpoint at 4, then round 3 (5,6).
	if len(idx) != 1 || idx[0] != 4 || len(res.Transcript) != 7 {
		t.Fatalf("checkpoint at %v, transcript len %d; want [4] and 7", idx, len(res.Transcript))
	}
	for _, c := range rr.calls {
		if isModCall(c) && !strings.Contains(c.agent.SystemPrompt, "PERIODIC COUNCIL CHECKPOINT") {
			t.Errorf("unexpected moderator trigger: %q", c.agent.SystemPrompt)
		}
	}
	// Steerage from the checkpoint reaches round 3's first turn.
	seen := false
	afterMod := false
	for _, c := range rr.calls {
		if isModCall(c) {
			afterMod = true
			continue
		}
		if afterMod && strings.Contains(c.instructions, "FOCUS: return to sharding keys") {
			seen = true
		}
	}
	if !seen {
		t.Errorf("checkpoint steerage not injected into later turns")
	}
}

func TestModeratorDisabledAndPassiveAddNoTurns(t *testing.T) {
	for name, mutate := range map[string]func(*model.DebateConfig){
		"nil": func(c *model.DebateConfig) { c.Moderation = nil },
		"disabled": func(c *model.DebateConfig) {
			c.Moderation.Enabled = false
			c.Moderation.Style = model.ModerationStrictArbitration
		},
		"passive": func(c *model.DebateConfig) { c.Moderation.Style = model.ModerationPassiveWrapupOnly },
	} {
		t.Run(name, func(t *testing.T) {
			cfg := wireModCfg(model.ModerationDynamicActiveSteerage, 2)
			mutate(&cfg)
			rr := &recRunner{reply: driftReply}
			res := rr.run(t, cfg)
			// (the tension detector makes its own calls, so count moderator calls and messages instead)
			if len(res.Transcript) != 4 {
				t.Errorf("transcript len = %d, want 4 agent turns", len(res.Transcript))
			}
			for _, c := range rr.calls {
				if isModCall(c) {
					t.Errorf("unexpected moderator call")
				}
			}
			for _, m := range res.Transcript {
				if m.IsModeratorIntervention {
					t.Errorf("unexpected moderator message")
				}
			}
		})
	}
}

func TestModeratorInterveneOnRepetitionInRun(t *testing.T) {
	cfg := wireModCfg(model.ModerationDynamicActiveSteerage, 2)
	cfg.Moderation.DetectRepetition = true
	cfg.Moderation.DetectTopicDrift = false
	cfg.Moderation.Strictness = 4 // 2 consecutive no-novelty turns
	cfg.Moderation.LoopDetectionThreshold = 5
	rr := &recRunner{reply: func(c call, _ int) string {
		if isModCall(c) {
			return "STEER: new angle"
		}
		return loopText
	}}
	res := rr.run(t, cfg)
	found := false
	for _, c := range rr.calls {
		if isModCall(c) && strings.Contains(c.agent.SystemPrompt, "REPETITION DETECTED") {
			found = true
		}
	}
	if !found {
		t.Fatalf("no repetition intervention; transcript=%+v", res.Transcript)
	}
}

func turn(seat, content string) model.DebateMessage {
	return model.DebateMessage{SeatID: seat, Content: content}
}

func TestShouldInterveneOnRepetition(t *testing.T) {
	mod := model.DefaultModeratorConfig()
	mod.Enabled = true
	loop := model.DefaultAntiLoopConfig()
	distinct := []string{
		"Sharding by tenant keeps joins local and simplifies compliance boundaries for regulated customers.",
		"Consistent hashing with virtual nodes reduces the cost of rebalancing when capacity changes unexpectedly.",
		"Operationally a range partition scheme allows cheap archival of cold data without touching hot shards.",
	}
	var same, varied []model.DebateMessage
	for i := 0; i < 4; i++ {
		same = append(same, turn("a", loopText))
	}
	for _, d := range distinct {
		varied = append(varied, turn("a", d))
	}

	if _, ok := ShouldInterveneOnRepetition(mod, loop, same, 0); !ok {
		t.Errorf("4 identical turns (strictness 3 -> 3) should trigger")
	}
	if _, ok := ShouldInterveneOnRepetition(mod, loop, varied, 0); ok {
		t.Errorf("distinct turns must not trigger")
	}
	if _, ok := ShouldInterveneOnRepetition(mod, loop, same[:3], 0); ok {
		t.Errorf("3 identical turns have only 2 no-novelty turns (first has no prior)")
	}

	m := mod
	m.Strictness = 5 // 1 turn
	if _, ok := ShouldInterveneOnRepetition(m, loop, same[:2], 0); !ok {
		t.Errorf("strictness 5 should trigger on one repeated turn")
	}
	m = mod
	m.Strictness, m.LoopDetectionThreshold = 1, 2 // 5 turns bounded to 2
	if _, ok := ShouldInterveneOnRepetition(m, loop, same[:3], 0); !ok {
		t.Errorf("LoopDetectionThreshold should bound the streak to 2")
	}
	m = mod
	m.DetectRepetition = false
	if _, ok := ShouldInterveneOnRepetition(m, loop, same, 0); ok {
		t.Errorf("DetectRepetition=false must not trigger")
	}
	m = mod
	m.MaxInterventions = 2
	if _, ok := ShouldInterveneOnRepetition(m, loop, same, 2); ok {
		t.Errorf("MaxInterventions reached must not trigger")
	}
	m = mod
	m.Style = model.ModerationPeriodicCheckpoint
	if _, ok := ShouldInterveneOnRepetition(m, loop, same, 0); ok {
		t.Errorf("checkpoint-only style must not trigger")
	}
	// A moderator message resets the streak.
	reset := append(append([]model.DebateMessage{}, same...), model.DebateMessage{IsModeratorIntervention: true, Content: "order"})
	if _, ok := ShouldInterveneOnRepetition(mod, loop, reset, 0); ok {
		t.Errorf("streak must restart after a moderator message")
	}
	// Stalled concessions count as no novelty.
	conc := []model.DebateMessage{{Content: "x", IsStalledConcession: true}, {Content: "y", IsStalledConcession: true}, {Content: "z", IsStalledConcession: true}}
	if _, ok := ShouldInterveneOnRepetition(mod, loop, conc, 0); !ok {
		t.Errorf("stalled concessions should trigger")
	}
}
