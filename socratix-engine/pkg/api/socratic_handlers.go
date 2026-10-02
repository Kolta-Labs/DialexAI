package api

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"socratix/pkg/model"
	"socratix/pkg/socratic"
)

// handleSocraticTurn processes a single user message and returns the persona's probe and updated ledger.
func (s *Server) handleSocraticTurn(w http.ResponseWriter, r *http.Request) {
	discID := r.PathValue("id")
	if discID == "" {
		writeError(w, http.StatusBadRequest, "discussion id is required")
		return
	}

	var req socratic.SocraticTurnRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json payload: "+err.Error())
		return
	}

	trimmedMsg := strings.TrimSpace(req.Message)
	if trimmedMsg == "" {
		writeError(w, http.StatusBadRequest, "message is required")
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load state: "+err.Error())
		return
	}

	var disc *model.Discussion
	var discIdx int = -1
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

	nowMs := time.Now().UnixMilli()

	// Ensure discussion is flagged as Socratic Mode
	disc.Mode = model.DiscussionModeSocraticInterview
	if disc.Status == model.DiscussionDraft {
		disc.Status = model.DiscussionRunning
	}

	if disc.SocraticConfig == nil {
		disc.SocraticConfig = &model.SocraticConfig{
			Stance: req.Stance,
			Stage:  req.Stage,
		}
	}
	if req.Stance != "" {
		disc.SocraticConfig.Stance = req.Stance
	}
	if req.Stage != "" {
		disc.SocraticConfig.Stage = req.Stage
	}

	// Append user turn to transcript
	userMsg := model.DebateMessage{
		SeatID:            "user",
		AuthorDisplayName: "User",
		Content:           trimmedMsg,
		IsUserComment:     true,
		Round:             len(disc.Transcript)/2 + 1,
		TimestampMs:       nowMs,
	}
	disc.Transcript = append(disc.Transcript, userMsg)

	// Determine interviewer agent
	agent := disc.Config.Primary
	if agent.Provider == "" {
		agent = model.Agent{
			Provider:    model.ProviderAnthropic,
			Model:       model.ProviderAnthropic.DefaultModel(),
			DisplayName: "The Socratic Inquisitor",
			Role:        "Socrates",
		}
		for _, p := range model.AllProviders {
			if key := state.ApiKeys.ForProvider(p); key != "" {
				agent.Provider = p
				agent.Model = p.DefaultModel()
				agent.RunMode = model.RunModeAPI
				break
			}
		}
	}
	if disc.SocraticConfig.InterviewerName != "" {
		agent.DisplayName = disc.SocraticConfig.InterviewerName
	}

	runner := s.runnerForAgent(agent)

	topic := disc.Config.Topic
	if topic == "" {
		topic = req.Topic
	}
	if topic == "" {
		topic = trimmedMsg
	}

	turnResp, err := socratic.ExecuteTurn(
		r.Context(),
		runner,
		agent,
		req,
		disc.Transcript,
		"",
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to execute socratic turn: "+err.Error())
		return
	}

	// Append persona probe turn to transcript
	personaMsg := model.DebateMessage{
		SeatID:            agent.ID,
		AgentID:           agent.Provider,
		Provider:          agent.Provider,
		AuthorDisplayName: agent.Label(),
		Content:           turnResp.ProbeQuestion,
		IsUserComment:     false,
		Round:             userMsg.Round,
		TimestampMs:       time.Now().UnixMilli(),
	}
	disc.Transcript = append(disc.Transcript, personaMsg)

	// Merge ledger updates
	existingLedgerMap := make(map[string]int)
	for i, item := range disc.SocraticLedger {
		existingLedgerMap[item.ID] = i
	}
	for _, update := range turnResp.LedgerUpdates {
		if idx, exists := existingLedgerMap[update.ID]; exists {
			disc.SocraticLedger[idx] = update
		} else {
			disc.SocraticLedger = append(disc.SocraticLedger, update)
		}
	}

	disc.SocraticConfig.Stage = turnResp.NewStage
	disc.UpdatedAt = time.Now().Unix()

	state.Discussions[discIdx] = *disc
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save state: "+err.Error())
		return
	}

	writeJSON(w, http.StatusOK, turnResp)
}

