package runner

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"socratix/pkg/model"
)

// A non-2xx response must surface the provider's own error body instead of failing to parse
// an error shape as a success shape (the bug this exists to prevent — see api_runner.go's
// postJSON doc comment).
func TestPostJSONSurfacesNonSuccessStatus(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
		w.Write([]byte(`{"type":"error","error":{"message":"invalid x-api-key"}}`))
	}))
	defer server.Close()

	err := postJSON(context.Background(), server.Client(), server.URL, nil, map[string]string{"a": "b"}, &struct{}{})
	if err == nil {
		t.Fatal("postJSON error = nil, want an error for a 401 response")
	}
	if !strings.Contains(err.Error(), "401") || !strings.Contains(err.Error(), "invalid x-api-key") {
		t.Errorf("error = %q, want it to include the status code and the provider's own error text", err.Error())
	}
}

func TestRespondCallsAnthropicAndParsesUsage(t *testing.T) {
	var gotAuth, gotModel string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("x-api-key")
		var body anthropicRequest
		json.NewDecoder(r.Body).Decode(&body)
		gotModel = body.Model
		json.NewEncoder(w).Encode(anthropicResponse{
			Content: []struct {
				Text string `json:"text"`
			}{{Text: "reply text"}},
			Usage: &AnthropicUsage{
				InputTokens:          42,
				OutputTokens:         7,
				CacheReadInputTokens: 20,
			},
		})
	}))
	defer server.Close()

	runner := NewApiAgentRunner(map[model.Provider]string{model.ProviderAnthropic: "sk-ant-test"})
	runner.URLs[model.ProviderAnthropic] = server.URL

	reply, err := runner.Respond(context.Background(), model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5"), "t", "", "", nil, "")
	if err != nil {
		t.Fatalf("Respond() error = %v", err)
	}
	if gotAuth != "sk-ant-test" {
		t.Errorf("x-api-key header = %q, want sk-ant-test", gotAuth)
	}
	if gotModel != "claude-sonnet-5" {
		t.Errorf("request model = %q, want claude-sonnet-5", gotModel)
	}
	if reply.Content != "reply text" {
		t.Errorf("reply.Content = %q, want %q", reply.Content, "reply text")
	}
	if reply.TokensIn == nil || *reply.TokensIn != 42 || reply.TokensOut == nil || *reply.TokensOut != 7 {
		t.Errorf("reply tokens = in:%v out:%v, want 42/7", reply.TokensIn, reply.TokensOut)
	}
	if reply.TokensCached == nil || *reply.TokensCached != 20 {
		t.Errorf("reply.TokensCached = %v, want 20", reply.TokensCached)
	}
}

func TestRespondUsesModelOverride(t *testing.T) {
	var gotModel string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body anthropicRequest
		json.NewDecoder(r.Body).Decode(&body)
		gotModel = body.Model
		json.NewEncoder(w).Encode(anthropicResponse{Content: []struct {
			Text string `json:"text"`
		}{{Text: "ok"}}})
	}))
	defer server.Close()

	runner := NewApiAgentRunner(map[model.Provider]string{model.ProviderAnthropic: "sk-ant-test"})
	runner.URLs[model.ProviderAnthropic] = server.URL

	_, err := runner.Respond(context.Background(), model.NewAgent(model.ProviderAnthropic, "claude-opus-5"), "t", "", "", nil, "claude-haiku-4-5-20251001")
	if err != nil {
		t.Fatalf("Respond() error = %v", err)
	}
	if gotModel != "claude-haiku-4-5-20251001" {
		t.Errorf("request model = %q, want the override, not the agent's own model", gotModel)
	}
}

