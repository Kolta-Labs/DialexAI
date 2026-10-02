package orchestrator

import (
	"fmt"
	"strings"

	"socratix/pkg/model"
)

// Pure moderator helpers ported from the Kotlin DebateOrchestrator
// (checkModeratorIntervention / checkPeriodicCheckpoint / buildModeratorPrompt, git d0af8e3).

// Trigger reasons (Kotlin string literals).
const (
	TriggerTopicalDrift       = "TOPICAL_DRIFT"
	TriggerPeriodicCheckpoint = "PERIODIC_CHECKPOINT"
)

// ModeratorTriggerTurns maps strictness to the consecutive no-novelty turns tolerated before
// the moderator steps in, from the Kotlin doc comment: 1=5 (passive), 2=4, 3=3 (balanced),
// 4=2, 5=1 (ruthless). Out of range is clamped; 0 (unset) means the default 3.
// NOTE: Kotlin documented this but never used strictness in the orchestrator.
func ModeratorTriggerTurns(strictness int) int {
	if strictness == 0 {
		strictness = 3
	}
	if strictness < 1 {
		strictness = 1
	}
	if strictness > 5 {
		strictness = 5
	}
	return 6 - strictness
}

// ModeratorAuthorName is the transcript display name for the persona.
func ModeratorAuthorName(p model.ModeratorPersona) string {
	switch p {
	case model.PersonaDeliberationChair:
		return "Deliberation Chair"
	case model.PersonaSocraticProbe:
		return "Socratic Inquirer"
	case model.PersonaDevilsAdvocateChair:
		return "Devil's Advocate Chair"
	default:
		return "Executive Arbiter"
	}
}

// ShouldIntervene is the per-turn drift check run after an agent's turn. It returns the
// trigger reason (TriggerTopicalDrift) when the moderator should speak. Mirrors Kotlin:
// needs Enabled, a style other than PASSIVE_WRAPUP_ONLY, a real agent turn (not error /
// system / moderator / user comment), style DYNAMIC_ACTIVE_STEERAGE or STRICT_ARBITRATION,
// and SemanticSimilarity(turn, topic) < DriftThreshold. Go addition: DetectTopicDrift=false
// disables the check (Kotlin ignored that toggle).
// Honouring MaxInterventions is the caller's job: pass the count so far as interventions.
func ShouldIntervene(cfg model.ModeratorConfig, topic string, last model.DebateMessage, interventions int) (reason string, ok bool) {
	if !cfg.Enabled || cfg.Style == model.ModerationPassiveWrapupOnly {
		return "", false
	}
	if last.IsError || last.IsSystem || last.IsModeratorIntervention || last.IsUserComment {
		return "", false
	}
	if cfg.MaxInterventions > 0 && interventions >= cfg.MaxInterventions {
		return "", false
	}
	if cfg.Style != model.ModerationDynamicActiveSteerage && cfg.Style != model.ModerationStrictArbitration {
		return "", false
	}
	if !cfg.DetectTopicDrift {
		return "", false
	}
	if SemanticSimilarity(last.Content, topic) < cfg.DriftThreshold {
		return TriggerTopicalDrift, true
	}
	return "", false
}

// ShouldCheckpoint reports whether a periodic checkpoint is due after the given round:
// Enabled, style PERIODIC_CHECKPOINT or STRICT_ARBITRATION, round % frequency == 0.
// A frequency <= 0 is treated as the default 2 (Kotlin would divide by zero).
func ShouldCheckpoint(cfg model.ModeratorConfig, round int) bool {
	if !cfg.Enabled {
		return false
	}
	if cfg.Style != model.ModerationPeriodicCheckpoint && cfg.Style != model.ModerationStrictArbitration {
		return false
	}
	f := cfg.CheckpointFrequencyRounds
	if f <= 0 {
		f = 2
	}
	return round%f == 0
}

func personaDirective(p model.ModeratorPersona) string {
	switch p {
	case model.PersonaDeliberationChair:
		return "You are the Deliberation Chair. Maintain parliamentary order, enforce topical adherence, and structure the council's inquiry."
	case model.PersonaSocraticProbe:
		return "You are the Socratic Inquirer. Probe unexamined assumptions, demand definitions of ambiguous terms, and challenge participants to test boundary conditions."
	case model.PersonaDevilsAdvocateChair:
		return "You are the Devil's Advocate Chair. Aggressively challenge emerging consensus, point out blind spots, and demand rigorous stress-testing of assumptions."
	default:
		return "You are the Executive Arbiter. You are pragmatic, ROI-driven, and intolerant of academic fluff or tangential theories. Demand concrete organizational reality."
	}
}