// handleSocraticDigest synthesizes the interview transcript into an authoritative digest and syncs to graph.
func (s *Server) handleSocraticDigest(w http.ResponseWriter, r *http.Request) {
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
	var discIdx int = -1
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

	req := socratic.SocraticDigestRequest{
		Topic:        disc.Config.Topic,
		Transcript:   disc.Transcript,
		Ledger:       disc.SocraticLedger,
		ProjectID:    disc.ProjectID,
		DiscussionID: disc.ID,
	}
	if req.Topic == "" && len(disc.Transcript) > 0 {
		req.Topic = disc.Transcript[0].Content
	}

	agent := disc.Config.Primary
	if agent.Provider == "" {
		agent = model.Agent{
			Provider: model.ProviderAnthropic,
			Model:    model.ProviderAnthropic.DefaultModel(),
		}
	}
	runner := s.runnerForAgent(agent)

	digest, err := socratic.GenerateDigest(r.Context(), runner, agent, req, "")
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to generate socratic digest: "+err.Error())
		return
	}

	disc.SocraticDigest = digest
	disc.Status = model.DiscussionCompleted
	disc.UpdatedAt = time.Now().Unix()

	// Append to artifacts
	artifactID := fmt.Sprintf("art_%s_digest", disc.ID)
	disc.Artifacts = append(disc.Artifacts, model.DiscussionArtifact{
		ID:          artifactID,
		Name:        "Socratic Interview Digest",
		Type:        "SOCRATIC_DIGEST",
		Format:      "markdown",
		Content:     digest.HardenedThesis,
		Timestamp:   time.Now().UTC().Format(time.RFC3339),
		TimestampMs: time.Now().UnixMilli(),
		SizeBytes:   int64(len(digest.HardenedThesis)),
	})

	// Enhancement 3: Auto-sync to SQLite Knowledge Graph
	if s.GraphStore != nil && disc.ProjectID != "" {
		_ = socratic.SyncDigestToKnowledgeGraph(r.Context(), s.GraphStore, disc.ProjectID, disc.ID, digest)
	}

	state.Discussions[discIdx] = *disc
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save state: "+err.Error())
		return
	}

	writeJSON(w, http.StatusOK, digest)
}

// handleSocraticElevate converts an interview's residual tensions and hardened thesis into a fresh Council debate.
func (s *Server) handleSocraticElevate(w http.ResponseWriter, r *http.Request) {
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
	for i := range state.Discussions {
		if state.Discussions[i].ID == discID {
			disc = &state.Discussions[i]
			break
		}
	}
	if disc == nil {
		writeError(w, http.StatusNotFound, "discussion not found")
		return
	}

	var req socratic.SocraticElevateRequest
	_ = json.NewDecoder(r.Body).Decode(&req)

	digest := disc.SocraticDigest
	if digest == nil && req.Digest.InitialHypothesis != "" {
		digest = &req.Digest
	}
	if digest == nil {
		h := socratic.HeuristicDigest(disc.Config.Topic, disc.Transcript, disc.SocraticLedger)
		digest = &h
	}

	// Format structured council agenda
	var agendaBuilder strings.Builder
	agendaBuilder.WriteString("### 🏛️ Hardened Architectural Thesis (From Socratic Interview)\n")
	agendaBuilder.WriteString(digest.HardenedThesis)
	agendaBuilder.WriteString("\n\n")

	if len(digest.DefendedInvariants) > 0 {
		agendaBuilder.WriteString("### 🛡️ Validated Baseline Invariants\n")
		for _, inv := range digest.DefendedInvariants {
			agendaBuilder.WriteString(fmt.Sprintf("- %s\n", inv))
		}
		agendaBuilder.WriteString("\n")
	}

	if len(digest.ResidualTensions) > 0 {
		agendaBuilder.WriteString("### 🎯 Council Agenda: Residual Dialectical Tensions\n")
		agendaBuilder.WriteString("The council must explicitly confront and synthesize the following unresolved tensions:\n")
		for i, ten := range digest.ResidualTensions {
			agendaBuilder.WriteString(fmt.Sprintf("%d. **%s**\n", i+1, ten))
		}
	}

	// Generate new discussion
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	newID := fmt.Sprintf("disc_council_%s", hex.EncodeToString(b))

	title := fmt.Sprintf("Council: %s", cleanSocraticTitle(digest.InitialHypothesis, 45))

	newDisc := model.Discussion{
		ID:        newID,
		ProjectID: disc.ProjectID,
		Name:      title,
		Mode:      model.DiscussionModeCouncil,
		Status:    model.DiscussionDraft,
		Config: model.DebateConfig{
			Topic:         digest.InitialHypothesis,
			CommonContext: agendaBuilder.String(),
			Primary:       disc.Config.Primary,
			AttachedFiles: disc.AttachedFiles,
		},
		AttachedFolders: disc.AttachedFolders,
		CreatedAt:       time.Now().Unix(),
		UpdatedAt:       time.Now().Unix(),
	}

	state.Discussions = append(state.Discussions, newDisc)
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save state: "+err.Error())
		return
	}

	result := socratic.ElevateResult{
		NewDiscussionID: newID,
		ProjectID:       newDisc.ProjectID,
		Topic:           newDisc.Config.Topic,
	}

	writeJSON(w, http.StatusOK, result)
}

func cleanSocraticTitle(s string, maxLen int) string {
	cleaned := strings.Join(strings.Fields(s), " ")
	if len(cleaned) <= maxLen {
		return cleaned
	}
	return cleaned[:maxLen] + "..."
}

