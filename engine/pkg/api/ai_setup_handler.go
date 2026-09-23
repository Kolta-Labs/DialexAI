package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

type aiSetupAgentSelection struct {
	Provider string `json:"provider"`
	Model    string `json:"model"`
	RunMode  string `json:"runMode"`
}

type aiSetupRequest struct {
	Prompt         string                   `json:"prompt"`
	ProjectID      string                   `json:"projectId"`
	Model          string                   `json:"model,omitempty"`
	Provider       string                   `json:"provider,omitempty"`
	AutoStart      bool                     `json:"autoStart,omitempty"`
	NumAgents      int                      `json:"numAgents,omitempty"`
	SelectedAgents []aiSetupAgentSelection `json:"selectedAgents,omitempty"`
}

type aiSetupAgentPlan struct {
	Role         string `json:"role"`
	DisplayName  string `json:"displayName"`
	SystemPrompt string `json:"systemPrompt"`
	Caveman      bool   `json:"caveman"`
	Ponytail     bool   `json:"ponytail"`
}

type aiSetupPlan struct {
	Title        string             `json:"title"`
	Topic        string             `json:"topic"`
	Context      string             `json:"context"`
	Archetype    string             `json:"archetype"`
	PrimaryAgent aiSetupAgentPlan   `json:"primaryAgent"`
	PeerAgents   []aiSetupAgentPlan `json:"peerAgents"`
}

var aiSetupJsonRegex = regexp.MustCompile(`(?s)\{.*"title".*"topic".*\}`)

// handleAISetup analyzes raw user input (text or voice transcript) using the smallest/fastest model,
// generates structured topic, context, constraints, and 3-4 specialized adversarial personas,
// saves the complete discussion, and optionally initiates the run.
func (s *Server) handleAISetup(w http.ResponseWriter, r *http.Request) {
	var req aiSetupRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body: "+err.Error())
		return
	}

	trimmedPrompt := strings.TrimSpace(req.Prompt)
	if trimmedPrompt == "" {
		writeError(w, http.StatusBadRequest, "prompt is required")
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	// 1. Resolve Project ID
	projectID := strings.TrimSpace(req.ProjectID)
	if projectID == "" {
		for _, p := range state.Projects {
			if strings.EqualFold(p.Name, "Ungrouped") {
				projectID = p.ID
				break
			}
		}
		if projectID == "" && len(state.Projects) > 0 {
			projectID = state.Projects[0].ID
		}
		if projectID == "" {
			newProj := model.Project{ID: newID(), Name: "General"}
			state.Projects = append(state.Projects, newProj)
			projectID = newProj.ID
		}
	}

	// 2. Resolve Model and Provider for the AI Council Architect
	chosenProvider, chosenModel, chosenRunMode := s.resolveSetupArchitectModel(state, req.Provider, req.Model)

	// 3. Prompt LLM to design the deliberation council
	plan := s.generateAISetupPlan(r.Context(), state, chosenProvider, chosenModel, chosenRunMode, trimmedPrompt, req.SelectedAgents)

	// 4. Assign unique providers to seats from selected or available providers
	discussion := s.buildDiscussionFromPlan(projectID, plan, state, req.NumAgents, req.SelectedAgents)

	// 5. Save discussion to store
	state.Discussions = append(state.Discussions, discussion)
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, err.Error())
		return
	}

	writeJSON(w, http.StatusCreated, discussion)
}

func (s *Server) resolveSetupArchitectModel(state model.AppState, reqProvider, reqModel string) (model.Provider, string, model.RunMode) {
	if reqProvider != "" {
		p := model.Provider(strings.ToUpper(reqProvider))
		m := reqModel
		if m == "" {
			m = p.DefaultModel()
		}
		mode := model.RunModeAPI
		if state.ApiKeys.ForProvider(p) == "" && state.CliCommands.ForProvider(p) != "" {
			mode = model.RunModeCLI
		}
		return p, m, mode
	}

	// Default to configured compaction model (the fast/small model)
	compactionModel := strings.TrimSpace(state.CompactionModel)
	if compactionModel != "" {
		p := detectProviderFromModel(compactionModel)
		mode := model.RunModeAPI
		if state.ApiKeys.ForProvider(p) == "" && state.CliCommands.ForProvider(p) != "" {
			mode = model.RunModeCLI
		}
		return p, compactionModel, mode
	}

	// Find first provider with working key or CLI
	for _, p := range model.AllProviders {
		if p == model.ProviderCustom {
			continue
		}
		if state.ApiKeys.ForProvider(p) != "" {
			return p, p.DefaultModel(), model.RunModeAPI
		}
		if state.CliCommands.ForProvider(p) != "" {
			return p, p.DefaultModel(), model.RunModeCLI
		}
	}

	return model.ProviderAnthropic, model.ProviderAnthropic.DefaultModel(), model.RunModeAPI
}