func TestRespondOpenAICompatibleCoversAllFourProviders(t *testing.T) {
	cachedCount := 64
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(chatResponse{
			Choices: []struct {
				Message struct {
					Content string `json:"content"`
				} `json:"message"`
			}{{Message: struct {
				Content string `json:"content"`
			}{Content: "compat reply"}}},
			Usage: &ChatUsage{
				PromptTokens:     100,
				CompletionTokens: 20,
				PromptTokensDetails: &struct {
					CachedTokens int `json:"cached_tokens"`
				}{CachedTokens: cachedCount},
			},
		})
	}))
	defer server.Close()

	for _, p := range []model.Provider{model.ProviderOpenAI, model.ProviderGrok, model.ProviderDeepSeek, model.ProviderMistral} {
		runner := NewApiAgentRunner(map[model.Provider]string{p: "key"})
		runner.URLs[p] = server.URL

		reply, err := runner.Respond(context.Background(), model.NewAgent(p, p.DefaultModel()), "t", "", "", nil, "")
		if err != nil {
			t.Errorf("%s: Respond() error = %v", p, err)
			continue
		}
		if reply.Content != "compat reply" {
			t.Errorf("%s: reply.Content = %q, want %q", p, reply.Content, "compat reply")
		}
		if reply.TokensCached == nil || *reply.TokensCached != cachedCount {
			t.Errorf("%s: reply.TokensCached = %v, want %d", p, reply.TokensCached, cachedCount)
		}
	}
}

func TestRespondCallsGemini(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.Contains(r.URL.Path, "gemini-3.7-flash") {
			t.Errorf("request path = %q, want it to include the model name", r.URL.Path)
		}
		var cand geminiCandidate
		cand.Content.Parts = []geminiPart{{Text: "gemini reply"}}
		json.NewEncoder(w).Encode(geminiResponse{
			Candidates: []geminiCandidate{cand},
			UsageMetadata: &GeminiUsageMetadata{
				PromptTokenCount:        50,
				CandidatesTokenCount:    15,
				CachedContentTokenCount: 30,
			},
		})
	}))
	defer server.Close()

	runner := NewApiAgentRunner(map[model.Provider]string{model.ProviderGemini: "key"})
	runner.GeminiBaseURL = server.URL

	reply, err := runner.Respond(context.Background(), model.NewAgent(model.ProviderGemini, "gemini-3.7-flash"), "t", "", "", nil, "")
	if err != nil {
		t.Fatalf("Respond() error = %v", err)
	}
	if reply.Content != "gemini reply" {
		t.Errorf("reply.Content = %q, want %q", reply.Content, "gemini reply")
	}
	if reply.TokensCached == nil || *reply.TokensCached != 30 {
		t.Errorf("reply.TokensCached = %v, want 30", reply.TokensCached)
	}
}

// CUSTOM has no API key concept at all — ApiKeys.ForProvider(CUSTOM) always returns "" (see
// settings.go), so the key check ahead of the provider dispatch always rejects it first.
// That matches the Kotlin ApiAgentRunner's actual behavior (same key-check-before-dispatch
// order there) — the CUSTOM-specific "CLI-only" message in the switch below is genuinely
// unreachable in both implementations, since the UI never lets a CUSTOM agent be RunMode.API
// in the first place. Documented here rather than silently assumed.
func TestRespondRejectsCustomProviderInApiMode(t *testing.T) {
	runner := NewApiAgentRunner(map[model.Provider]string{})
	agent := model.Agent{Provider: model.ProviderCustom, DisplayName: "Aider"}

	_, err := runner.Respond(context.Background(), agent, "t", "", "", nil, "")
	if err == nil || !strings.Contains(err.Error(), "no API key") {
		t.Errorf("error = %v, want the no-API-key message (CUSTOM never has a key configured)", err)
	}
}

func TestRespondRequiresAnApiKey(t *testing.T) {
	runner := NewApiAgentRunner(map[model.Provider]string{})
	agent := model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5")

	_, err := runner.Respond(context.Background(), agent, "t", "", "", nil, "")
	if err == nil || !strings.Contains(err.Error(), "no API key") {
		t.Errorf("error = %v, want a clear missing-key message", err)
	}
}

