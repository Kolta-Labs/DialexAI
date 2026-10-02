// Package quickstart builds ready-to-run councils for people who have ONE API key (or a local
// Ollama): every seat is a different persona on the same model, tuned against the conformity
// that makes same-model debate agree too easily.
package quickstart

import (
	"fmt"
	"strings"

	"socratix/pkg/model"
)

// Persona is one seat: a name, and the instructions that make it argue from a fixed stance.
type Persona struct {
	Name   string
	Role   string
	Prompt string
}

// Mode is a named way of stress-testing a decision.
type Mode struct {
	ID       string
	Name     string
	Blurb    string
	Rounds   int
	Chair    Persona   // speaks first each round and writes the final verdict
	Council  []Persona // the arguing seats
	Question string    // how the user's topic is framed to the council
	// KeepDissent disables early consensus so a dissenter cannot be argued out of the room.
	KeepDissent bool
}

// antiConformity is appended to every seat. It is the cheap, prompt-level half of the defence;
// the structural half is the blind first round and anonymized peers (model.IndependenceConfig).
const antiConformity = "\n\nRules for everyone: do not agree just to be agreeable or polite. Change your position only " +
	"for a new argument or evidence, and say exactly what changed your mind. Be concrete: name the " +
	"assumption, the number, or the failure mode. Keep each turn under 250 words."

const chairPrompt = "You chair this council. You are neutral: you do not take a side. Frame the question, then weigh the " +
	"arguments on their merits. Your final verdict must state: the decision or recommendation, the strongest argument " +
	"AGAINST it, what would change your mind, and which disagreements remain unresolved. Never hide dissent." + antiConformity

var modes = []Mode{
	{
		ID: "premortem", Name: "Pre-mortem",
		Blurb:    "Assume the plan already failed. Find out why before you start.",
		Rounds:   2,
		Chair:    Persona{"Chair", "Neutral Chair", chairPrompt},
		Question: "Pre-mortem. Assume it is 12 months from now and this has FAILED. Explain why, then say what to do about it now. Plan: ",
		Council: []Persona{
			{"Failure Analyst", "Pre-mortem Analyst", "You write the story of how this failed. Pick the most likely causes, in order, with the early warning signs nobody noticed. Assume the plan was executed as described." + antiConformity},
			{"Operator", "Execution Realist", "You run the work day to day. Attack timelines, dependencies, staffing and hidden costs. Say what will really take twice as long." + antiConformity},
			{"Sponsor", "Plan Advocate", "You want this to work. Defend the plan's strongest logic, but concede a risk only when it is real, and say what you would do to mitigate it." + antiConformity},
		},
	},
	{
		ID: "redteam", Name: "Red team",
		Blurb:    "A proponent and attackers go three rounds on your plan.",
		Rounds:   3,
		Chair:    Persona{"Chair", "Neutral Chair", chairPrompt},
		Question: "Red-team this proposal. The proponent steelmans it; the others attack it. Proposal: ",
		Council: []Persona{
			{"Proponent", "Steelman", "You make the strongest honest case for the proposal. Answer attacks with evidence, not rhetoric, and admit the points you cannot answer." + antiConformity},
			{"Red Team", "Adversary", "You attack the proposal: unstated assumptions, failure modes, perverse incentives, what a competitor or critic would say. Do not soften." + antiConformity},
			{"Risk Auditor", "Downside Specialist", "You look only at downside: legal, security, reputational and irreversible risks, and the worst realistic case. Rate each by likelihood and cost." + antiConformity},
		},
	},
	{
		ID: "tenthman", Name: "Tenth man",
		Blurb:       "When everyone agrees, one seat must argue the opposite. Dissent is kept.",
		Rounds:      3,
		KeepDissent: true,
		Chair:       Persona{"Chair", "Neutral Chair", chairPrompt},
		Question:    "Decide this, with a mandatory dissenter. Decision to test: ",
		Council: []Persona{
			{"Strategist", "Case Builder", "You build the best case for the decision and say what outcome you expect." + antiConformity},
			{"Analyst", "Evidence Checker", "You test the claims made so far: what is asserted without evidence, what numbers are missing, what is the base rate." + antiConformity},
			{"Tenth Man", "Mandatory Dissenter", "You are the Tenth Man. Whatever the others conclude, you argue the opposite as strongly and honestly as you can, and you never open a reply with AGREED. Each turn name the one fact that, if true, would prove you right, and what the others have not answered." + antiConformity},
		},
	},
}

