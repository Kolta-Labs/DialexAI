package api

import (
	"encoding/json"
	"net/http"
	"strings"

	"dialex/pkg/decomposition"
	"dialex/pkg/model"
)

// handleDecomposeProblem handles POST /api/v1/discussions/decompose
func (s *Server) handleDecomposeProblem(w http.ResponseWriter, r *http.Request) {
	var req decomposition.DecompositionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json payload: "+err.Error())
		return
	}

	trimmedTopic := strings.TrimSpace(req.Topic)
	if trimmedTopic == "" {
		writeError(w, http.StatusBadRequest, "topic is required")
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		state = model.NewAppState()
	}

	// Resolve provider and model
	var provider model.Provider
	var modelName string
	runMode := model.RunModeAPI

	if req.Provider != "" {
		provider = model.Provider(req.Provider)
		modelName = req.Model
	} else {
		// Auto-select first available provider with configured API key or CLI
		for _, p := range model.AllProviders {
			if key := state.ApiKeys.ForProvider(p); key != "" {
				provider = p
				modelName = p.DefaultModel()
				runMode = model.RunModeAPI
				break
			}
		}
		if provider == "" {
			for _, p := range model.AllProviders {
				if cmd := state.CliCommands.ForProvider(p); cmd != "" {
					provider = p
					modelName = p.DefaultModel()
					runMode = model.RunModeCLI
					break
				}
			}
		}
	}

	agent := model.Agent{
		Provider: provider,
		Model:    modelName,
		RunMode:  runMode,
	}

	var agentRunner = s.runnerForAgent(agent)

	res, err := decomposition.DecomposeProblem(
		r.Context(),
		agentRunner,
		agent,
		trimmedTopic,
		req.Context,
		"",
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "decomposition failed: "+err.Error())
		return
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(res)
}