// BuildModeratorBasePrompt is the exact Kotlin buildModeratorPrompt text (persona, trigger
// reason, topic, round) with none of the Go-side extras.
func BuildModeratorBasePrompt(reason string, persona model.ModeratorPersona, topic string, round int) string {
	pd := personaDirective(persona)
	switch reason {
	case TriggerTopicalDrift:
		return fmt.Sprintf(`%[1]s

[TRIGGER: TOPICAL DRIFT & PEDANTRY DETECTED]
The council was convened to address: "%[2]s".
Recent participant turns have drifted into abstract tangents, analogies, or pedantic theoretical side-quests.

Issue an authoritative intervention for Round %[3]d:
1. Call order in the council and state explicitly what off-topic tangent must stop immediately.
2. Re-anchor the deliberation firmly onto the original topic: "%[2]s".
3. Provide a MANDATORY FOCUS QUESTION that participants must answer in their next turn.

Format:
### 🏛️ Deliberation Chair — Intervention (Round %[3]d)
**Order in the council.** [Explanation of drift]

**Mandatory Steerage for Next Turn:**
[Concrete, high-signal questions strictly addressing "%[2]s"]`, pd, topic, round)
	case TriggerPeriodicCheckpoint:
		return fmt.Sprintf(`%[1]s

[TRIGGER: PERIODIC COUNCIL CHECKPOINT]
The council has concluded Round %[3]d on: "%[2]s".
Synthesize an interim state of the deliberation:

Format:
### 🏛️ Deliberation Chair — Interim Checkpoint (End of Round %[3]d)
**Current State of Consensus:**
• **ESTABLISHED:** [1-2 bullets on what participants have fundamentally settled]
• **CONTESTED:** [1-2 bullets on the core unresolved sticking points]

**Mandatory Focus for Next Round:**
[Targeted question forcing participants to directly resolve the contested points]`, pd, topic, round)
	default:
		return fmt.Sprintf("%s\nDeliver an authoritative steerage intervention to refocus the debate on \"%s\".", pd, topic)
	}
}

// BuildModeratorPrompt is BuildModeratorBasePrompt plus the config-driven directives that the
// Kotlin settings UI exposed but its orchestrator never applied: intervention style,
// civility, evidence, and free-form customDirectives (also the TopicDriftDirective for drift
// triggers). Each is appended only when its setting calls for it.
func BuildModeratorPrompt(reason string, cfg model.ModeratorConfig, topic string, round int) string {
	var b strings.Builder
	b.WriteString(BuildModeratorBasePrompt(reason, cfg.Persona, topic, round))
	if reason == TriggerTopicalDrift {
		if d := strings.TrimSpace(cfg.TopicDriftDirective); d != "" {
			b.WriteString("\n\n[TOPIC DRIFT DIRECTIVE]\n" + d)
		}
		switch cfg.InterventionStyle {
		case model.InterventionChallenge:
			b.WriteString("\n\n[INTERVENTION STYLE: CHALLENGE]\nDemand that each participant provide one concrete counter-example or retract their position.")
		case model.InterventionForceVote:
			b.WriteString("\n\n[INTERVENTION STYLE: FORCE VOTE]\nDemand that each participant state their final position and score their confidence 1-10.")
		default: // REDIRECT
			b.WriteString("\n\n[INTERVENTION STYLE: REDIRECT]\nTell participants that they have strayed; they must introduce a new on-topic angle or concede.")
		}
	}
	if cfg.EnforceEvidence {
		b.WriteString("\n\n[EVIDENCE STANDARD]\nRequire participants to support every claim with concrete evidence, data or a cited source; call out unsupported assertions.")
	}
	if cfg.EnforceCivility {
		b.WriteString("\n\n[CIVILITY]\nKeep the council courteous: criticise arguments, never people.")
	}
	if d := strings.TrimSpace(cfg.CustomDirectives); d != "" {
		b.WriteString("\n\n[CUSTOM MODERATOR DIRECTIVES]\n" + d)
	}
	return b.String()
}
