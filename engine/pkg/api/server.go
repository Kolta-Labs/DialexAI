// Package api is the Go port target for the engine's REST + SSE surface (Task 1.5). Plain
// net/http (Go 1.22+ ServeMux method+path patterns) — no router framework dependency for
// a route table this size.
package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"dialex/pkg/graph"
	"dialex/pkg/model"
	"dialex/pkg/orchestrator"
	"dialex/pkg/runner"
	"dialex/pkg/store"
)

// Server holds everything a request handler needs — the persisted state, the orchestrator,
// and in-memory bookkeeping for whichever debates are actively running in this process
// (control flags, live SSE subscribers). A restart loses that in-memory bookkeeping, same
// as the desktop app's own runningJobs map always did — a RUNNING discussion found on
// startup is sanitized to PAUSED, resumable normally.
type Server struct {
	Store        *store.Store
	GraphStore   graph.GraphStore
	Orchestrator *orchestrator.Orchestrator
	RunnerFor    func(model.Agent) runner.AgentRunner
	jwtSecret    []byte
	startTime    time.Time

	mu   sync.Mutex
	runs map[string]*runController
}

// NewServer wires a Server around a store — RunnerFor decides API vs CLI per agent, reading
// keys/commands from the store's own settings at run time (so a Settings change takes
// effect on the next turn without a restart).
func NewServer(st *store.Store) *Server {
	secret := randomSecret()
	if st != nil {
		if persistentSecret, err := st.JWTSecret(); err == nil && len(persistentSecret) == 32 {
			secret = persistentSecret
		}
	}
	s := &Server{
		Store:     st,
		jwtSecret: secret,
		startTime: time.Now().UTC(),
		runs:      make(map[string]*runController),
	}
	if st != nil && st.Dir() != "" {
		if gs, err := graph.OpenSQLite(filepath.Join(st.Dir(), "graph.db")); err == nil {
			s.GraphStore = gs
		}
	}
	s.Orchestrator = &orchestrator.Orchestrator{RunnerFor: func(agent model.Agent) runner.AgentRunner {
		return s.runnerForAgent(agent)
	}}
	sanitizeStaleRunningStatus(st)
	return s
}

// sanitizeStaleRunningStatus runs once at process start, before any request is served — at
// this point s.runs is always empty, so *any* discussion still marked RUNNING on disk cannot
// actually have a live run behind it (that bookkeeping is in-memory only and doesn't survive
// a restart/crash). Left as RUNNING, a client would show it as live and in progress forever,
// with no way to Pause/Hard Stop it (there's nothing there to stop) and Resume refusing to
// run because it "looks" already running. PAUSED is exactly what it actually is: stopped,
// resumable from its last successful turn.
func sanitizeStaleRunningStatus(st *store.Store) {
	state, err := st.Load()
	if err != nil {
		return
	}
	changed := false
	for i := range state.Discussions {
		if state.Discussions[i].Status == model.DiscussionRunning {
			state.Discussions[i].Status = model.DiscussionPaused
			changed = true
		}
	}
	if changed {
		_ = st.Save(state)
	}
}

func (s *Server) runnerForAgent(agent model.Agent) runner.AgentRunner {
	return s.runnerForAgentWithContext(agent, nil, nil)
}

func (s *Server) runnerForAgentWithContext(agent model.Agent, folders []model.FolderScope, perms *model.PermissionConfig) runner.AgentRunner {
	if s.RunnerFor != nil {
		return s.RunnerFor(agent)
	}
	state, err := s.Store.Load()
	if err != nil {
		state = model.NewAppState()
	}
	if agent.RunMode == model.RunModeCLI {
		return runner.NewCliAgentRunnerWithContext(state.CliCommands, folders, perms)
	}
	return runner.NewApiAgentRunnerWithPermissions(state.ApiKeys.AsMap(), perms)
}

