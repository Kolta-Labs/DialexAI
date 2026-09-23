package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

func TestAISetupHandler_Validation(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	// Empty prompt should fail with 400
	req := authedRequest(t, "POST", srv.URL+"/api/v1/debates/ai-setup", token, mustJSON(t, map[string]any{
		"prompt": "",
	}))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("status = %d, want %d", resp.StatusCode, http.StatusBadRequest)
	}
}

func TestAISetupHandler_SuccessWithFallback(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	req := authedRequest(t, "POST", srv.URL+"/api/v1/debates/ai-setup", token, mustJSON(t, map[string]any{
		"prompt": "Should we build our own auth system or use Auth0 with 3 engineers?",
	}))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
	}

	var disc model.Discussion
	if err := json.NewDecoder(resp.Body).Decode(&disc); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if disc.ID == "" {
		t.Errorf("expected non-empty ID")
	}
	if disc.Name == "" {
		t.Errorf("expected non-empty Name")
	}
	if disc.Config.Topic == "" {
		t.Errorf("expected non-empty Topic")
	}
	if disc.Config.Primary.Role == "" {
		t.Errorf("expected Primary agent role to be populated")
	}
	if disc.Config.Secondary == nil {
		t.Errorf("expected Secondary agent to be populated")
	}
	if disc.Config.Tertiary == nil {
		t.Errorf("expected Tertiary agent to be populated")
	}

	// Verify seats have distinct roles and valid models
	roles := []string{disc.Config.Primary.Role}
	if disc.Config.Secondary != nil {
		roles = append(roles, disc.Config.Secondary.Role)
	}
	if disc.Config.Tertiary != nil {
		roles = append(roles, disc.Config.Tertiary.Role)
	}
	if disc.Config.Quaternary != nil {
		roles = append(roles, disc.Config.Quaternary.Role)
	}

	seenRoles := make(map[string]bool)
	for _, r := range roles {
		if r == "" {
			t.Errorf("role cannot be empty")
		}
		if seenRoles[r] {
			t.Errorf("duplicate role in seats: %v", r)
		}
		seenRoles[r] = true
	}
}

func TestAISetupHandler_SuccessWithStructuredLLMResponse(t *testing.T) {
	s, st := newTestServer(t)

	// Mock runner returning a valid deliberation council JSON
	mockJSON := `{
  "title": "Auth0 vs Custom Auth System",
  "topic": "Should the engineering team adopt Auth0 or build an in-house OAuth2 service?",
  "context": "Constraints: 3 engineers, strict 6-month deadline, HIPAA compliance required.",
  "archetype": "TECH_ARCHITECTURE",
  "primaryAgent": {
    "role": "Principal Solutions Architect",
    "displayName": "Solutions Architect",
    "systemPrompt": "Moderate the architectural trade-offs.",
    "caveman": false,
    "ponytail": true
  },
  "peerAgents": [
    {
      "role": "Cloud Security Lead",
      "displayName": "Security Lead",
      "systemPrompt": "Advocate for managed security and compliance guarantees.",
      "caveman": false,
      "ponytail": false
    },
    {
      "role": "Devil's Advocate",
      "displayName": "Adversary",
      "systemPrompt": "Stress-test vendor lock-in and pricing escalations.",
      "caveman": false,
      "ponytail": false
    },
    {
      "role": "Startup Pragmatist",
      "displayName": "Pragmatist",
      "systemPrompt": "Focus on engineering velocity and headcount limits.",
      "caveman": false,
      "ponytail": false
    }
  ]
}`
	s.RunnerFor = func(model.Agent) runner.AgentRunner {
		return &fakeRunner{
			respond: func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error) {
				return runner.AgentReply{Content: "```json\n" + mockJSON + "\n```"}, nil
			},
		}
	}

	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	req := authedRequest(t, "POST", srv.URL+"/debates/ai-setup", token, mustJSON(t, map[string]any{
		"prompt": "Evaluate Auth0 vs custom auth for HIPAA",
	}))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
	}

	var disc model.Discussion
	if err := json.NewDecoder(resp.Body).Decode(&disc); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if disc.Name != "Auth0 vs Custom Auth System" {
		t.Errorf("disc.Name = %q, want 'Auth0 vs Custom Auth System'", disc.Name)
	}
	if disc.Config.Primary.DisplayName != "Solutions Architect" {
		t.Errorf("Primary DisplayName = %q, want 'Solutions Architect'", disc.Config.Primary.DisplayName)
	}
	if disc.Config.Secondary == nil || disc.Config.Secondary.DisplayName != "Security Lead" {
		t.Errorf("Secondary DisplayName mismatch")
	}
	if disc.Config.Tertiary == nil || disc.Config.Tertiary.DisplayName != "Adversary" {
		t.Errorf("Tertiary DisplayName mismatch")
	}
	if disc.Config.Quaternary == nil || disc.Config.Quaternary.DisplayName != "Pragmatist" {
		t.Errorf("Quaternary DisplayName mismatch")
	}
}

