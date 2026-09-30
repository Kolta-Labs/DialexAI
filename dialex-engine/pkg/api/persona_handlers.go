package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"

	"dialex/pkg/model"
)


func (s *Server) handleListPersonas(w http.ResponseWriter, r *http.Request) {
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, state.Personas)
}

func (s *Server) handleCreatePersona(w http.ResponseWriter, r *http.Request) {
	var p model.Persona
	if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON")
		return
	}
	if p.ID == "" {
		p.ID = newID()
	}
	p.IsSystem = false

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	state.Personas = append(state.Personas, p)
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusCreated, p)
}

func (s *Server) handleDeletePersona(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	filtered := state.Personas[:0]
	for _, p := range state.Personas {
		if p.ID != id || p.IsSystem {
			filtered = append(filtered, p)
		}
	}
	state.Personas = filtered
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleImportPersonas(w http.ResponseWriter, r *http.Request) {
	var incoming []model.Persona
	if err := json.NewDecoder(r.Body).Decode(&incoming); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON")
		return
	}
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	count := 0
	for _, p := range incoming {
		p.IsSystem = false
		if p.ID == "" {
			p.ID = newID()
		}
		state.Personas = append(state.Personas, p)
		count++
	}
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]int{"imported": count})
}

func (s *Server) handleExportPersonas(w http.ResponseWriter, r *http.Request) {
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	var exportable []model.Persona
	for _, p := range state.Personas {
		if !p.IsSystem {
			exportable = append(exportable, p)
		}
	}
	writeJSON(w, http.StatusOK, exportable)
}

type personaChatMessageDto struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type personaChatRequest struct {
	Provider     model.Provider          `json:"provider"`
	Model        string                  `json:"model"`
	RunMode      model.RunMode           `json:"runMode"`
	CliCommand   string                  `json:"cliCommand,omitempty"`
	Messages     []personaChatMessageDto `json:"messages"`
	CurrentDraft *model.Persona          `json:"currentDraft,omitempty"`
}

type personaChatResponse struct {
	Reply         string         `json:"reply"`
	ParsedPersona *model.Persona `json:"parsedPersona,omitempty"`
}

var jsonCodeBlockRegex = regexp.MustCompile("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```")


func (s *Server) handleChatPersona(w http.ResponseWriter, r *http.Request) {
	var req personaChatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body: "+err.Error())
		return
	}

	provider := req.Provider
	if provider == "" {
		provider = model.ProviderAnthropic
	}
	agentModel := req.Model
	if agentModel == "" {
		agentModel = provider.DefaultModel()
	}
	runMode := req.RunMode
	if runMode == "" {
		runMode = model.RunModeAPI
	}

	var cliCmdPtr *string
	if req.CliCommand != "" {
		cliCmdPtr = &req.CliCommand
	}

	systemInstruction := `You are the Dialex Persona Architect, an expert assistant dedicated to designing deep, highly-specialized, dialectically rigorous AI debate personas.
Your goal is to converse with the user and construct rich persona definitions tailored for multi-agent deliberation.

A Dialex Persona has these fields:
- "name": Distinctive persona name (e.g. "Socratic Epistemologist", "Zero-Trust Security Lead").
- "category": Profession/domain (e.g. "Software Engineering", "Scientific Research", "Product & Strategy", "Legal & Governance", "General Debate").
- "role": Specific seat title/label (e.g. "Chief Adversary", "Formal Verification Architect").
- "description": 1-2 sentence high-level summary of the persona's debate purpose.
- "icon": An icon keyword ("code", "database", "security", "terminal", "analytics", "science", "psychology", "business", "legal", "creative", "default").
- "roleAndPersona": Detailed specification of who this persona is, their background, and worldview.
- "coreExpertise": Specific methodologies, frameworks, laws, or technical domains they specialize in.
- "toneAndVoice": Directives for their tone (e.g. "Incisive, empirical, skeptical of hype").
- "objective": Their core fiduciary goal or debate mandate.
- "systemPrompt": Full composed system instructions for the LLM when debating as this persona.
- "ponytail": boolean (true if the persona should default to structured, bullet-driven executive analysis).

When you output or refine a persona, ALWAYS provide a conversational response followed by a JSON markdown code block containing the complete persona object, for example:
` + "```json\n" + `{
  "id": "custom_architect",
  "name": "Distributed Systems Architect",
  "category": "Software Engineering",
  "role": "Fault-Tolerance Specialist",
  "description": "Examines consensus models, partitions, and recovery invariants under stress.",
  "icon": "terminal",
  "roleAndPersona": "Principal engineer with deep background in Raft, Paxos, and distributed storage.",
  "coreExpertise": "Jepsen testing, CAP theorem trade-offs, p99 tail latencies, Byzantine fault tolerance.",
  "toneAndVoice": "Pragmatic, direct, relentlessly empirical.",
  "objective": "Prevent cascading failure modes and unrecoverable state divergence.",
  "systemPrompt": "### ROLE & PERSONA\nPrincipal engineer...\n\n### CORE EXPERTISE\nJepsen testing...",
  "ponytail": true
}` + "\n```\n" + `The user interface will automatically detect this JSON block and render a one-click "Load into Editor" button!`

	if req.CurrentDraft != nil && req.CurrentDraft.Name != "" {
		draftBytes, _ := json.MarshalIndent(req.CurrentDraft, "", "  ")
		systemInstruction += fmt.Sprintf("\n\nUser's current editor draft is:\n```json\n%s\n```\nAssist them in improving or modifying this draft as requested.", string(draftBytes))
	}

	agent := model.Agent{
		ID:           "persona_architect",
		Provider:     provider,
		Model:        agentModel,
		RunMode:      runMode,
		CliCommand:   cliCmdPtr,
		SystemPrompt: systemInstruction,
	}

	var transcript []model.DebateMessage
	for _, m := range req.Messages {
		isUser := strings.EqualFold(m.Role, "user")
		var msgAgentID model.Provider
		if isUser {
			msgAgentID = model.Provider("USER")
		} else {
			msgAgentID = provider
		}
		transcript = append(transcript, model.DebateMessage{
			AgentID:       msgAgentID,
			IsUserComment: isUser,
			Content:       m.Content,
		})
	}

	if len(transcript) == 0 {
		transcript = append(transcript, model.DebateMessage{
			AgentID:       model.Provider("USER"),
			IsUserComment: true,
			Content:       "Hello! Help me design a new persona.",
		})
	}


	reply, err := s.runnerForAgent(agent).Respond(r.Context(), agent, "Persona Design Consultation", "", "", transcript, "")
	if err != nil {
		writeError(w, http.StatusBadGateway, "failed to run persona assistant: "+err.Error())
		return
	}

	var parsed *model.Persona
	matches := jsonCodeBlockRegex.FindStringSubmatch(reply.Content)
	if len(matches) > 1 {
		var p model.Persona
		if err := json.Unmarshal([]byte(strings.TrimSpace(matches[1])), &p); err == nil && p.Name != "" {
			if p.ID == "" {
				p.ID = newID()
			}
			p.IsSystem = false
			parsed = &p
		}
	}

	writeJSON(w, http.StatusOK, personaChatResponse{
		Reply:         reply.Content,
		ParsedPersona: parsed,
	})
}