func detectProviderFromModel(m string) model.Provider {
	lower := strings.ToLower(m)
	switch {
	case strings.Contains(lower, "claude") || strings.Contains(lower, "haiku") || strings.Contains(lower, "sonnet"):
		return model.ProviderAnthropic
	case strings.Contains(lower, "gpt") || strings.Contains(lower, "o1") || strings.Contains(lower, "o3") || strings.Contains(lower, "o4"):
		return model.ProviderOpenAI
	case strings.Contains(lower, "gemini") || strings.Contains(lower, "flash") || strings.Contains(lower, "pro"):
		return model.ProviderGemini
	case strings.Contains(lower, "grok"):
		return model.ProviderGrok
	case strings.Contains(lower, "deepseek"):
		return model.ProviderDeepSeek
	case strings.Contains(lower, "mistral") || strings.Contains(lower, "codestral"):
		return model.ProviderMistral
	case strings.Contains(lower, "llama") || strings.Contains(lower, "ollama") || strings.Contains(lower, "qwen"):
		return model.ProviderOllama
	default:
		return model.ProviderAnthropic
	}
}

func (s *Server) generateAISetupPlan(
	ctx context.Context,
	state model.AppState,
	provider model.Provider,
	agentModel string,
	runMode model.RunMode,
	prompt string,
	selectedAgents []aiSetupAgentSelection,
) aiSetupPlan {
	architectAgent := model.Agent{
		ID:       "ai_council_architect",
		Provider: provider,
		Model:    agentModel,
		RunMode:  runMode,
		SystemPrompt: `You are the Dialex AI Deliberation Architect.
Your task is to take an informal user dilemma, problem, or decision challenge, and construct a high-impact, rigorous multi-agent deliberation council.

Output ONLY a single valid JSON object matching this schema:
{
  "title": "Concise 3 to 6 word title (e.g. Microservices vs Monolith Migration)",
  "topic": "Clear central framing dilemma question",
  "context": "Comprehensive context with explicit constraints, trade-offs, and critical failure modes",
  "archetype": "EXECUTIVE_DECISION",
  "primaryAgent": {
    "role": "Lead Moderator / Facilitator Title",
    "displayName": "Lead Architect",
    "systemPrompt": "Directive instructing how to moderate, weigh trade-offs, and seek consensus",
    "caveman": false,
    "ponytail": false
  },
  "peerAgents": [
    {
      "role": "Proponent / Strategic Advocate",
      "displayName": "Proponent Lead",
      "systemPrompt": "Directives advocating for the ambitious / upside perspective",
      "caveman": false,
      "ponytail": false
    },
    {
      "role": "Adversary / Risk Analyst / Devil's Advocate",
      "displayName": "Risk & Compliance",
      "systemPrompt": "Directives relentlessly stress-testing risks, failure modes, and downsides",
      "caveman": false,
      "ponytail": false
    },
    {
      "role": "Pragmatist / Cost & Resource Auditor",
      "displayName": "Pragmatist",
      "systemPrompt": "Directives focusing on realistic timelines, budgets, headcount, and migration friction",
      "caveman": false,
      "ponytail": false
    }
  ]
}`,
	}

	runner := s.runnerForAgent(architectAgent)
	if runner != nil {
		reqCtx, cancel := context.WithTimeout(ctx, 35*time.Second)
		defer cancel()

		councilContext := ""
		if len(selectedAgents) > 0 {
			var sb strings.Builder
			sb.WriteString("\nTarget Deliberation Council configuration:\n")
			for i, sa := range selectedAgents {
				hint := "Peer Deliberator"
				if i == 0 {
					hint = "Lead Moderator / Facilitator"
				} else if i == 1 {
					hint = "Strategic Proponent"
				} else if i == 2 {
					hint = "Adversary / Risk Analyst"
				} else if i == 3 {
					hint = "Pragmatist / Resource Auditor"
				}
				sb.WriteString(fmt.Sprintf("- Seat %d (%s): %s (%s, %s mode)\n", i+1, hint, sa.Provider, sa.Model, sa.RunMode))
			}
			councilContext = sb.String()
		}

		userMsg := fmt.Sprintf("User dilemma:\n%s%s\n\nGenerate the complete deliberation council plan JSON:", prompt, councilContext)
		resp, err := runner.Respond(reqCtx, architectAgent, "AI Council Setup", "", "", []model.DebateMessage{
			{AgentID: model.Provider("USER"), IsUserComment: true, Content: userMsg},
		}, "")

		if err == nil && resp.Content != "" {
			raw := strings.TrimSpace(resp.Content)
			if match := aiSetupJsonRegex.FindString(raw); match != "" {
				var plan aiSetupPlan
				if err := json.Unmarshal([]byte(match), &plan); err == nil && plan.Topic != "" {
					return plan
				}
			}
		}
	}

	// High quality deterministic fallback when model is unavailable or non-JSON
	return fallbackPlan(prompt)
}