func TestAISetupHandler_WithUserSelectedAgents(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	req := authedRequest(t, "POST", srv.URL+"/api/v1/debates/ai-setup", token, mustJSON(t, map[string]any{
		"prompt": "Evaluate migrating to Rust for low-latency market feeds",
		"selectedAgents": []map[string]any{
			{"provider": "anthropic", "model": "claude-3-7-sonnet", "runMode": "API"},
			{"provider": "openai", "model": "gpt-4o", "runMode": "CLI"},
			{"provider": "gemini", "model": "gemini-2.0-flash", "runMode": "API"},
		},
	}))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
	}

	var disc model.Discussion
	if err := json.NewDecoder(resp.Body).Decode(&disc); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	// Verify primary is Anthropic with claude-3-7-sonnet and API mode
	if disc.Config.Primary.Provider != model.ProviderAnthropic {
		t.Errorf("Primary.Provider = %v, want Anthropic", disc.Config.Primary.Provider)
	}
	if disc.Config.Primary.Model != "claude-3-7-sonnet" {
		t.Errorf("Primary.Model = %v, want claude-3-7-sonnet", disc.Config.Primary.Model)
	}
	if disc.Config.Primary.RunMode != model.RunModeAPI {
		t.Errorf("Primary.RunMode = %v, want API", disc.Config.Primary.RunMode)
	}
	if disc.Config.Primary.Role == "" {
		t.Errorf("Primary.Role should be populated")
	}

	// Verify secondary is OpenAI with gpt-4o and CLI mode
	if disc.Config.Secondary == nil {
		t.Fatalf("expected Secondary to be non-nil")
	}
	if disc.Config.Secondary.Provider != model.ProviderOpenAI {
		t.Errorf("Secondary.Provider = %v, want OpenAI", disc.Config.Secondary.Provider)
	}
	if disc.Config.Secondary.Model != "gpt-4o" {
		t.Errorf("Secondary.Model = %v, want gpt-4o", disc.Config.Secondary.Model)
	}
	if disc.Config.Secondary.RunMode != model.RunModeCLI {
		t.Errorf("Secondary.RunMode = %v, want CLI", disc.Config.Secondary.RunMode)
	}

	// Verify tertiary is Gemini with gemini-2.0-flash and API mode
	if disc.Config.Tertiary == nil {
		t.Fatalf("expected Tertiary to be non-nil")
	}
	if disc.Config.Tertiary.Provider != model.ProviderGemini {
		t.Errorf("Tertiary.Provider = %v, want Gemini", disc.Config.Tertiary.Provider)
	}
	if disc.Config.Tertiary.Model != "gemini-2.0-flash" {
		t.Errorf("Tertiary.Model = %v, want gemini-2.0-flash", disc.Config.Tertiary.Model)
	}
	if disc.Config.Tertiary.RunMode != model.RunModeAPI {
		t.Errorf("Tertiary.RunMode = %v, want API", disc.Config.Tertiary.RunMode)
	}

	// Quaternary should be nil because user only selected 3 agents
	if disc.Config.Quaternary != nil {
		t.Errorf("expected Quaternary to be nil for 3 selected agents, got %+v", disc.Config.Quaternary)
	}
}

func TestAISetupHandler_SameProviderMultipleSeats(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	// User configures 2 seats using the SAME provider (Claude) with different models/modes
	req := authedRequest(t, "POST", srv.URL+"/api/v1/debates/ai-setup", token, mustJSON(t, map[string]any{
		"prompt": "Evaluate React Server Components vs Remix",
		"selectedAgents": []map[string]any{
			{"provider": "anthropic", "model": "claude-3-7-sonnet", "runMode": "CLI"},
			{"provider": "anthropic", "model": "claude-3-5-haiku", "runMode": "API"},
		},
	}))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
	}

	var disc model.Discussion
	if err := json.NewDecoder(resp.Body).Decode(&disc); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if disc.Config.Primary.Provider != model.ProviderAnthropic || disc.Config.Primary.Model != "claude-3-7-sonnet" {
		t.Errorf("Primary seat mismatch: %+v", disc.Config.Primary)
	}
	if disc.Config.Secondary == nil || disc.Config.Secondary.Provider != model.ProviderAnthropic || disc.Config.Secondary.Model != "claude-3-5-haiku" {
		t.Errorf("Secondary seat mismatch: %+v", disc.Config.Secondary)
	}
	if disc.Config.Primary.Role == disc.Config.Secondary.Role {
		t.Errorf("Primary and Secondary seats should have different personas/roles")
	}
}

func TestAISetupHandler_NumAgents(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	// User requests exactly 2 agents
	req := authedRequest(t, "POST", srv.URL+"/api/v1/debates/ai-setup", token, mustJSON(t, map[string]any{
		"prompt":    "Should we use Rust or Zig?",
		"numAgents": 2,
	}))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST error = %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("status = %d, want %d", resp.StatusCode, http.StatusCreated)
	}

	var disc model.Discussion
	if err := json.NewDecoder(resp.Body).Decode(&disc); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if disc.Config.Primary.Role == "" {
		t.Errorf("Primary role should be set")
	}
	if disc.Config.Secondary == nil {
		t.Errorf("Secondary should be present for 2 agents")
	}
	if disc.Config.Tertiary != nil {
		t.Errorf("Tertiary should be nil for 2 agents")
	}
}