// runController is the live, in-memory control surface for one discussion's active run —
// nothing here is persisted; it only exists while a goroutine is actually executing
// Orchestrator.Run for that discussion.
type runController struct {
	cancel context.CancelFunc
	// done is closed once the run's goroutine returns — the SSE handler watches it to know
	// when to stop waiting on this controller's subscriber channels and end the stream.
	// Without this, a stream outlives the run it was watching and hangs forever (caught by
	// EngineClientIntegrationTest actually finishing a debate over real SSE, not a unit
	// test with a mocked transport).
	done chan struct{}

	mu       sync.Mutex
	paused   bool
	subs     map[chan model.DebateMessage]struct{}
	injected []model.DebateMessage
}

func newRunController(cancel context.CancelFunc) *runController {
	return &runController{
		cancel:   cancel,
		done:     make(chan struct{}),
		subs:     make(map[chan model.DebateMessage]struct{}),
		injected: make([]model.DebateMessage, 0),
	}
}

func (c *runController) markDone() {
	close(c.done)
}

func (c *runController) isPaused() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.paused
}

func (c *runController) setPaused(p bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.paused = p
}

func (c *runController) injectComment(msg model.DebateMessage) {
	c.mu.Lock()
	c.injected = append(c.injected, msg)
	c.mu.Unlock()
	c.broadcast(msg)
}

func (c *runController) drainInjected() []model.DebateMessage {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.injected) == 0 {
		return nil
	}
	msgs := c.injected
	c.injected = make([]model.DebateMessage, 0)
	return msgs
}

func (c *runController) broadcast(msg model.DebateMessage) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for ch := range c.subs {
		select {
		case ch <- msg:
		default: // a slow/gone subscriber never blocks the debate itself
		}
	}
}

func (c *runController) subscribe() chan model.DebateMessage {
	ch := make(chan model.DebateMessage, 16)
	c.mu.Lock()
	c.subs[ch] = struct{}{}
	c.mu.Unlock()
	return ch
}

func (c *runController) unsubscribe(ch chan model.DebateMessage) {
	c.mu.Lock()
	delete(c.subs, ch)
	c.mu.Unlock()
}

// --- small JSON helpers shared by every handler ---

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

type errorBody struct {
	Error string `json:"error"`
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, errorBody{Error: message})
}

// --- context plumbing for the authenticated username ---

type contextKey int

const usernameKey contextKey = iota

func withUsername(ctx context.Context, username string) context.Context {
	return context.WithValue(ctx, usernameKey, username)
}

// --- health ---

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok",
		"time":   time.Now().UTC().Format(time.RFC3339),
	})
}

// --- projects ---

func (s *Server) handleListProjects(w http.ResponseWriter, r *http.Request) {
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, state.Projects)
}

func (s *Server) handleCreateProject(w http.ResponseWriter, r *http.Request) {
	var req model.Project
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Name == "" {
		writeError(w, http.StatusBadRequest, "project name is required")
		return
	}
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if req.ID == "" {
		req.ID = newID()
	}
	state.Projects = append(state.Projects, req)
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, req)
}

// handleDeleteProject also removes every discussion inside the project — nothing is left
// orphaned, same as the Kotlin app's AppViewModel.deleteProject.
func (s *Server) handleDeleteProject(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	keptProjects := state.Projects[:0]
	for _, p := range state.Projects {
		if p.ID != id {
			keptProjects = append(keptProjects, p)
		}
	}
	state.Projects = keptProjects
	keptDiscussions := state.Discussions[:0]
	for _, d := range state.Discussions {
		if d.ProjectID != id {
			keptDiscussions = append(keptDiscussions, d)
		}
	}
	state.Discussions = keptDiscussions
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- debates (== discussions) ---

func (s *Server) handleListDebates(w http.ResponseWriter, r *http.Request) {
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, state.Discussions)
}

