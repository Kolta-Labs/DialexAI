package orchestrator

import (
	"fmt"

	"dialex/pkg/model"
)

// applyIndependence returns the transcript view a seat is allowed to see. It never mutates the
// persisted transcript: blind and anonymized views are built per turn.
//
//   - BlindFirstRound drops other seats' round-1 answers while round 1 is being played.
//   - AnonymizeTranscript renames other seats "Participant A/B/C" by their fixed seat order, so
//     the same seat keeps the same letter for everyone.
//
// Moderator interventions, system notes, user comments and the seat's own turns are untouched.
func applyIndependence(views []model.DebateMessage, forAgent model.Agent, round int, cfg *model.IndependenceConfig, seats []model.Agent) []model.DebateMessage {
	if cfg == nil || (!cfg.BlindFirstRound && !cfg.AnonymizeTranscript) {
		return views
	}
	index := make(map[string]int, len(seats))
	for i, a := range seats {
		index[a.ID] = i
	}
	out := make([]model.DebateMessage, 0, len(views))
	for _, m := range views {
		i, isSeatTurn := index[m.SeatID]
		peerTurn := isSeatTurn && !m.IsSystem && !m.IsUserComment && !m.IsModeratorIntervention && !ownedBySeat(m, forAgent)
		if peerTurn && cfg.BlindFirstRound && round == 1 && m.Round == 1 {
			continue
		}
		if peerTurn && cfg.AnonymizeTranscript {
			m.AuthorDisplayName = fmt.Sprintf("Participant %c", 'A'+rune(i))
		}
		out = append(out, m)
	}
	return out
}

// ApplyIndependence is applyIndependence for callers outside the orchestrator, such as the
// benchmark runner, which plays its own council loop.
func ApplyIndependence(views []model.DebateMessage, forAgent model.Agent, round int, cfg *model.IndependenceConfig, seats []model.Agent) []model.DebateMessage {
	return applyIndependence(views, forAgent, round, cfg, seats)
}
