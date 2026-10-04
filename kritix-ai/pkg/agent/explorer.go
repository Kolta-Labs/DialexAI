package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"kritix/pkg/driver"
	"kritix/pkg/model"
	"kritix/pkg/triage"
)

// AgentDecision represents a structured action decided by the VLM/LLM.
type AgentDecision struct {
	ActionType  driver.ActionType `json:"action_type"`
	TargetQuery string            `json:"target_query"` // element text, role, or test-id
	Value       string            `json:"value,omitempty"`
	Reasoning   string            `json:"reasoning"`
	IsGoalMet   bool              `json:"is_goal_met"`
	BugDetected bool              `json:"bug_detected"`
	BugSummary  string            `json:"bug_summary,omitempty"`
}

// ExplorerConfig configures an autonomous exploratory testing session.
type ExplorerConfig struct {
	TargetURL  string        `json:"target_url"`
	Goal       string        `json:"goal"`
	MaxSteps   int           `json:"max_steps"`
	Timeout    time.Duration `json:"timeout"`
	SessionID  string        `json:"session_id"`
}

// Explorer executes autonomous exploratory and manual-style testing.
type Explorer struct {
	router   *model.Router
	driver   driver.BrowserDriver
	recorder *triage.SessionRecorder
	config   ExplorerConfig
}

// NewExplorer creates a new autonomous testing agent.
func NewExplorer(router *model.Router, drv driver.BrowserDriver, cfg ExplorerConfig) *Explorer {
	if cfg.MaxSteps <= 0 {
		cfg.MaxSteps = 10
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 60 * time.Second
	}
	if cfg.SessionID == "" {
		cfg.SessionID = fmt.Sprintf("session-%d", time.Now().UnixNano())
	}

	recorder := triage.NewSessionRecorder(cfg.SessionID, cfg.TargetURL)
	return &Explorer{
		router:   router,
		driver:   drv,
		recorder: recorder,
		config:   cfg,
	}
}

// Run executes the perception-reasoning-action loop until completion or bug discovery.
func (e *Explorer) Run(ctx context.Context) (*triage.SessionTrace, error) {
	ctx, cancel := context.WithTimeout(ctx, e.config.Timeout)
	defer cancel()

	if err := e.driver.Start(ctx); err != nil {
		return nil, fmt.Errorf("failed to start browser driver: %w", err)
	}
	defer e.driver.Stop(ctx)

	// Step 1: Navigate to target URL
	navAction := driver.Action{
		Type:        driver.ActionNavigate,
		Value:       e.config.TargetURL,
		Description: fmt.Sprintf("Navigate to %s", e.config.TargetURL),
		Timestamp:   time.Now(),
	}
	state, err := e.driver.Navigate(ctx, e.config.TargetURL)
	if err != nil {
		return nil, fmt.Errorf("navigation failed: %w", err)
	}
	e.recorder.RecordStep(navAction, state)

	var executedActions []driver.Action
	executedActions = append(executedActions, navAction)

	// Step 2: Main Exploration Loop
	for step := 1; step <= e.config.MaxSteps; step++ {
		select {
		case <-ctx.Done():
			return e.recorder.Finalize(), ctx.Err()
		default:
		}

		// 1. Check invariants on current state (500 errors, console crashes)
		if bug := e.inspectInvariants(state, executedActions); bug != nil {
			e.recorder.RegisterDefect(*bug)
			return e.recorder.Finalize(), nil
		}

		// 2. Reason on next step via StageExplorer model
		decision, err := e.decideNextAction(ctx, state, executedActions)
		if err != nil {
			// Fallback: heuristic exploration if model is unreachable or offline
			decision = e.heuristicNextAction(state, step)
		}

		if decision.IsGoalMet {
			break
		}

		// 3. Translate decision to driver Action
		action := e.buildDriverAction(state, decision)
		executedActions = append(executedActions, action)

		// 4. Execute action in browser
		state, err = e.driver.ExecuteAction(ctx, action)
		if err != nil {
			return e.recorder.Finalize(), fmt.Errorf("action execution failed: %w", err)
		}

		// 5. Record step artifacts
		e.recorder.RecordStep(action, state)

		// 6. Check if model signaled an explicit bug
		if decision.BugDetected {
			repro := triage.GeneratePlaywrightRepro(e.config.TargetURL, executedActions, decision.BugSummary)
			e.recorder.RegisterDefect(triage.DefectReport{
				ID:               fmt.Sprintf("KRITIX-BUG-%d", step),
				Title:            decision.BugSummary,
				Severity:         triage.SeverityMajor,
				TargetURL:        e.config.TargetURL,
				StepsToReproduce: formatSteps(executedActions),
				ExpectedBehavior: e.config.Goal,
				ActualBehavior:   decision.Reasoning,
				PlaywrightRepro:  repro,
				DiscoveredAt:     time.Now(),
			})
			break
		}
	}

	return e.recorder.Finalize(), nil
}