func fallbackPeerAgent(i int) aiSetupAgentPlan {
	switch i {
	case 0:
		return aiSetupAgentPlan{
			Role:         "Strategic Proponent",
			DisplayName:  "Proponent",
			SystemPrompt: "You are the Strategic Proponent. Argue vigorously for the high-velocity, forward-looking solution while articulating long-term upside.",
		}
	case 1:
		return aiSetupAgentPlan{
			Role:         "Devil's Advocate",
			DisplayName:  "Risk Analyst",
			SystemPrompt: "You are the Devil's Advocate and Risk Analyst. Relentlessly identify hidden pitfalls, security gaps, and catastrophic failure modes.",
		}
	case 2:
		return aiSetupAgentPlan{
			Role:         "The Pragmatist",
			DisplayName:  "Pragmatist",
			SystemPrompt: "You are the Pragmatist. Demand realistic timelines, cost caps, maintenance overhead realities, and migration friction details.",
		}
	case 3:
		return aiSetupAgentPlan{
			Role:         "Customer Champion",
			DisplayName:  "User Advocate",
			SystemPrompt: "You are the Customer Champion. Defend user experience, latency, reliability, and human customer satisfaction.",
		}
	default:
		return aiSetupAgentPlan{
			Role:         "Systems & Operations Lead",
			DisplayName:  "Operations",
			SystemPrompt: "You are the Systems & Operations Lead. Focus on observability, incident response, rollback strategies, and operational toil.",
		}
	}
}

func fallbackPlan(prompt string) aiSetupPlan {
	trimmed := strings.TrimSpace(prompt)
	words := strings.Fields(trimmed)
	titleWords := words
	if len(titleWords) > 6 {
		titleWords = titleWords[:6]
	}
	title := strings.Join(titleWords, " ")
	if title == "" {
		title = "Deliberation Council"
	}

	return aiSetupPlan{
		Title:     title,
		Topic:     fmt.Sprintf("How should we resolve: %s?", trimmed),
		Context:   fmt.Sprintf("Deliberation Context:\n%s\n\nKey Directives:\n- Stress-test all trade-offs\n- Evaluate risks and operational overhead\n- Produce an actionable consensus deliverable", trimmed),
		Archetype: "EXECUTIVE_DECISION",
		PrimaryAgent: aiSetupAgentPlan{
			Role:         "Council Facilitator",
			DisplayName:  "Facilitator",
			SystemPrompt: "You are the Lead Facilitator. Keep the debate focused, structure rounds, balance competing viewpoints, and synthesize actionable consensus.",
			Caveman:      false,
			Ponytail:     false,
		},
		PeerAgents: []aiSetupAgentPlan{
			fallbackPeerAgent(0),
			fallbackPeerAgent(1),
			fallbackPeerAgent(2),
		},
	}
}