func (s *Server) handleCreateDebate(w http.ResponseWriter, r *http.Request) {
	var req model.Discussion
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if req.ID == "" {
		req.ID = newID()
	}
	if req.Status == "" {
		req.Status = model.DiscussionDraft
	}
	now := time.Now().UnixMilli()
	if req.CreatedAt == 0 {
		req.CreatedAt = now
	}
	req.UpdatedAt = now
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	state.Discussions = append(state.Discussions, req)
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, req)
}

func (s *Server) handleGetDebate(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	discussion, _, err := s.findDiscussion(id)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, discussion)
}

// handleUpdateDebate updates a discussion — used for config edits on a DRAFT (or
// "Edit setup" on an existing one), or metadata updates (like renaming or moving to a
// project) while in-flight. If the discussion is actively running in this process, it
// applies metadata changes without racing or corrupting the running orchestrator loop.
func (s *Server) handleUpdateDebate(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	_, running := s.runs[id]
	s.mu.Unlock()
	var req model.Discussion
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	req.ID = id // the path is authoritative, not whatever the body claims
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if running {
		// When running, allow updating metadata (Name, ProjectID) without interrupting the run.
		var existing model.Discussion
		found := false
		for _, d := range state.Discussions {
			if d.ID == id {
				existing = d
				found = true
				break
			}
		}
		if !found {
			writeError(w, http.StatusNotFound, fmt.Sprintf("no discussion with id %q", id))
			return
		}
		if req.Name != "" {
			existing.Name = req.Name
		}
		if req.ProjectID != "" {
			existing.ProjectID = req.ProjectID
		}
		if len(req.Artifacts) > 0 {
			existing.Artifacts = req.Artifacts
		}
		if len(req.DismissedArtifactIds) > 0 {
			existing.DismissedArtifactIds = req.DismissedArtifactIds
		}

		s.mu.Lock()
		ctrl := s.runs[id]
		s.mu.Unlock()

		if ctrl != nil {
			for _, m := range req.Transcript {
				if m.IsUserComment {
					alreadyPresent := false
					for _, em := range existing.Transcript {
						if em.IsUserComment && em.Content == m.Content && em.TimestampMs == m.TimestampMs {
							alreadyPresent = true
							break
						}
					}
					if !alreadyPresent {
						ctrl.injectComment(m)
						existing.Transcript = append(existing.Transcript, m)
					}
				}
			}
		}

		if err := s.updateDiscussion(state, existing); err != nil {
			writeError(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, existing)
		return
	}
	if err := s.updateDiscussion(state, req); err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, req)
}

func (s *Server) handleDeleteDebate(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	s.mu.Lock()
	_, running := s.runs[id]
	s.mu.Unlock()
	if running {
		writeError(w, http.StatusConflict, "this discussion is running — pause or stop it before deleting")
		return
	}
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	kept := state.Discussions[:0]
	for _, d := range state.Discussions {
		if d.ID != id {
			kept = append(kept, d)
		}
	}
	state.Discussions = kept
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) findDiscussion(id string) (model.Discussion, model.AppState, error) {
	state, err := s.Store.Load()
	if err != nil {
		return model.Discussion{}, state, err
	}
	for _, d := range state.Discussions {
		if d.ID == id {
			return d, state, nil
		}
	}
	return model.Discussion{}, state, fmt.Errorf("no discussion with id %q", id)
}

func (s *Server) updateDiscussion(state model.AppState, updated model.Discussion) error {
	for i, d := range state.Discussions {
		if d.ID == updated.ID {
			if updated.CreatedAt == 0 {
				updated.CreatedAt = d.CreatedAt
			}
			updated.UpdatedAt = time.Now().UnixMilli()
			state.Discussions[i] = updated
			return s.Store.Save(state)
		}
	}
	return fmt.Errorf("no discussion with id %q", updated.ID)
}

func (s *Server) handleGenerateDebateTitle(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	discussion, state, err := s.findDiscussion(id)
	if err != nil {
		writeError(w, http.StatusNotFound, err.Error())
		return
	}
	compactionModel := state.CompactionModel
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()

	newTitle := s.generateDiscussionTitle(ctx, &discussion, compactionModel)
	if newTitle == "" {
		writeError(w, http.StatusInternalServerError, "failed to generate summary title using compaction model")
		return
	}

	discussion.Name = newTitle
	if err := s.updateDiscussion(state, discussion); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"title": newTitle})
}