// Several personas on ONE provider must be told apart: only a seat's own turns are
// "assistant", and every line is attributed to its persona, not to the shared provider.
func TestSameProviderSeatsAreToldApart(t *testing.T) {
	var body struct {
		Messages []struct {
			Role    string `json:"role"`
			Content any    `json:"content"`
		} `json:"messages"`
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewDecoder(r.Body).Decode(&body)
		json.NewEncoder(w).Encode(anthropicResponse{Content: []struct {
			Text string `json:"text"`
		}{{Text: "ok"}}})
	}))
	defer server.Close()
	runner := NewApiAgentRunner(map[model.Provider]string{model.ProviderAnthropic: "k"})
	runner.URLs[model.ProviderAnthropic] = server.URL

	skeptic := model.NewAgent(model.ProviderAnthropic, "m")
	skeptic.ID = "seat-skeptic"
	optimist := model.NewAgent(model.ProviderAnthropic, "m")
	optimist.ID = "seat-optimist"
	transcript := []model.DebateMessage{
		{SeatID: "seat-optimist", AgentID: model.ProviderAnthropic, AuthorDisplayName: "Optimist", Content: "ship it"},
		{SeatID: "seat-skeptic", AgentID: model.ProviderAnthropic, AuthorDisplayName: "Skeptic", Content: "no"},
	}
	if _, err := runner.Respond(context.Background(), skeptic, "t", "", "", transcript, ""); err != nil {
		t.Fatal(err)
	}
	if len(body.Messages) != 2 || body.Messages[0].Role != "user" || body.Messages[1].Role != "assistant" {
		t.Fatalf("skeptic must see the optimist as 'user' and itself as 'assistant': %+v", body.Messages)
	}
	if first, _ := body.Messages[0].Content.(string); !strings.HasPrefix(first, "[Optimist]") {
		t.Fatalf("line must be attributed to the persona, got %v", body.Messages[0].Content)
	}
	_ = optimist
}

func TestOpenAISamplingParamsSentExceptForReasoningModels(t *testing.T) {
	var got map[string]any
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = nil
		json.NewDecoder(r.Body).Decode(&got)
		w.Write([]byte(`{"choices":[{"message":{"content":"ok"}}]}`))
	}))
	defer server.Close()
	r := NewApiAgentRunner(map[model.Provider]string{model.ProviderOpenAI: "k"})
	r.URLs[model.ProviderOpenAI] = server.URL

	f, p, temp := 0.3, 0.0, 0.7
	for modelName, want := range map[string]bool{"gpt-4o": true, "gpt-5": false, "o3-mini": false} {
		a := model.NewAgent(model.ProviderOpenAI, modelName)
		a.Temperature, a.FrequencyPenalty, a.PresencePenalty = &temp, &f, &p
		if _, err := r.Respond(context.Background(), a, "t", "", "", nil, ""); err != nil {
			t.Fatal(err)
		}
		_, hasT := got["temperature"]
		_, hasF := got["frequency_penalty"]
		_, hasP := got["presence_penalty"]
		if hasT != want || hasF != want || hasP != want {
			t.Errorf("%s: temperature/frequency/presence sent = %v/%v/%v, want all %v", modelName, hasT, hasF, hasP, want)
		}
	}
}

// captureBody serves a canned reply and records the last request body.
func captureBody(t *testing.T, reply string) (*httptest.Server, *map[string]any) {
	t.Helper()
	got := map[string]any{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		for k := range got {
			delete(got, k)
		}
		json.NewDecoder(r.Body).Decode(&got)
		w.Write([]byte(reply))
	}))
	t.Cleanup(server.Close)
	return server, &got
}

func samplingAgent(provider model.Provider, modelName string) model.Agent {
	a := model.NewAgent(provider, modelName)
	temp, topP, f, p := 0.7, 0.9, 0.3, 0.2
	a.Temperature, a.TopP, a.FrequencyPenalty, a.PresencePenalty = &temp, &topP, &f, &p
	return a
}

func TestGrokPenaltiesOnlyForNonReasoningModels(t *testing.T) {
	server, got := captureBody(t, `{"choices":[{"message":{"content":"ok"}}]}`)
	r := NewApiAgentRunner(map[model.Provider]string{model.ProviderGrok: "k"})
	r.URLs[model.ProviderGrok] = server.URL
	for modelName, want := range map[string]bool{
		"grok-4-fast": false, "grok-4": false, "grok-4.5": false, "grok-3-mini": false,
		"grok-4-fast-non-reasoning": true, "grok-3": true, "grok-2-1212": true,
	} {
		if _, err := r.Respond(context.Background(), samplingAgent(model.ProviderGrok, modelName), "t", "", "", nil, ""); err != nil {
			t.Fatal(err)
		}
		_, hasF := (*got)["frequency_penalty"]
		_, hasP := (*got)["presence_penalty"]
		if hasF != want || hasP != want {
			t.Errorf("%s: penalties sent = %v/%v, want %v", modelName, hasF, hasP, want)
		}
		if _, ok := (*got)["temperature"]; !ok {
			t.Errorf("%s: temperature must still be sent", modelName)
		}
	}
}