func (s *Server) handleUploadFile(w http.ResponseWriter, r *http.Request) {
	writeError(w, http.StatusNotImplemented, "not implemented")
}

func (s *Server) handleDeleteFile(w http.ResponseWriter, r *http.Request) {
	writeError(w, http.StatusNotImplemented, "not implemented")
}

func (s *Server) handleGetDebateUsage(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	var tokensIn, tokensOut int64
	for _, d := range state.Discussions {
		if d.ID == id {
			for _, m := range d.Transcript {
				if m.TokensIn != nil {
					tokensIn += int64(*m.TokensIn)
				}
				if m.TokensOut != nil {
					tokensOut += int64(*m.TokensOut)
				}
			}
			break
		}
	}
	writeJSON(w, http.StatusOK, map[string]int64{"tokensIn": tokensIn, "tokensOut": tokensOut})
}

func (s *Server) handleGetProjectUsage(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	var tokensIn, tokensOut int64
	for _, d := range state.Discussions {
		if d.ProjectID == id {
			for _, m := range d.Transcript {
				if m.TokensIn != nil {
					tokensIn += int64(*m.TokensIn)
				}
				if m.TokensOut != nil {
					tokensOut += int64(*m.TokensOut)
				}
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]int64{"tokensIn": tokensIn, "tokensOut": tokensOut})
}

func (s *Server) handleListModels(w http.ResponseWriter, r *http.Request) {
	models := map[string][]string{
		"ANTHROPIC": {"claude-sonnet-5", "claude-opus-5", "claude-haiku-4-5-20251001", "claude-sonnet-4-5"},
		"OPENAI":    {"gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.5", "gpt-5.4-mini"},
		"GEMINI":    {"gemini-3.7-flash", "gemini-3.1-pro", "gemini-3.5-flash-lite"},
		"GROK":      {"grok-4-fast", "grok-4", "grok-3", "grok-3-mini"},
		"DEEPSEEK":  {"deepseek-chat", "deepseek-reasoner"},
		"MISTRAL":   {"mistral-medium-latest", "mistral-large-latest", "mistral-small-latest"},
	}
	writeJSON(w, http.StatusOK, models)
}

func (s *Server) handleDuplicateDebate(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	var src *model.Discussion
	for i := range state.Discussions {
		if state.Discussions[i].ID == id {
			src = &state.Discussions[i]
			break
		}
	}
	if src == nil {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	dup := model.NewDiscussion(newID(), src.ProjectID, src.Name+" (copy)", src.Config)
	state.Discussions = append(state.Discussions, dup)
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, dup)
}