func (e *Explorer) decideNextAction(ctx context.Context, state *driver.BrowserState, history []driver.Action) (*AgentDecision, error) {
	prompt := e.buildPrompt(state, history)

	req := model.Request{
		Stage: model.StageExplorer,
		Messages: []model.Message{
			{
				Role:    "system",
				Content: "You are an autonomous manual and exploratory QA tester. You examine the current UI and choose the next logical action to test the user flow or find bugs. Always respond in valid JSON format: {\"action_type\": \"click\"|\"type\"|\"wait\", \"target_query\": \"element text/role\", \"value\": \"text to type\", \"reasoning\": \"explanation\", \"is_goal_met\": bool, \"bug_detected\": bool, \"bug_summary\": \"desc\"}",
			},
			{
				Role:        "user",
				Content:     prompt,
				ImageBase64: state.ScreenshotB64,
				ImageMime:   "image/png",
			},
		},
		Temperature: 0.2,
		MaxTokens:   512,
	}

	stepCtx, stepCancel := context.WithTimeout(ctx, 5*time.Second)
	defer stepCancel()

	resp, err := e.router.InvokeStage(stepCtx, model.StageExplorer, req)
	if err != nil {
		// Fallback: heuristic exploration if model is unreachable or offline
		return nil, err
	}

	var decision AgentDecision
	cleanedContent := extractJSON(resp.Content)
	if err := json.Unmarshal([]byte(cleanedContent), &decision); err != nil {
		return nil, fmt.Errorf("failed to parse agent decision JSON: %w (content: %s)", err, resp.Content)
	}

	return &decision, nil
}

func (e *Explorer) heuristicNextAction(state *driver.BrowserState, step int) *AgentDecision {
	// If inputs exist and haven't been typed in, type test value
	for _, el := range state.Elements {
		if el.Tag == "input" || el.Role == "textbox" {
			return &AgentDecision{
				ActionType:  driver.ActionTypeKey,
				TargetQuery: el.ID,
				Value:       "TEST_INPUT_VALUE",
				Reasoning:   "Heuristic: Fill open input field with boundary test data",
			}
		}
	}

	// If buttons exist, click the first actionable CTA
	for _, el := range state.Elements {
		if el.Tag == "button" || el.Role == "button" {
			return &AgentDecision{
				ActionType:  driver.ActionClick,
				TargetQuery: el.Text,
				Reasoning:   "Heuristic: Trigger CTA button to advance flow",
			}
		}
	}

	return &AgentDecision{
		ActionType:  driver.ActionWait,
		Value:       "1000",
		Reasoning:   "Heuristic: Wait for network or DOM settlement",
		IsGoalMet:   step >= 3,
	}
}