func (s *Server) buildDiscussionFromPlan(
	projectID string,
	plan aiSetupPlan,
	state model.AppState,
	numAgents int,
	selectedAgents []aiSetupAgentSelection,
) model.Discussion {
	createAgent := func(p model.Provider, m string, rm model.RunMode, planAgent aiSetupAgentPlan) model.Agent {
		if rm != model.RunModeCLI && rm != model.RunModeAPI {
			// Prefer CLI first if configured
			if state.CliCommands.ForProvider(p) != "" {
				rm = model.RunModeCLI
			} else if state.ApiKeys.ForProvider(p) != "" {
				rm = model.RunModeAPI
			} else {
				rm = model.RunModeAPI
			}
		}
		if m == "" {
			m = p.DefaultModel()
		}
		dispName := planAgent.DisplayName
		if dispName == "" {
			dispName = p.BrandName()
		}
		return model.Agent{
			ID:           fmt.Sprintf("seat_%s_%d", strings.ToLower(string(p)), time.Now().UnixNano()%10000),
			Provider:     p,
			Model:        m,
			RunMode:      rm,
			DisplayName:  dispName,
			Role:         planAgent.Role,
			SystemPrompt: planAgent.SystemPrompt,
			Caveman:      planAgent.Caveman,
			Ponytail:     false,
		}
	}

	getPeerPlan := func(idx int) aiSetupAgentPlan {
		if idx < len(plan.PeerAgents) && plan.PeerAgents[idx].Role != "" {
			return plan.PeerAgents[idx]
		}
		return fallbackPeerAgent(idx)
	}

	var primaryAgent model.Agent
	var secondaryAgent, tertiaryAgent, quaternaryAgent, quinaryAgent, senaryAgent *model.Agent

	if len(selectedAgents) > 0 {
		// User explicitly selected participating council agents and modes
		p0 := model.Provider(strings.ToUpper(strings.TrimSpace(selectedAgents[0].Provider)))
		m0 := strings.TrimSpace(selectedAgents[0].Model)
		rm0 := model.RunMode(strings.ToUpper(strings.TrimSpace(selectedAgents[0].RunMode)))
		primaryAgent = createAgent(p0, m0, rm0, plan.PrimaryAgent)

		if len(selectedAgents) > 1 {
			p := model.Provider(strings.ToUpper(strings.TrimSpace(selectedAgents[1].Provider)))
			a := createAgent(p, strings.TrimSpace(selectedAgents[1].Model), model.RunMode(strings.ToUpper(strings.TrimSpace(selectedAgents[1].RunMode))), getPeerPlan(0))
			secondaryAgent = &a
		}
		if len(selectedAgents) > 2 {
			p := model.Provider(strings.ToUpper(strings.TrimSpace(selectedAgents[2].Provider)))
			a := createAgent(p, strings.TrimSpace(selectedAgents[2].Model), model.RunMode(strings.ToUpper(strings.TrimSpace(selectedAgents[2].RunMode))), getPeerPlan(1))
			tertiaryAgent = &a
		}
		if len(selectedAgents) > 3 {
			p := model.Provider(strings.ToUpper(strings.TrimSpace(selectedAgents[3].Provider)))
			a := createAgent(p, strings.TrimSpace(selectedAgents[3].Model), model.RunMode(strings.ToUpper(strings.TrimSpace(selectedAgents[3].RunMode))), getPeerPlan(2))
			quaternaryAgent = &a
		}
		if len(selectedAgents) > 4 {
			p := model.Provider(strings.ToUpper(strings.TrimSpace(selectedAgents[4].Provider)))
			a := createAgent(p, strings.TrimSpace(selectedAgents[4].Model), model.RunMode(strings.ToUpper(strings.TrimSpace(selectedAgents[4].RunMode))), getPeerPlan(3))
			quinaryAgent = &a
		}
		if len(selectedAgents) > 5 {
			p := model.Provider(strings.ToUpper(strings.TrimSpace(selectedAgents[5].Provider)))
			a := createAgent(p, strings.TrimSpace(selectedAgents[5].Model), model.RunMode(strings.ToUpper(strings.TrimSpace(selectedAgents[5].RunMode))), getPeerPlan(4))
			senaryAgent = &a
		}
	} else {
		// Automatic agent selection: prioritize CLI first, then API
		if numAgents <= 0 {
			numAgents = 1 + len(plan.PeerAgents)
		}
		if numAgents < 2 {
			numAgents = 3
		}
		if numAgents > 6 {
			numAgents = 6
		}

		allProviders := []model.Provider{
			model.ProviderAnthropic,
			model.ProviderOpenAI,
			model.ProviderGemini,
			model.ProviderDeepSeek,
			model.ProviderGrok,
			model.ProviderMistral,
			model.ProviderOllama,
		}

		isCliAvailable := func(p model.Provider) bool {
			cmd := state.CliCommands.ForProvider(p)
			if cmd == "" {
				return false
			}
			fields := strings.Fields(cmd)
			if len(fields) == 0 {
				return false
			}
			binPath, err := runner.ResolveBinary(fields[0])
			return err == nil && binPath != ""
		}

		isApiAvailable := func(p model.Provider) bool {
			if p == model.ProviderOllama {
				return isCliAvailable(p)
			}
			return strings.TrimSpace(state.ApiKeys.ForProvider(p)) != ""
		}

		cliProviders := make([]model.Provider, 0, len(allProviders))
		apiProviders := make([]model.Provider, 0, len(allProviders))
		otherProviders := make([]model.Provider, 0, len(allProviders))

		for _, p := range allProviders {
			if isCliAvailable(p) {
				cliProviders = append(cliProviders, p)
			} else if isApiAvailable(p) {
				apiProviders = append(apiProviders, p)
			} else {
				otherProviders = append(otherProviders, p)
			}
		}

		// Prefer CLI providers first, then API providers, then others
		var orderedProviders []model.Provider
		if len(cliProviders) > 0 {
			orderedProviders = append(orderedProviders, cliProviders...)
		}
		if len(apiProviders) > 0 {
			orderedProviders = append(orderedProviders, apiProviders...)
		}
		if len(orderedProviders) == 0 {
			orderedProviders = append(orderedProviders, otherProviders...)
		}

		resolveSeatProviderAndMode := func(idx int) (model.Provider, model.RunMode) {
			p := orderedProviders[idx%len(orderedProviders)]
			// Prefer CLI first
			if isCliAvailable(p) {
				return p, model.RunModeCLI
			}
			return p, model.RunModeAPI
		}

		p0, rm0 := resolveSeatProviderAndMode(0)
		primaryAgent = createAgent(p0, p0.DefaultModel(), rm0, plan.PrimaryAgent)

		if numAgents > 1 {
			p1, rm1 := resolveSeatProviderAndMode(1)
			a1 := createAgent(p1, p1.DefaultModel(), rm1, getPeerPlan(0))
			secondaryAgent = &a1
		}
		if numAgents > 2 {
			p2, rm2 := resolveSeatProviderAndMode(2)
			a2 := createAgent(p2, p2.DefaultModel(), rm2, getPeerPlan(1))
			tertiaryAgent = &a2
		}
		if numAgents > 3 {
			p3, rm3 := resolveSeatProviderAndMode(3)
			a3 := createAgent(p3, p3.DefaultModel(), rm3, getPeerPlan(2))
			quaternaryAgent = &a3
		}
		if numAgents > 4 {
			p4, rm4 := resolveSeatProviderAndMode(4)
			a4 := createAgent(p4, p4.DefaultModel(), rm4, getPeerPlan(3))
			quinaryAgent = &a4
		}
		if numAgents > 5 {
			p5, rm5 := resolveSeatProviderAndMode(5)
			a5 := createAgent(p5, p5.DefaultModel(), rm5, getPeerPlan(4))
			senaryAgent = &a5
		}
	}

	debateConfig := model.DebateConfig{
		Topic:              plan.Topic,
		CommonContext:      plan.Context,
		Primary:            primaryAgent,
		Secondary:          secondaryAgent,
		Tertiary:           tertiaryAgent,
		Quaternary:         quaternaryAgent,
		Quinary:            quinaryAgent,
		Senary:             senaryAgent,
		RoundMode:          model.RoundModeFixed,
		MaxRounds:          6,
		ConsensusTolerance: 0.85,
	}

	now := time.Now().UnixMilli()
	return model.Discussion{
		ID:        newID(),
		ProjectID: projectID,
		Name:      plan.Title,
		Config:    debateConfig,
		CreatedAt: now,
		UpdatedAt: now,
		Status:    model.DiscussionDraft,
	}
}
