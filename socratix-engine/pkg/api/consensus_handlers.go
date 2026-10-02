package api

import (
	"encoding/json"
	"net/http"

	"socratix/pkg/consensus"
	"socratix/pkg/model"
)

type evaluateConsensusRequest struct {
	Config     model.DebateConfig    `json:"config"`
	Transcript []model.DebateMessage `json:"transcript"`
	Round      *int                  `json:"round,omitempty"` // optional; if not provided, defaults to max round
}

// handleEvaluateConsensus evaluates consensus on a debate transcript.
// POST /api/v1/consensus/evaluate
func (s *Server) handleEvaluateConsensus(w http.ResponseWriter, r *http.Request) {
	var req evaluateConsensusRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body: "+err.Error())
		return
	}

	// Determine the current round
	currentRound := 1
	if req.Round != nil {
		currentRound = *req.Round
		if currentRound < 1 {
			currentRound = 1
		}
	} else {
		// Default to max round among non-error/non-user/non-system messages
		for _, msg := range req.Transcript {
			if !msg.IsError && !msg.IsUserComment && !msg.IsSystem {
				if msg.Round > currentRound {
					currentRound = msg.Round
				}
			}
		}
		if currentRound == 1 && len(req.Transcript) == 0 {
			currentRound = 1 // fallback if no messages
		}
	}

	result := consensus.Evaluate(currentRound, req.Transcript, req.Config)
	writeJSON(w, http.StatusOK, result)
}