func (e *Explorer) buildDriverAction(state *driver.BrowserState, decision *AgentDecision) driver.Action {
	action := driver.Action{
		Type:        decision.ActionType,
		Value:       decision.Value,
		Description: decision.Reasoning,
		Timestamp:   time.Now(),
	}

	if el := state.FindElementBySemanticMatch(decision.TargetQuery); el != nil {
		action.TargetXPath = el.XPath
		action.TargetRole = el.Role
		action.TargetText = el.Text
		action.Coordinates = &el.BoundingBox
	} else {
		action.TargetText = decision.TargetQuery
	}

	return action
}

func (e *Explorer) inspectInvariants(state *driver.BrowserState, history []driver.Action) *triage.DefectReport {
	// Invariant 1: No 500 server errors
	for _, net := range state.NetworkActivity {
		if net.StatusCode >= 500 {
			repro := triage.GeneratePlaywrightRepro(e.config.TargetURL, history, "API should return 2xx or user-friendly 4xx; not 500 server crash")
			return &triage.DefectReport{
				ID:                "KRITIX-INVARIANT-500",
				Title:             fmt.Sprintf("Internal Server Error (HTTP %d) on %s", net.StatusCode, net.URL),
				Severity:          triage.SeverityBlocker,
				TargetURL:         e.config.TargetURL,
				StepsToReproduce:  formatSteps(history),
				ExpectedBehavior:  "Clean client validation without HTTP 500",
				ActualBehavior:    fmt.Sprintf("Endpoint %s responded with status %d", net.URL, net.StatusCode),
				FailedNetworkReqs: []driver.NetworkEvent{net},
				PlaywrightRepro:   repro,
				CurlRepro:         triage.GenerateCurlRepro(net),
				DiscoveredAt:      time.Now(),
			}
		}
	}

	// Invariant 2: No uncaught fatal JS exceptions
	for _, log := range state.ConsoleLogs {
		if strings.Contains(log, "Uncaught") || strings.Contains(log, "TypeError") {
			repro := triage.GeneratePlaywrightRepro(e.config.TargetURL, history, "No fatal uncaught exceptions in console")
			return &triage.DefectReport{
				ID:               "KRITIX-INVARIANT-JSCRASH",
				Title:            fmt.Sprintf("Client Crash: %s", log),
				Severity:         triage.SeverityMajor,
				TargetURL:        e.config.TargetURL,
				StepsToReproduce: formatSteps(history),
				ExpectedBehavior: "Graceful error handling without console crash",
				ActualBehavior:   log,
				ConsoleErrors:    []string{log},
				PlaywrightRepro:  repro,
				DiscoveredAt:     time.Now(),
			}
		}
	}

	return nil
}

func (e *Explorer) buildPrompt(state *driver.BrowserState, history []driver.Action) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("TEST GOAL: %s\n", e.config.Goal))
	sb.WriteString(fmt.Sprintf("CURRENT URL: %s | TITLE: %s\n\n", state.URL, state.Title))

	sb.WriteString("INTERACTIVE ELEMENTS:\n")
	for i, el := range state.Elements {
		if i >= 15 {
			sb.WriteString("... (truncated)\n")
			break
		}
		sb.WriteString(fmt.Sprintf("- [%s] tag=%s id=%q text=%q role=%q\n",
			el.Tag, el.Tag, el.ID, el.Text, el.Role))
	}

	sb.WriteString("\nRECENT ACTIONS EXECUTED:\n")
	for _, a := range history {
		sb.WriteString(fmt.Sprintf("- %s: %s\n", a.Type, a.Description))
	}

	sb.WriteString("\nWhat action should be taken next? Respond in strict JSON.")
	return sb.String()
}

func extractJSON(s string) string {
	start := strings.Index(s, "{")
	end := strings.LastIndex(s, "}")
	if start != -1 && end != -1 && end > start {
		return s[start : end+1]
	}
	return s
}

func formatSteps(actions []driver.Action) []string {
	var steps []string
	for i, a := range actions {
		steps = append(steps, fmt.Sprintf("%d. %s (%s)", i+1, a.Description, a.Type))
	}
	return steps
}
