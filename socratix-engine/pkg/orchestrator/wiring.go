package orchestrator

import (
	"socratix/pkg/model"
	"socratix/pkg/runner"
)

// loopRetryFrequencyBump is added to the frequency penalty on each anti-loop retry
// (Kotlin: freqBump += 0.60).
const loopRetryFrequencyBump = 0.60

// applySampling copies normalized sampling onto the agent the runner will see. A nil penalty
// means the provider rejects it, so it is cleared; a per-agent penalty wins over the schedule.
func applySampling(a *model.Agent, ns NormalizedSampling) {
	t := ns.Temperature
	a.Temperature = &t
	a.TopP = ns.TopP
	if ns.FrequencyPenalty == nil {
		a.FrequencyPenalty = nil
	} else if a.FrequencyPenalty == nil {
		a.FrequencyPenalty = ns.FrequencyPenalty
	}
	if ns.PresencePenalty == nil {
		a.PresencePenalty = nil
	} else if a.PresencePenalty == nil {
		a.PresencePenalty = ns.PresencePenalty
	}
}

func isRealTurn(m model.DebateMessage) bool {
	return !m.IsError && !m.IsSystem && !m.IsUserComment
}

// loopCandidates are the earlier turns a new reply from agent is compared against: its own
// prior turns plus the last spoken turn (Kotlin candidateTurns).
func loopCandidates(transcript []model.DebateMessage, agent model.Agent) []string {
	var own []string
	last, haveLast := "", false
	for _, m := range transcript {
		if !isRealTurn(m) {
			continue
		}
		last, haveLast = m.Content, true
		if ownedBySeat(m, agent) {
			own = append(own, m.Content)
		}
	}
	if haveLast {
		for _, c := range own {
			if c == last {
				return own
			}
		}
		own = append(own, last)
	}
	return own
}

// loopFallback builds the turn that replaces a reply still looping after all retries
// (Kotlin fallbackMsg). nil = drop the turn (HALT_OR_ADVANCE).
func loopFallback(action model.LoopAction, agent model.Agent, primary model.Provider, round int, reply runner.AgentReply) *model.DebateMessage {
	switch action {
	case model.LoopActionHaltOrAdvance:
		return nil
	case model.LoopActionModeratorIntervene:
		return &model.DebateMessage{
			SeatID:                  "moderator",
			Provider:                primary,
			AuthorDisplayName:       "Moderator",
			AgentID:                 primary,
			Round:                   round,
			Content:                 ModeratorImpasseText,
			IsModeratorIntervention: true,
		}
	}
	content := StalledConcessionText(agent.Label(), round)
	m := model.DebateMessage{
		SeatID: agent.ID, Provider: agent.Provider, AuthorDisplayName: agent.Label(), AgentID: agent.Provider,
		Round: round, IsStalledConcession: true,
	}
	if action == model.LoopActionRetryWithDirective {
		content = StalledRetryConcessionText(agent.Label(), round)
	} else { // CONVERT_TO_CONCESSION (also the default)
		m.TokensIn, m.TokensOut, m.TokensCached = reply.TokensIn, reply.TokensOut, reply.TokensCached
	}
	m.Content = content
	return &m
}

// repetitionWindow bounds how many earlier turns each recent turn is compared against
// (InspectLoop is O(n*m) per pair).
const repetitionWindow = 4

// ShouldInterveneOnRepetition reports whether the moderator should step in because the last N
// consecutive turns added nothing new, N = min(ModeratorTriggerTurns(Strictness),
// LoopDetectionThreshold). A turn adds nothing new when it was an anti-loop stalled
// concession, or InspectLoop (with the anti-loop thresholds) flags it against the preceding
// turns. Only turns after the latest moderator message count, so one intervention is not
// repeated every turn. Needs Enabled, DetectRepetition, style DYNAMIC_ACTIVE_STEERAGE or
// STRICT_ARBITRATION, and interventions < MaxInterventions (when positive).
func ShouldInterveneOnRepetition(cfg model.ModeratorConfig, loop model.AntiLoopConfig, transcript []model.DebateMessage, interventions int) (string, bool) {
	if !cfg.Enabled || !cfg.DetectRepetition {
		return "", false
	}
	if cfg.Style != model.ModerationDynamicActiveSteerage && cfg.Style != model.ModerationStrictArbitration {
		return "", false
	}
	if cfg.MaxInterventions > 0 && interventions >= cfg.MaxInterventions {
		return "", false
	}
	n := ModeratorTriggerTurns(cfg.Strictness)
	if cfg.LoopDetectionThreshold > 0 && cfg.LoopDetectionThreshold < n {
		n = cfg.LoopDetectionThreshold
	}
	var turns []model.DebateMessage
	for _, m := range transcript {
		if m.IsModeratorIntervention {
			turns = nil
			continue
		}
		if isRealTurn(m) {
			turns = append(turns, m)
		}
	}
	if len(turns) < n {
		return "", false
	}
	for i := len(turns) - n; i < len(turns); i++ {
		if !noNovelty(turns, i, loop) {
			return "", false
		}
	}
	return TriggerRepetition, true
}

func noNovelty(turns []model.DebateMessage, i int, loop model.AntiLoopConfig) bool {
	if turns[i].IsStalledConcession {
		return true
	}
	from := i - repetitionWindow
	if from < 0 {
		from = 0
	}
	var prior []string
	for _, p := range turns[from:i] {
		prior = append(prior, p.Content)
	}
	return InspectLoop(turns[i].Content, prior, loop.MaxSimilarityThreshold, loop.MaxContiguousDuplicateChars).Detected
}