// Modes lists the available modes in a stable order.
func Modes() []Mode { return append([]Mode(nil), modes...) }

// Get returns a mode by ID.
func Get(id string) (Mode, bool) {
	for _, m := range modes {
		if m.ID == strings.ToLower(strings.TrimSpace(id)) {
			return m, true
		}
	}
	return Mode{}, false
}

// Seats returns the chair and the arguing seats for a mode, all on one provider and model, each
// with a stable unique ID. Build uses it, and so can a benchmark that needs the same council.
func Seats(m Mode, provider model.Provider, modelName string) []model.Agent {
	if modelName == "" {
		modelName = provider.DefaultModel()
	}
	seat := func(p Persona, n int) model.Agent {
		a := model.NewAgent(provider, modelName)
		a.ID = fmt.Sprintf("qs_%s_%d", m.ID, n)
		a.DisplayName, a.Role, a.SystemPrompt = p.Name, p.Role, p.Prompt
		a.RunMode = model.RunModeAPI
		return a
	}
	seats := []model.Agent{seat(m.Chair, 0)}
	for i, p := range m.Council {
		seats = append(seats, seat(p, i+1))
	}
	return seats
}

// Build makes a runnable config where every seat uses the same provider and model.
// rounds <= 0 uses the mode's default. The mode's framing goes in the topic.
func Build(m Mode, topic string, provider model.Provider, modelName string, rounds int) (model.DebateConfig, error) {
	if strings.TrimSpace(topic) == "" {
		return model.DebateConfig{}, fmt.Errorf("a question or plan is required")
	}
	if rounds <= 0 {
		rounds = m.Rounds
	}
	base := model.DebateConfig{Primary: model.Agent{Provider: provider, Model: modelName, RunMode: model.RunModeAPI}}
	cfg := Apply(base, m)
	cfg.Topic = m.Question + strings.TrimSpace(topic)
	cfg.CommonContext = ""
	cfg.MaxRounds = rounds
	return cfg, nil
}

// Apply turns an existing config into a one-provider council, keeping its topic, files and
// other settings. The chair replaces Primary, the mode's seats fill Secondary..Quaternary
// (Quinary/Senary are cleared), and the framing is appended to CommonContext. Mirrors the
// Kotlin OneKeyCouncil.apply it replaces.
func Apply(current model.DebateConfig, m Mode) model.DebateConfig {
	seats := Seats(m, current.Primary.Provider, current.Primary.Model)
	for i := range seats {
		seats[i].RunMode = current.Primary.RunMode
	}
	out := current
	out.Primary = seats[0]
	out.Secondary, out.Tertiary, out.Quaternary, out.Quinary, out.Senary = nil, nil, nil, nil, nil
	for i, slot := range []**model.Agent{&out.Secondary, &out.Tertiary, &out.Quaternary} {
		if i+1 < len(seats) {
			*slot = &seats[i+1]
		}
	}
	out.RoundMode = model.RoundModeFixed
	out.MaxRounds = m.Rounds
	out.Independence = &model.IndependenceConfig{BlindFirstRound: true, AnonymizeTranscript: true}
	if ctx := strings.TrimSpace(current.CommonContext); ctx != "" {
		out.CommonContext = ctx + "\n\n" + m.Question
	} else {
		out.CommonContext = m.Question
	}
	if m.KeepDissent {
		c := model.ConsensusConfig{}
		if current.Consensus != nil {
			c = *current.Consensus
		}
		c.Mode = model.ConsensusModeDisabled
		out.Consensus = &c
	}
	return out
}
