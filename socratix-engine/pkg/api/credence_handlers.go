package api

import (
	"net/http"
	"time"

	"socratix/pkg/credence"
	"socratix/pkg/model"
)

// handleGetCredenceLedger returns the Bayesian Credence Ledger for a discussion.
// If the discussion has no ledger yet, it initializes one from the debate topic and transcript.
func (s *Server) handleGetCredenceLedger(w http.ResponseWriter, r *http.Request) {
	discID := r.PathValue("id")
	if discID == "" {
		writeError(w, http.StatusBadRequest, "discussion id is required")
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load state: "+err.Error())
		return
	}

	var disc *model.Discussion
	var discIdx = -1
	for i := range state.Discussions {
		if state.Discussions[i].ID == discID {
			disc = &state.Discussions[i]
			discIdx = i
			break
		}
	}
	if disc == nil {
		writeError(w, http.StatusNotFound, "discussion not found")
		return
	}

	if disc.CredenceLedger != nil && len(disc.CredenceLedger.Hypotheses) > 0 {
		writeJSON(w, http.StatusOK, disc.CredenceLedger)
		return
	}

	// Initialize new ledger
	topic := disc.Config.Topic
	if topic == "" {
		topic = disc.Name
	}
	if topic == "" {
		topic = "System Architecture Dilemma"
	}

	hypotheses := credence.HeuristicExtractHypotheses(topic, disc.Config.CommonContext)
	ledger := &model.CredenceLedger{
		DiscussionID: disc.ID,
		Topic:        topic,
		Hypotheses:   hypotheses,
		Snapshots:    []model.RoundCredenceSnapshot{credence.CreateInitialSnapshot(hypotheses)},
		FinalEntropy: credence.CalculateShannonEntropy(credence.CreateInitialSnapshot(hypotheses).AggregatedCredence),
		Status:       "IN_PROGRESS",
	}

	// If transcript already has messages, evaluate past rounds
	maxRound := 0
	for _, m := range disc.Transcript {
		if m.Round > maxRound {
			maxRound = m.Round
		}
	}

	authorityWeights := make(map[string]float64)
	agents := disc.Config.Agents()

	for rIdx := 1; rIdx <= maxRound; rIdx++ {
		personaCredences := credence.HeuristicEvaluateRoundCredence(hypotheses, disc.Transcript, agents, rIdx)
		aggCredence := credence.AggregateCouncilCredence(personaCredences, authorityWeights, hypotheses)
		snapshot := model.RoundCredenceSnapshot{
			RoundIndex:         rIdx,
			Timestamp:          time.Now(),
			PersonaCredences:   personaCredences,
			AggregatedCredence: aggCredence,
		}
		ledger = credence.UpdateCredenceLedger(ledger, snapshot, disc.RetrievedEvidence)
	}

	disc.CredenceLedger = ledger
	state.Discussions[discIdx] = *disc
	_ = s.Store.Save(state)

	writeJSON(w, http.StatusOK, ledger)
}

// handleRecalculateCredence re-evaluates hypotheses and rounds across the entire transcript.
func (s *Server) handleRecalculateCredence(w http.ResponseWriter, r *http.Request) {
	discID := r.PathValue("id")
	if discID == "" {
		writeError(w, http.StatusBadRequest, "discussion id is required")
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load state: "+err.Error())
		return
	}

	var disc *model.Discussion
	var discIdx = -1
	for i := range state.Discussions {
		if state.Discussions[i].ID == discID {
			disc = &state.Discussions[i]
			discIdx = i
			break
		}
	}
	if disc == nil {
		writeError(w, http.StatusNotFound, "discussion not found")
		return
	}

	topic := disc.Config.Topic
	if topic == "" {
		topic = disc.Name
	}
	if topic == "" {
		topic = "System Architecture Dilemma"
	}

	hypotheses := credence.HeuristicExtractHypotheses(topic, disc.Config.CommonContext)
	ledger := &model.CredenceLedger{
		DiscussionID: disc.ID,
		Topic:        topic,
		Hypotheses:   hypotheses,
		Snapshots:    []model.RoundCredenceSnapshot{credence.CreateInitialSnapshot(hypotheses)},
		FinalEntropy: credence.CalculateShannonEntropy(credence.CreateInitialSnapshot(hypotheses).AggregatedCredence),
		Status:       "IN_PROGRESS",
	}

	maxRound := 0
	for _, m := range disc.Transcript {
		if m.Round > maxRound {
			maxRound = m.Round
		}
	}

	authorityWeights := make(map[string]float64)
	agents := disc.Config.Agents()

	for rIdx := 1; rIdx <= maxRound; rIdx++ {
		personaCredences := credence.HeuristicEvaluateRoundCredence(hypotheses, disc.Transcript, agents, rIdx)
		aggCredence := credence.AggregateCouncilCredence(personaCredences, authorityWeights, hypotheses)
		snapshot := model.RoundCredenceSnapshot{
			RoundIndex:         rIdx,
			Timestamp:          time.Now(),
			PersonaCredences:   personaCredences,
			AggregatedCredence: aggCredence,
		}
		ledger = credence.UpdateCredenceLedger(ledger, snapshot, disc.RetrievedEvidence)
	}

	disc.CredenceLedger = ledger
	state.Discussions[discIdx] = *disc
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to persist recalculated ledger: "+err.Error())
		return
	}

	writeJSON(w, http.StatusOK, ledger)
}
