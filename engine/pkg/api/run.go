package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"dialex/pkg/model"
	"dialex/pkg/orchestrator"
	"dialex/pkg/runner"
)

// handleStartDebate begins a fresh run — resets the transcript to empty first, same as the
// desktop app's "Restart with these changes" always did (as opposed to /resume, which
// continues from wherever the transcript left off).
func (s *Server) handleStartDebate(w http.ResponseWriter, r *http.Request) {
	s.runDiscussion(w, r, true)
}

// handleResumeDebate continues a PAUSED or ERROR discussion from its last successful turn.
func (s *Server) handleResumeDebate(w http.ResponseWriter, r *http.Request) {
	s.runDiscussion(w, r, false)
}

func (s *Server) runDiscussion(w http.ResponseWriter, r *http.Request, resetTranscript bool) {
	id := r.PathValue("id")
	discussion, state, err := s.findDiscussion(id)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	// Validate attached folders and parent project workspace folders before running
	var projectFolders []model.FolderScope
	for _, p := range state.Projects {
		if p.ID == discussion.ProjectID {
			projectFolders = p.WorkspaceScope.Folders
			break
		}
	}
	allFolders := append([]model.FolderScope{}, discussion.AttachedFolders...)
	allFolders = append(allFolders, projectFolders...)

	for _, f := range allFolders {
		trimmed := strings.TrimSpace(f.Path)
		if trimmed == "" {
			continue
		}
		info, statErr := os.Stat(trimmed)
		if statErr != nil {
			writeError(w, http.StatusUnprocessableEntity, fmt.Sprintf("workspace folder '%s' was removed or is invalid; processing blocked until resolved", trimmed))
			return
		}
		if !info.IsDir() {
			writeError(w, http.StatusUnprocessableEntity, fmt.Sprintf("workspace path '%s' is not a directory; processing blocked until resolved", trimmed))
			return
		}
	}

	s.mu.Lock()
	if _, running := s.runs[id]; running {
		s.mu.Unlock()
		writeError(w, http.StatusConflict, "this discussion is already running")
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	controller := newRunController(cancel)
	s.runs[id] = controller
	s.mu.Unlock()

	if resetTranscript {
		discussion.Transcript = nil
		discussion.Conclusion = nil
		discussion.Deliverable = nil
		discussion.Summary = nil
	} else {
		// Dropping trailing error entries mirrors the desktop app's resume(): they're not
		// fed back to the agents as context and shouldn't skew the resume point.
		discussion.Transcript = dropTrailingErrors(discussion.Transcript)

		// Before clearing live conclusion/summary fields, snapshot them into discussion.Artifacts
		// as milestone checkpoint artifacts so previous checkpoints are never lost!
		if discussion.Summary != nil || discussion.Conclusion != nil || discussion.Deliverable != nil {
			maxRound := 1
			for _, m := range discussion.Transcript {
				if !m.IsUserComment && !m.IsError && m.Round > maxRound {
					maxRound = m.Round
				}
			}
			sumContent := ""
			if discussion.Summary != nil && *discussion.Summary != "" {
				sumContent = *discussion.Summary
			} else if discussion.Conclusion != nil && *discussion.Conclusion != "" {
				sumContent = *discussion.Conclusion
			} else if discussion.Deliverable != nil && *discussion.Deliverable != "" {
				sumContent = *discussion.Deliverable
			}

			if sumContent != "" {
				artID := fmt.Sprintf("art_sum_r%d", maxRound)
				hasArt := false
				for _, a := range discussion.Artifacts {
					if a.ID == artID {
						hasArt = true
						break
					}
				}
				if !hasArt {
					discussion.Artifacts = append(discussion.Artifacts, model.DiscussionArtifact{
						ID:        artID,
						Name:      fmt.Sprintf("Discussion Summary (Round %d)", maxRound),
						Type:      "Summary",
						Format:    "md",
						Content:   sumContent,
						Timestamp: fmt.Sprintf("Round %d", maxRound),
						SizeBytes: int64(len(sumContent)),
						Round:     &maxRound,
					})
				}
			}
		}

		discussion.Conclusion = nil
		discussion.Deliverable = nil
		discussion.Summary = nil

		if discussion.Config.RoundMode == model.RoundModeFixed && len(discussion.Config.Agents()) > 0 {
			agentCount := len(discussion.Config.Agents())
			maxRound := 1
			for _, m := range discussion.Transcript {
				if !m.IsUserComment && !m.IsError && m.Round > maxRound {
					maxRound = m.Round
				}
			}
			lastRoundTurns := 0
			for _, m := range discussion.Transcript {
				if !m.IsUserComment && !m.IsError && m.Round == maxRound {
					lastRoundTurns++
				}
			}
			targetRound := maxRound
			if lastRoundTurns >= agentCount {
				targetRound = maxRound + 1
			}
			if targetRound > discussion.Config.MaxRounds {
				discussion.Config.MaxRounds = targetRound + 2
			}
		}
	}
	discussion.Status = model.DiscussionRunning
	if err := s.updateDiscussion(state, discussion); err != nil {
		s.finishRun(id)
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	go s.executeRun(ctx, controller, id, discussion)

	writeJSON(w, http.StatusAccepted, map[string]string{"status": "running"})
}

func dropTrailingErrors(transcript []model.DebateMessage) []model.DebateMessage {
	end := len(transcript)
	for end > 0 && transcript[end-1].IsError {
		end--
	}
	return transcript[:end]
}

// generateDiscussionTitle uses the compaction model to summarize a topic into a concise, punchy 1-line title.
func (s *Server) generateDiscussionTitle(ctx context.Context, d *model.Discussion, compactionModel string) string {
	topic := strings.TrimSpace(d.Config.Topic)
	if topic == "" {
		return ""
	}
	if compactionModel == "" {
		compactionModel = model.DefaultCompactionModel
	}
	compactionAgent := d.Config.Primary
	compactionAgent.SystemPrompt = "You are a concise discussion title generator. Output ONLY a single short, punchy, descriptive title (3 to 7 words maximum, single line, no quotes, no markdown, no trailing punctuation)."

	runner := s.runnerForAgent(compactionAgent)
	if runner == nil {
		return ""
	}

	prompt := fmt.Sprintf("Generate a concise 1-line summary title (3-7 words, single line, no quotes or markdown) for this discussion topic:\n\n%s", topic)
	resp, err := runner.Respond(ctx, compactionAgent, prompt, "", "", nil, compactionModel)
	if err != nil {
		return ""
	}

	title := strings.TrimSpace(resp.Content)
	title = strings.Trim(title, "\"`*# \t\r\n")
	title = strings.TrimPrefix(title, "Title:")
	title = strings.TrimPrefix(title, "title:")
	title = strings.TrimPrefix(title, "Subject:")
	title = strings.TrimSpace(title)
	if idx := strings.IndexAny(title, "\r\n"); idx != -1 {
		title = strings.TrimSpace(title[:idx])
	}
	if len(title) > 70 {
		title = title[:70]
	}
	return title
}

// executeRun runs the orchestrator in the background, persisting after every turn (Task
// 3.2.2 — atomic per-turn persistence, so an engine restart mid-turn loses at most the
// in-flight turn, never the ones already completed) and broadcasting each turn to any live
// SSE subscribers.
func (s *Server) executeRun(ctx context.Context, controller *runController, id string, discussion model.Discussion) {
	defer s.finishRun(id)
	defer controller.markDone()

	state, err := s.Store.Load()
	if err != nil {
		return
	}
	compactionModel := state.CompactionModel
	tokenBudget := state.TokenBudget

	// Auto-generate a clean summary title using the compaction model if the name is a default/placeholder or matches raw topic
	if discussion.Name == "" || discussion.Name == "New Discussion" || discussion.Name == "Dialex Debate" || strings.TrimSpace(discussion.Name) == strings.TrimSpace(discussion.Config.Topic) {
		go func(discID string, dCopy model.Discussion, compModel string) {
			ctxTitle, cancel := context.WithTimeout(context.Background(), 15*time.Second)
			defer cancel()
			newTitle := s.generateDiscussionTitle(ctxTitle, &dCopy, compModel)
			if newTitle != "" {
				st, err := s.Store.Load()
				if err == nil {
					for i := range st.Discussions {
						if st.Discussions[i].ID == discID {
							st.Discussions[i].Name = newTitle
							_ = s.Store.Save(st)
							break
						}
					}
				}
			}
		}(id, discussion, compactionModel)
	}

	onMessage := func(msg model.DebateMessage) {
		st, err := s.Store.Load()
		if err == nil {
			for i, d := range st.Discussions {
				if d.ID == id {
					alreadyPresent := false
					for _, existing := range st.Discussions[i].Transcript {
						if existing.Round == msg.Round && existing.AgentID == msg.AgentID && existing.Content == msg.Content {
							alreadyPresent = true
							break
						}
					}
					if !alreadyPresent {
						st.Discussions[i].Transcript = append(st.Discussions[i].Transcript, msg)
					}
					st.Discussions[i].Status = model.DiscussionRunning
					discussion.Transcript = st.Discussions[i].Transcript
					discussion.Name = d.Name
					discussion.ProjectID = d.ProjectID
					_ = s.Store.Save(st)
					break
				}
			}
		}
		controller.broadcast(msg)
	}

	orch := &orchestrator.Orchestrator{
		RunnerFor: func(agent model.Agent) runner.AgentRunner {
			return s.runnerForAgentWithContext(agent, discussion.AttachedFolders, discussion.Config.Permissions)
		},
	}

	result, runErr := orch.Run(ctx, orchestrator.RunOptions{
		Config:            discussion.Config,
		InitialTranscript: discussion.Transcript,
		IsStopped:         controller.isPaused,
		GetInjected:       controller.drainInjected,
		CompactionModel:   compactionModel,
		TokenBudget:       tokenBudget,
		OnMessage:         onMessage,
		AttachedFolders:   discussion.AttachedFolders,
		Permissions:       discussion.Config.Permissions,
	})

	discussion.Transcript = result.Transcript
	discussion.Conclusion = result.Conclusion
	discussion.Warning = result.Warning
	if result.Conclusion != nil {
		discussion.Summary = result.Conclusion
	}
	switch {
	case runErr != nil:
		discussion.Status = model.DiscussionPaused // hard stop — resumable, not an error
	case result.Error != nil:
		discussion.Status = model.DiscussionFailed
	case result.Warning != nil:
		discussion.Status = model.DiscussionCompletedWithWarning
	case result.Paused:
		discussion.Status = model.DiscussionPaused
	default:
		discussion.Status = model.DiscussionCompleted
	}
	if st, err := s.Store.Load(); err == nil {
		for i, d := range st.Discussions {
			if d.ID == id {
				discussion.Name = d.Name
				discussion.ProjectID = d.ProjectID
				if len(discussion.Artifacts) == 0 && len(d.Artifacts) > 0 {
					discussion.Artifacts = d.Artifacts
				}
				if len(discussion.DismissedArtifactIds) == 0 && len(d.DismissedArtifactIds) > 0 {
					discussion.DismissedArtifactIds = d.DismissedArtifactIds
				}
				// Merge any user comments that were stored in st.Discussions[i]
				for _, storedMsg := range d.Transcript {
					if storedMsg.IsUserComment {
						found := false
						for _, dm := range discussion.Transcript {
							if dm.IsUserComment && dm.Content == storedMsg.Content && dm.TimestampMs == storedMsg.TimestampMs {
								found = true
								break
							}
						}
						if !found {
							discussion.Transcript = append(discussion.Transcript, storedMsg)
						}
					}
				}
				st.Discussions[i] = discussion
				_ = s.Store.Save(st)
				break
			}
		}
	}
}

func (s *Server) finishRun(id string) {
	s.mu.Lock()
	delete(s.runs, id)
	s.mu.Unlock()
}

// handleHardStop kills the in-flight turn immediately (Task 3.2.1 — context cancellation
// propagates to the CLI subprocess as SIGKILL / aborts the in-flight HTTP call) instead of
// waiting for it to finish like /pause does.
func (s *Server) handleHardStop(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	controller, running := s.runs[id]
	s.mu.Unlock()
	if !running {
		writeError(w, http.StatusConflict, "this discussion isn't running")
		return
	}
	controller.cancel()
	writeJSON(w, http.StatusOK, map[string]string{"status": "stopping"})
}

// handlePause finishes the current turn, then stops — resumable via /resume.
func (s *Server) handlePause(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	controller, running := s.runs[id]
	s.mu.Unlock()
	if !running {
		writeError(w, http.StatusConflict, "this discussion isn't running")
		return
	}
	controller.setPaused(true)
	writeJSON(w, http.StatusOK, map[string]string{"status": "pausing"})
}

// handleStream is the SSE feed for one discussion — one event per turn as it completes,
// live only while a run is actually in progress in this process. A discussion that isn't
// currently running gets its last known state as a single event, then the stream closes.
func (s *Server) handleStream(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	flusher, ok := w.(http.Flusher)
	if !ok {
		writeError(w, http.StatusInternalServerError, "streaming unsupported")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")

	s.mu.Lock()
	controller, running := s.runs[id]
	s.mu.Unlock()

	if !running {
		discussion, _, err := s.findDiscussion(id)
		if err != nil {
			writeError(w, http.StatusNotFound, err.Error())
			return
		}
		writeSSE(w, "snapshot", discussion)
		flusher.Flush()
		return
	}

	ch := controller.subscribe()
	defer controller.unsubscribe(ch)

	// Emit initial live snapshot to the subscriber immediately upon connecting
	if initialDisc, _, err := s.findDiscussion(id); err == nil {
		writeSSE(w, "snapshot", initialDisc)
		flusher.Flush()
	}

	for {
		select {
		case <-r.Context().Done():
			return
		case <-controller.done:
			// The run finished — drain whatever turns already landed in this
			// subscriber's buffer, then emit the final state snapshot before closing
			for {
				select {
				case <-ch:
					disc, _, err := s.findDiscussion(id)
					if err == nil {
						writeSSE(w, "turn", disc)
						flusher.Flush()
					}
				default:
					if finalDisc, _, err := s.findDiscussion(id); err == nil {
						writeSSE(w, "snapshot", finalDisc)
						flusher.Flush()
					}
					return
				}
			}
		case _, ok := <-ch:
			if !ok {
				return
			}
			disc, _, err := s.findDiscussion(id)
			if err == nil {
				writeSSE(w, "turn", disc)
				flusher.Flush()
			}
		}
	}
}

func writeSSE(w http.ResponseWriter, event string, v any) {
	data, err := json.Marshal(v)
	if err != nil {
		return
	}
	fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, data)
}

type handoffResponse struct {
	Prompt string `json:"prompt"`
}

// handleHandoff asks the primary agent to turn the discussion so far into a self-contained
// prompt another AI (with zero prior context) could pick up cold — a one-off side call that
// doesn't touch the discussion's transcript/status, so it's safe regardless of Paused/Done/
// Error. Ported from the Kotlin app's AppViewModel.generateHandoffPrompt, word for word on
// the instruction text so the two behave identically. Persists the result onto
// Discussion.HandoffPrompt, same as the Kotlin side, so it survives navigating away/back.
func (s *Server) handleHandoff(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	discussion, state, err := s.findDiscussion(id)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	primary := discussion.Config.Primary
	instruction := "Summarize this debate into a comprehensive, ready-to-paste prompt for a " +
		"DIFFERENT AI assistant that has no prior context at all. Include: the topic, essential " +
		"background/context, each participant's key positions and strongest arguments, how the " +
		"discussion evolved, and the conclusion (or, if unresolved, the open questions). Write it " +
		"as the prompt itself — something to hand directly to another AI to bring it fully up to " +
		"speed — not commentary about the summary."
	primary.SystemPrompt = instruction

	reply, err := s.runnerForAgent(primary).Respond(r.Context(), primary, discussion.Config.Topic, discussion.Config.CommonContext, "", discussion.Transcript, "")
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}

	discussion.HandoffPrompt = &reply.Content
	if err := s.updateDiscussion(state, discussion); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, handoffResponse{Prompt: reply.Content})
}