// --- settings ---

func (s *Server) handleGetSettings(w http.ResponseWriter, r *http.Request) {
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	// Keys never round-trip back out once set — a settings read shows whether each is
	// configured, not the value itself.
	writeJSON(w, http.StatusOK, map[string]any{
		"apiKeysConfigured": map[string]bool{
			"anthropic": state.ApiKeys.Anthropic != "",
			"openai":    state.ApiKeys.OpenAI != "",
			"gemini":    state.ApiKeys.Gemini != "",
			"grok":      state.ApiKeys.Grok != "",
			"deepseek":  state.ApiKeys.DeepSeek != "",
			"mistral":   state.ApiKeys.Mistral != "",
		},
		"cliCommands":     state.CliCommands,
		"compactionModel": state.CompactionModel,
		"tokenBudget":     state.TokenBudget,
		"agentLimit":      state.AgentLimit,
	})
}

type settingsUpdateRequest struct {
	ApiKeys         *model.ApiKeys     `json:"apiKeys,omitempty"`
	CliCommands     *model.CliCommands `json:"cliCommands,omitempty"`
	CompactionModel *string            `json:"compactionModel,omitempty"`
	TokenBudget     *int               `json:"tokenBudget,omitempty"`
	AgentLimit      *int               `json:"agentLimit,omitempty"`
}

func (s *Server) handleUpdateSettings(w http.ResponseWriter, r *http.Request) {
	var req settingsUpdateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	if req.ApiKeys != nil {
		state.ApiKeys = *req.ApiKeys
	}
	if req.CliCommands != nil {
		state.CliCommands = *req.CliCommands
	}
	if req.CompactionModel != nil {
		state.CompactionModel = *req.CompactionModel
	}
	if req.TokenBudget != nil {
		state.TokenBudget = *req.TokenBudget
	}
	if req.AgentLimit != nil {
		state.AgentLimit = *req.AgentLimit
	}
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func newID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%x", b)
}

func (s *Server) handleCliStatus(w http.ResponseWriter, r *http.Request) {
	status := make(map[string]bool)

	claudePath, err1 := runner.ResolveBinary("claude")
	claudeAvail := err1 == nil && claudePath != ""
	status["Claude Code"] = claudeAvail
	status["Claude"] = claudeAvail
	status["claude"] = claudeAvail
	status["ANTHROPIC"] = claudeAvail

	codexPath, err2 := runner.ResolveBinary("codex")
	codexAvail := err2 == nil && codexPath != ""
	status["Codex (OpenAI)"] = codexAvail
	status["Codex"] = codexAvail
	status["codex"] = codexAvail
	status["ChatGPT"] = codexAvail
	status["OPENAI"] = codexAvail

	agyPath, err3 := runner.ResolveBinary("agy")
	antiPath, err4 := runner.ResolveBinary("antigravity")
	gemPath, err5 := runner.ResolveBinary("gemini")
	gemAvail := (err3 == nil && agyPath != "") || (err4 == nil && antiPath != "") || (err5 == nil && gemPath != "")
	status["Antigravity (Gemini)"] = gemAvail
	status["Antigravity"] = gemAvail
	status["Gemini"] = gemAvail
	status["agy"] = gemAvail
	status["antigravity"] = gemAvail
	status["gemini"] = gemAvail
	status["GEMINI"] = gemAvail

	state, err := s.Store.Load()
	if err == nil {
		for _, p := range []model.Provider{
			model.ProviderAnthropic, model.ProviderOpenAI, model.ProviderGemini,
			model.ProviderGrok, model.ProviderDeepSeek, model.ProviderMistral, model.ProviderCustom,
		} {
			cmd := state.CliCommands.ForProvider(p)
			fields := strings.Fields(cmd)
			if len(fields) > 0 {
				binPath, bErr := runner.ResolveBinary(fields[0])
				avail := bErr == nil && binPath != ""
				// Only set if true or if this provider wasn't already detected as available
				if avail || !status[string(p)] {
					status[string(p)] = avail
					status[fields[0]] = avail
				}
			}
		}
	}

	writeJSON(w, http.StatusOK, status)
}

