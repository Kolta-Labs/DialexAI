package orchestrator

import (
	"strings"
	"testing"

	"socratix/pkg/model"
)

const (
	offTopicTurn = "We must evaluate the fluid viscosity and rheometer calibration drag coefficients under non-Newtonian flow."
	onTopicTurn  = "Product managers bridge technical roadmaps with enterprise customer feedback to prioritize engineering headcount."
	pmTopic      = "Can AI replace product managers"
)

func modCfg() model.ModeratorConfig {
	c := model.DefaultModeratorConfig()
	c.Enabled = true
	return c
}

func TestShouldInterveneOnDrift(t *testing.T) {
	r, ok := ShouldIntervene(modCfg(), pmTopic, model.DebateMessage{Content: offTopicTurn}, 0)
	if !ok || r != TriggerTopicalDrift {
		t.Fatalf("off-topic should trigger: %v %v", r, ok)
	}
	if _, ok := ShouldIntervene(modCfg(), pmTopic, model.DebateMessage{Content: onTopicTurn}, 0); ok {
		t.Error("on-topic must not trigger")
	}
}

func TestShouldInterveneGates(t *testing.T) {
	off := model.DebateMessage{Content: offTopicTurn}
	mut := func(f func(*model.ModeratorConfig)) model.ModeratorConfig { c := modCfg(); f(&c); return c }
	cases := map[string]bool{
		"disabled":       wouldFire(mut(func(c *model.ModeratorConfig) { c.Enabled = false }), off, 0),
		"passive":        wouldFire(mut(func(c *model.ModeratorConfig) { c.Style = model.ModerationPassiveWrapupOnly }), off, 0),
		"periodic only":  wouldFire(mut(func(c *model.ModeratorConfig) { c.Style = model.ModerationPeriodicCheckpoint }), off, 0),
		"drift off":      wouldFire(mut(func(c *model.ModeratorConfig) { c.DetectTopicDrift = false }), off, 0),
		"max reached":    wouldFire(modCfg(), off, 5),
		"error turn":     wouldFire(modCfg(), model.DebateMessage{Content: offTopicTurn, IsError: true}, 0),
		"moderator turn": wouldFire(modCfg(), model.DebateMessage{Content: offTopicTurn, IsModeratorIntervention: true}, 0),
		"user comment":   wouldFire(modCfg(), model.DebateMessage{Content: offTopicTurn, IsUserComment: true}, 0),
		"system turn":    wouldFire(modCfg(), model.DebateMessage{Content: offTopicTurn, IsSystem: true}, 0),
	}
	for name, fired := range cases {
		if fired {
			t.Errorf("%s should not fire", name)
		}
	}
	if !wouldFire(mut(func(c *model.ModeratorConfig) { c.Style = model.ModerationStrictArbitration }), off, 0) {
		t.Error("strict arbitration should fire")
	}
}

// test helper
func wouldFire(c model.ModeratorConfig, m model.DebateMessage, n int) bool {
	_, ok := ShouldIntervene(c, pmTopic, m, n)
	return ok
}

func TestShouldCheckpointOnModuloRounds(t *testing.T) {
	c := modCfg()
	c.Style = model.ModerationPeriodicCheckpoint
	c.CheckpointFrequencyRounds = 2
	var fired []int
	for r := 1; r <= 3; r++ {
		if ShouldCheckpoint(c, r) {
			fired = append(fired, r)
		}
	}
	if len(fired) != 1 || fired[0] != 2 {
		t.Errorf("fired %v", fired)
	}
	c.Style = model.ModerationDynamicActiveSteerage
	if ShouldCheckpoint(c, 2) {
		t.Error("dynamic style has no checkpoints")
	}
	c.Style, c.CheckpointFrequencyRounds = model.ModerationStrictArbitration, 0
	if !ShouldCheckpoint(c, 2) {
		t.Error("zero frequency defaults to 2")
	}
}

func TestModeratorPersonaNamesAndPrompts(t *testing.T) {
	cases := []struct {
		p    model.ModeratorPersona
		name string
		lead string
	}{
		{model.PersonaDeliberationChair, "Deliberation Chair", "You are the Deliberation Chair."},
		{model.PersonaExecutiveArbiter, "Executive Arbiter", "You are the Executive Arbiter."},
		{model.PersonaSocraticProbe, "Socratic Inquirer", "You are the Socratic Inquirer."},
		{model.PersonaDevilsAdvocateChair, "Devil's Advocate Chair", "You are the Devil's Advocate Chair."},
	}
	for _, c := range cases {
		if ModeratorAuthorName(c.p) != c.name {
			t.Errorf("name %v", c.p)
		}
		cfg := modCfg()
		cfg.Persona = c.p
		if p := BuildModeratorPrompt(TriggerTopicalDrift, cfg, "T", 1); !strings.HasPrefix(p, c.lead) || !strings.Contains(p, "[TRIGGER: TOPICAL DRIFT") {
			t.Errorf("drift prompt %v: %q", c.p, p[:60])
		}
	}
}

func TestBuildModeratorPromptShapes(t *testing.T) {
	cfg := modCfg()
	cfg.Persona = model.PersonaDeliberationChair
	cp := BuildModeratorBasePrompt(TriggerPeriodicCheckpoint, cfg.Persona, "Topic X", 2)
	for _, want := range []string{"PERIODIC COUNCIL CHECKPOINT", "Interim Checkpoint (End of Round 2)", `"Topic X"`} {
		if !strings.Contains(cp, want) {
			t.Errorf("checkpoint missing %q", want)
		}
	}
	d := BuildModeratorBasePrompt(TriggerTopicalDrift, cfg.Persona, "Topic X", 3)
	if !strings.Contains(d, "Intervention (Round 3)") || strings.Count(d, `"Topic X"`) != 3 {
		t.Errorf("drift base: %q", d)
	}
	if got := BuildModeratorBasePrompt("OTHER", cfg.Persona, "Topic X", 1); !strings.HasSuffix(got, `refocus the debate on "Topic X".`) {
		t.Errorf("default: %q", got)
	}
	// extras only when configured
	cfg.EnforceCivility, cfg.EnforceEvidence = false, false
	cfg.CustomDirectives = "Be brief."
	cfg.InterventionStyle = model.InterventionForceVote
	p := BuildModeratorPrompt(TriggerTopicalDrift, cfg, "Topic X", 1)
	if !strings.Contains(p, "FORCE VOTE") || !strings.Contains(p, "Be brief.") || strings.Contains(p, "EVIDENCE STANDARD") || strings.Contains(p, "CIVILITY") {
		t.Errorf("extras: %q", p)
	}
	if BuildModeratorPrompt(TriggerPeriodicCheckpoint, modCfg(), "T", 2) == BuildModeratorBasePrompt(TriggerPeriodicCheckpoint, modCfg().Persona, "T", 2) {
		t.Error("civility (default on) should append")
	}
}

func TestModeratorTriggerTurns(t *testing.T) {
	for s, want := range map[int]int{0: 3, 1: 5, 2: 4, 3: 3, 4: 2, 5: 1, 9: 1, -2: 5} {
		if got := ModeratorTriggerTurns(s); got != want {
			t.Errorf("strictness %d: got %d want %d", s, got, want)
		}
	}
}