type deliverableRequest struct {
	Format string `json:"format"`
}

type deliverableResponse struct {
	Content string `json:"content"`
}

// handleDeliverable synthesizes the discussion transcript into a specialized deliverable format
// (such as Action Plan, Decision Matrix, Pro/Con List, Executive Brief, Decision Summary).
func (s *Server) handleDeliverable(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	discussion, _, err := s.findDiscussion(id)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}

	var req deliverableRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	var instruction string
	switch strings.ToLower(req.Format) {
	case "action_plan":
		instruction = "Generate an actionable Action Plan based on this debate. Include: \n\n" +
			"### 1. Immediate Next Steps (Days 1-14)\nNumbered action items with suggested owners.\n\n" +
			"### 2. Strategic Milestones\nKey deliverables and checkpoints.\n\n" +
			"### 3. Risk Mitigation Table\nSpecific mitigations for identified risks."
	case "decision_matrix":
		instruction = "Generate a structured Decision Matrix evaluating the options discussed in this debate in Markdown table format:\n\n" +
			"| Option | Pros | Cons | Feasibility (1-5) | Impact (1-5) | Recommended? |\n" +
			"| :--- | :--- | :--- | :--- | :--- | :--- |\n\n" +
			"Followed by a concise synthesis explaining the optimal recommendation."
	case "pro_con_list":
		instruction = "Generate a comprehensive Pro / Con List summarizing the debate:\n\n" +
			"### Arguments in Favor (Pros)\n- Key advantages and evidence presented.\n\n" +
			"### Arguments Against (Cons)\n- Key drawbacks and counter-arguments.\n\n" +
			"### Balanced Takeaways\n- Critical trade-offs to consider."
	case "executive_brief", "brief":
		instruction = "Generate a 1-page Executive Brief for leadership based on this debate. Include:\n\n" +
			"### Executive Summary\n### Core Dilemma & Options Evaluated\n### Consensus Recommendation\n### Next Steps & Timeline"
	case "decision_summary", "summary":
		instruction = "Generate a concise Decision Summary of this debate. Include: \n\n" +
			"### 1. Core Decision\nThe final conclusion agreed upon.\n\n" +
			"### 2. Key Rationale\nSupporting arguments and consensus points.\n\n" +
			"### 3. Open Questions & Risks\nRemaining uncertainties."
	default:
		instruction = "Synthesize this debate into a structured deliverable format (" + req.Format + "). Be thorough, objective, and directly actionable."
	}

	primary := discussion.Config.Primary
	agent := primary
	agent.SystemPrompt = instruction

	reply, err := s.runnerForAgent(agent).Respond(r.Context(), agent, discussion.Config.Topic, discussion.Config.CommonContext, "", discussion.Transcript, "")
	if err != nil {
		writeError(w, http.StatusBadGateway, err.Error())
		return
	}

	content := reply.Content
	discussion.Deliverable = &content

	var label string
	switch strings.ToLower(req.Format) {
	case "action_plan":
		label = "Action Plan Deliverable"
	case "decision_matrix":
		label = "Decision Matrix Deliverable"
	case "pro_con_list":
		label = "Pro/Con List Deliverable"
	case "executive_brief", "brief":
		label = "Executive Brief Deliverable"
	case "decision_summary", "summary":
		label = "Decision Summary Deliverable"
	default:
		label = strings.ToUpper(req.Format[:1]) + strings.ToLower(req.Format[1:]) + " Deliverable"
	}

	artID := strings.ToLower(req.Format) + "_" + strconv.FormatInt(time.Now().UnixMilli(), 10)
	art := model.DiscussionArtifact{
		ID:        artID,
		Name:      label,
		Type:      "Deliverable",
		Format:    "md",
		Content:   content,
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		SizeBytes: int64(len(content)),
	}

	filtered := make([]model.DiscussionArtifact, 0, len(discussion.Artifacts)+1)
	for _, a := range discussion.Artifacts {
		if a.Name != label {
			filtered = append(filtered, a)
		}
	}
	discussion.Artifacts = append(filtered, art)

	freshState, err := s.Store.Load()
	if err == nil {
		_ = s.updateDiscussion(freshState, discussion)
	}

	writeJSON(w, http.StatusOK, deliverableResponse{Content: reply.Content})
}

