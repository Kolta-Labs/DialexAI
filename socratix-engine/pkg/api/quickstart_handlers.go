package api

import (
	"encoding/json"
	"fmt"
	"net/http"

	"socratix/pkg/model"
	"socratix/pkg/quickstart"
)

type quickstartModeInfo struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Blurb       string `json:"blurb"`
	Rounds      int    `json:"rounds"`
	KeepDissent bool   `json:"keepDissent"`
}

type quickstartApplyRequest struct {
	Mode   string             `json:"mode"`
	Config model.DebateConfig `json:"config"`
}

// handleListQuickstartModes lists available quickstart modes.
// GET /api/v1/quickstart/modes
func (s *Server) handleListQuickstartModes(w http.ResponseWriter, r *http.Request) {
	modes := quickstart.Modes()
	result := make([]quickstartModeInfo, len(modes))
	for i, m := range modes {
		result[i] = quickstartModeInfo{
			ID:          m.ID,
			Title:       m.Name,
			Blurb:       m.Blurb,
			Rounds:      m.Rounds,
			KeepDissent: m.KeepDissent,
		}
	}
	writeJSON(w, http.StatusOK, result)
}

// handleApplyQuickstartMode applies a quickstart mode to an existing config.
// POST /api/v1/quickstart/apply
func (s *Server) handleApplyQuickstartMode(w http.ResponseWriter, r *http.Request) {
	var req quickstartApplyRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body: "+err.Error())
		return
	}

	mode, ok := quickstart.Get(req.Mode)
	if !ok {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("unknown mode: %q", req.Mode))
		return
	}

	result := quickstart.Apply(req.Config, mode)
	writeJSON(w, http.StatusOK, result)
}