func TestDeepSeekAndMistralSendBothPenalties(t *testing.T) {
	server, got := captureBody(t, `{"choices":[{"message":{"content":"ok"}}]}`)
	for _, p := range []model.Provider{model.ProviderDeepSeek, model.ProviderMistral} {
		r := NewApiAgentRunner(map[model.Provider]string{p: "k"})
		r.URLs[p] = server.URL
		if _, err := r.Respond(context.Background(), samplingAgent(p, "some-model"), "t", "", "", nil, ""); err != nil {
			t.Fatal(err)
		}
		if (*got)["frequency_penalty"] != 0.3 || (*got)["presence_penalty"] != 0.2 || (*got)["top_p"] != 0.9 {
			t.Errorf("%s: body = %v, want both penalties and top_p", p, *got)
		}
	}
}

func TestOllamaOptionsMapPenalties(t *testing.T) {
	server, got := captureBody(t, `{"message":{"content":"ok"}}`)
	r := NewApiAgentRunner(map[model.Provider]string{model.ProviderOllama: server.URL})
	if _, err := r.Respond(context.Background(), samplingAgent(model.ProviderOllama, "llama3"), "t", "", "", nil, ""); err != nil {
		t.Fatal(err)
	}
	opts, _ := (*got)["options"].(map[string]any)
	if opts == nil {
		t.Fatalf("no options in body: %v", *got)
	}
	if opts["temperature"] != 0.7 || opts["top_p"] != 0.9 || opts["presence_penalty"] != 0.2 {
		t.Errorf("options = %v", opts)
	}
	if rp, _ := opts["repeat_penalty"].(float64); rp < 1.149 || rp > 1.151 {
		t.Errorf("repeat_penalty = %v, want 1 + 0.3*0.5 = 1.15", opts["repeat_penalty"])
	}
	if _, bad := opts["frequency_penalty"]; bad {
		t.Error("Ollama has no frequency_penalty; it must be mapped to repeat_penalty")
	}
}

func TestAnthropicSendsTemperatureAndTopPButNoPenalties(t *testing.T) {
	server, got := captureBody(t, `{"content":[{"text":"ok"}]}`)
	r := NewApiAgentRunner(map[model.Provider]string{model.ProviderAnthropic: "k"})
	r.URLs[model.ProviderAnthropic] = server.URL
	if _, err := r.Respond(context.Background(), samplingAgent(model.ProviderAnthropic, "claude-sonnet-5-5"), "t", "", "", nil, ""); err != nil {
		t.Fatal(err)
	}
	if (*got)["temperature"] != 0.7 || (*got)["top_p"] != 0.9 {
		t.Errorf("body = %v, want temperature and top_p", *got)
	}
	for _, k := range []string{"frequency_penalty", "presence_penalty"} {
		if _, bad := (*got)[k]; bad {
			t.Errorf("Anthropic has no %s", k)
		}
	}
}

func TestGeminiSendsGenerationConfigButNoPenalties(t *testing.T) {
	server, got := captureBody(t, `{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}`)
	r := NewApiAgentRunner(map[model.Provider]string{model.ProviderGemini: "k"})
	r.GeminiBaseURL = server.URL
	if _, err := r.Respond(context.Background(), samplingAgent(model.ProviderGemini, "gemini-3.7-flash"), "t", "", "", nil, ""); err != nil {
		t.Fatal(err)
	}
	cfg, _ := (*got)["generationConfig"].(map[string]any)
	if cfg == nil || cfg["temperature"] != 0.7 || cfg["topP"] != 0.9 {
		t.Errorf("generationConfig = %v, want temperature and topP", cfg)
	}
	if _, bad := cfg["frequencyPenalty"]; bad {
		t.Error("Gemini must not get penalties")
	}
}