func (s *Server) handleCliLogins(w http.ResponseWriter, r *http.Request) {
	logins := checkCliLogins()
	writeJSON(w, http.StatusOK, logins)
}

func checkCliLogins() map[string]bool {
	home, _ := os.UserHomeDir()
	logins := make(map[string]bool)

	claudeLoggedIn := false
	if home != "" {
		jsonPath := filepath.Join(home, ".claude.json")
		if data, err := os.ReadFile(jsonPath); err == nil {
			str := string(data)
			if strings.Contains(str, "oauthAccount") ||
				strings.Contains(str, "hasCompletedOnboarding") ||
				strings.Contains(str, "sessionKey") ||
				strings.Contains(str, "accountUuid") {
				claudeLoggedIn = true
			}
		}
		if !claudeLoggedIn {
			claudeDir := filepath.Join(home, ".claude")
			if entries, err := os.ReadDir(claudeDir); err == nil && len(entries) > 0 {
				claudeLoggedIn = true
			}
		}
		if !claudeLoggedIn {
			configDir := filepath.Join(home, ".config", "claude")
			if entries, err := os.ReadDir(configDir); err == nil && len(entries) > 0 {
				claudeLoggedIn = true
			}
		}
	}
	if os.Getenv("ANTHROPIC_API_KEY") != "" {
		claudeLoggedIn = true
	}

	geminiLoggedIn := false
	if home != "" {
		geminiDir := filepath.Join(home, ".gemini")
		antigravityDir := filepath.Join(home, ".antigravity")
		if _, err := os.Stat(filepath.Join(geminiDir, "jetski-standalone-oauth-token")); err == nil {
			geminiLoggedIn = true
		} else if _, err := os.Stat(filepath.Join(geminiDir, "oauth_credentials.json")); err == nil {
			geminiLoggedIn = true
		} else if entries, err := os.ReadDir(antigravityDir); err == nil && len(entries) > 0 {
			geminiLoggedIn = true
		}
	}
	if os.Getenv("GEMINI_API_KEY") != "" {
		geminiLoggedIn = true
	}

	codexLoggedIn := false
	if home != "" {
		codexDir := filepath.Join(home, ".codex")
		chatgptDir := filepath.Join(home, ".chatgpt")
		if entries, err := os.ReadDir(codexDir); err == nil && len(entries) > 0 {
			codexLoggedIn = true
		} else if entries, err := os.ReadDir(chatgptDir); err == nil && len(entries) > 0 {
			codexLoggedIn = true
		}
	}
	if os.Getenv("OPENAI_API_KEY") != "" {
		codexLoggedIn = true
	}

	logins["Claude Code"] = claudeLoggedIn
	logins["Claude"] = claudeLoggedIn
	logins["claude"] = claudeLoggedIn
	logins["ANTHROPIC"] = claudeLoggedIn
	logins["Codex (OpenAI)"] = codexLoggedIn
	logins["Codex"] = codexLoggedIn
	logins["codex"] = codexLoggedIn
	logins["ChatGPT"] = codexLoggedIn
	logins["OPENAI"] = codexLoggedIn
	logins["Antigravity (Gemini)"] = geminiLoggedIn
	logins["Antigravity"] = geminiLoggedIn
	logins["Gemini"] = geminiLoggedIn
	logins["agy"] = geminiLoggedIn
	logins["antigravity"] = geminiLoggedIn
	logins["gemini"] = geminiLoggedIn
	logins["GEMINI"] = geminiLoggedIn

	return logins
}

