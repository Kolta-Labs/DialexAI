package runner

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"socratix/pkg/model"
)

// ApiAgentRunner calls each provider's REST API directly with a key from the global
// Settings screen (one key per provider — every API-mode agent on that provider shares it).
// One HTTP call per turn, full transcript sent as conversation history every time (no
// server-side session).
type ApiAgentRunner struct {
	ApiKeys map[model.Provider]string
	Client  *http.Client
	// URLs are overridable per provider (defaulted by NewApiAgentRunner) so tests can point
	// them at an httptest.Server instead of the real internet. GeminiBaseURL excludes the
	// trailing `/models/<model>:generateContent?key=<key>` — that part is built per call
	// since it embeds the model name.
	URLs          map[model.Provider]string
	GeminiBaseURL string
	Permissions   *model.PermissionConfig
}

// NewApiAgentRunner builds a runner with a default 120s timeout, matching CliAgentRunner's
// default, so a hung provider call cannot block a turn indefinitely.
func NewApiAgentRunner(apiKeys map[model.Provider]string) *ApiAgentRunner {
	return NewApiAgentRunnerWithPermissions(apiKeys, nil)
}

// NewApiAgentRunnerWithPermissions builds a runner with explicit permissions governance.
func NewApiAgentRunnerWithPermissions(apiKeys map[model.Provider]string, permissions *model.PermissionConfig) *ApiAgentRunner {
	return &ApiAgentRunner{
		ApiKeys:     apiKeys,
		Permissions: permissions,
		Client:      &http.Client{Timeout: 120 * time.Second},
		URLs: map[model.Provider]string{
			model.ProviderAnthropic: "https://api.anthropic.com/v1/messages",
			model.ProviderOpenAI:    "https://api.openai.com/v1/chat/completions",
			model.ProviderGrok:      "https://api.x.ai/v1/chat/completions",
			model.ProviderDeepSeek:  "https://api.deepseek.com/chat/completions",
			model.ProviderMistral:   "https://api.mistral.ai/v1/chat/completions",
		},
		GeminiBaseURL: "https://generativelanguage.googleapis.com/v1beta/models",
	}
}

func (r *ApiAgentRunner) Respond(
	ctx context.Context,
	agent model.Agent,
	topic, commonContext, commonInstructions string,
	transcript []model.DebateMessage,
	modelOverride string,
) (AgentReply, error) {
	key := r.ApiKeys[agent.Provider]
	if key == "" && agent.Provider != model.ProviderOllama {
		return AgentReply{}, fmt.Errorf("no API key set for %s in Settings", agent.Provider)
	}
	if key == "" && agent.Provider == model.ProviderOllama {
		key = "http://localhost:11434"
	}
	sharedSystem, agentSystem := buildSplitSystemPrompt(topic, commonContext, commonInstructions, agent)
	fullSystem := sharedSystem
	if agentSystem != "" {
		if fullSystem != "" {
			fullSystem += "\n" + agentSystem
		} else {
			fullSystem = agentSystem
		}
	}
	effective := agent
	if modelOverride != "" {
		effective.Model = modelOverride
	}

	switch agent.Provider {
	case model.ProviderAnthropic:
		return r.callAnthropic(ctx, effective, key, sharedSystem, agentSystem, transcript)
	// xAI, DeepSeek, and Mistral all expose an OpenAI-compatible /chat/completions
	// endpoint — one implementation, just a different base URL and bearer key per
	// provider, instead of three more bespoke request/response parsers.
	case model.ProviderOpenAI, model.ProviderGrok, model.ProviderDeepSeek, model.ProviderMistral:
		return r.callOpenAICompatible(ctx, effective, key, fullSystem, transcript, r.URLs[agent.Provider])
	case model.ProviderGemini:
		return r.callGemini(ctx, effective, key, fullSystem, transcript)
	case model.ProviderOllama:
		return r.callOllama(ctx, effective, key, fullSystem, transcript)
	default:
		// No fixed API shape for an arbitrary CLI tool — CUSTOM is CLI-only, callers
		// should never route it here (blankAgent forces RunMode=CLI for it).
		return AgentReply{}, fmt.Errorf("%s has no API mode — this agent should be CLI-only", agent.Provider)
	}
}

func buildSplitSystemPrompt(topic, commonContext, commonInstructions string, agent model.Agent) (string, string) {
	var sb bytes.Buffer
	if topic != "" {
		fmt.Fprintf(&sb, "Topic: %s\n", topic)
	}
	if commonContext != "" {
		fmt.Fprintf(&sb, "Context: %s\n", commonContext)
	}
	if commonInstructions != "" {
		fmt.Fprintf(&sb, "Rules: %s\n", commonInstructions)
	}
	shared := strings.TrimSpace(sb.String())

	var ab bytes.Buffer
	if agent.Context != "" {
		fmt.Fprintf(&ab, "%s\n", agent.Context)
	}
	if agent.SystemPrompt != "" {
		fmt.Fprintf(&ab, "%s\n", agent.SystemPrompt)
	}
	agentSpecific := strings.TrimSpace(ab.String())
	return shared, agentSpecific
}

func buildSystemPrompt(topic, commonContext, commonInstructions string, agent model.Agent) string {
	s, a := buildSplitSystemPrompt(topic, commonContext, commonInstructions, agent)
	if s != "" && a != "" {
		return s + "\n" + a
	}
	return s + a
}

// postJSON posts body as JSON and decodes the response into out. A non-2xx response is
// surfaced as a clear "HTTP <status>: <body>" error instead of letting the caller try to
// parse an error body as a success shape.
func postJSON(ctx context.Context, client *http.Client, url string, headers map[string]string, body any, out any) error {
	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		text := string(respBody)
		if len(text) > 500 {
			text = text[:500]
		}
		if text == "" {
			text = "(no response body)"
		}
		return fmt.Errorf("HTTP %d: %s", resp.StatusCode, text)
	}
	return json.Unmarshal(respBody, out)
}

// --- Anthropic ---

type anthropicCacheControl struct {
	Type string `json:"type"`
}

type anthropicContentBlock struct {
	Type         string                 `json:"type"`
	Text         string                 `json:"text"`
	CacheControl *anthropicCacheControl `json:"cache_control,omitempty"`
}

type anthropicMessage struct {
	Role    string      `json:"role"`
	Content interface{} `json:"content"`
}

type anthropicSystemBlock struct {
	Type         string                 `json:"type"`
	Text         string                 `json:"text"`
	CacheControl *anthropicCacheControl `json:"cache_control,omitempty"`
}

type anthropicRequest struct {
	Model       string                 `json:"model"`
	MaxTokens   int                    `json:"max_tokens"`
	Temperature *float64               `json:"temperature,omitempty"`
	TopP        *float64               `json:"top_p,omitempty"`
	System      []anthropicSystemBlock `json:"system"`
	Messages    []anthropicMessage     `json:"messages"`
}

type AnthropicUsage struct {
	InputTokens              int `json:"input_tokens"`
	OutputTokens             int `json:"output_tokens"`
	CacheCreationInputTokens int `json:"cache_creation_input_tokens"`
	CacheReadInputTokens     int `json:"cache_read_input_tokens"`
}

type anthropicResponse struct {
	Content []struct {
		Text string `json:"text"`
	} `json:"content"`
	Usage *AnthropicUsage `json:"usage"`
}

func (r *ApiAgentRunner) callAnthropic(ctx context.Context, agent model.Agent, key, sharedSystem, agentSystem string, transcript []model.DebateMessage) (AgentReply, error) {
	cache := &anthropicCacheControl{Type: "ephemeral"}
	messages := make([]anthropicMessage, len(transcript))
	for i, m := range transcript {
		role := "user"
		if isOwnTurn(m, agent) {
			role = "assistant"
		}
		text := fmt.Sprintf("[%s] %s", speakerLabel(m), m.Content)
		// Every call resends the full transcript so far — each one is a strict prefix of
		// the next. Marking the last message as a cache breakpoint means Anthropic reuses
		// the (large, ever-growing) prefix instead of reprocessing it from scratch every
		// single turn.
		if i == len(transcript)-1 {
			messages[i] = anthropicMessage{Role: role, Content: []anthropicContentBlock{{Type: "text", Text: text, CacheControl: cache}}}
		} else {
			messages[i] = anthropicMessage{Role: role, Content: text}
		}
	}

	var systemBlocks []anthropicSystemBlock
	if sharedSystem != "" {
		// System prompt is identical across every turn and agent in a discussion — caching it
		// separately from agent persona means it's reused across all turns and agents.
		systemBlocks = append(systemBlocks, anthropicSystemBlock{Type: "text", Text: sharedSystem, CacheControl: cache})
	}
	if agentSystem != "" {
		systemBlocks = append(systemBlocks, anthropicSystemBlock{Type: "text", Text: agentSystem})
	}

	maxTokens := 4096
	if agent.MaxTokens != nil {
		maxTokens = *agent.MaxTokens
	}
	var temp *float64
	if agent.Temperature != nil {
		t := *agent.Temperature
		if t < 0.0 {
			t = 0.0
		}
		if t > 1.0 {
			t = 1.0
		}
		temp = &t
	}
	var topP *float64
	if agent.TopP != nil {
		p := *agent.TopP
		if p < 0.0 {
			p = 0.0
		}
		if p > 1.0 {
			p = 1.0
		}
		topP = &p
	}

	body := anthropicRequest{
		Model:       agent.Model,
		MaxTokens:   maxTokens,
		Temperature: temp,
		TopP:        topP,
		System:      systemBlocks,
		Messages:    messages,
	}
	var resp anthropicResponse
	err := postJSON(ctx, r.Client, r.URLs[model.ProviderAnthropic], map[string]string{
		"x-api-key":         key,
		"anthropic-version": "2023-06-01",
	}, body, &resp)
	if err != nil {
		return AgentReply{}, err
	}
	if len(resp.Content) == 0 {
		return AgentReply{}, fmt.Errorf("anthropic response had no content")
	}
	reply := AgentReply{Content: resp.Content[0].Text}
	if resp.Usage != nil {
		reply.TokensIn = intPtr(resp.Usage.InputTokens)
		reply.TokensOut = intPtr(resp.Usage.OutputTokens)
		if resp.Usage.CacheReadInputTokens > 0 {
			reply.TokensCached = intPtr(resp.Usage.CacheReadInputTokens)
		}
	}
	return reply, nil
}

// --- OpenAI-compatible (OpenAI, xAI, DeepSeek, Mistral) ---

type chatMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type chatRequest struct {
	Model       string        `json:"model"`
	Temperature *float64      `json:"temperature,omitempty"`
	TopP        *float64      `json:"top_p,omitempty"`
	MaxTokens   *int          `json:"max_tokens,omitempty"`
	Messages    []chatMessage `json:"messages"`
}

type ChatUsage struct {
	PromptTokens        int `json:"prompt_tokens"`
	CompletionTokens    int `json:"completion_tokens"`
	PromptTokensDetails *struct {
		CachedTokens int `json:"cached_tokens"`
	} `json:"prompt_tokens_details"`
}

type chatResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
	Usage *ChatUsage `json:"usage"`
}

func (r *ApiAgentRunner) callOpenAICompatible(ctx context.Context, agent model.Agent, key, system string, transcript []model.DebateMessage, url string) (AgentReply, error) {
	messages := []chatMessage{{Role: "system", Content: system}}
	for _, m := range transcript {
		role := "user"
		if isOwnTurn(m, agent) {
			role = "assistant"
		}
		messages = append(messages, chatMessage{Role: role, Content: fmt.Sprintf("[%s] %s", speakerLabel(m), m.Content)})
	}
	body := chatRequest{
		Model:       agent.Model,
		Temperature: agent.Temperature,
		TopP:        agent.TopP,
		MaxTokens:   agent.MaxTokens,
		Messages:    messages,
	}
	var resp chatResponse
	err := postJSON(ctx, r.Client, url, map[string]string{"Authorization": "Bearer " + key}, body, &resp)
	if err != nil {
		return AgentReply{}, err
	}
	if len(resp.Choices) == 0 {
		return AgentReply{}, fmt.Errorf("empty choices in response from %s", url)
	}
	reply := AgentReply{Content: resp.Choices[0].Message.Content}
	if resp.Usage != nil {
		reply.TokensIn = intPtr(resp.Usage.PromptTokens)
		reply.TokensOut = intPtr(resp.Usage.CompletionTokens)
		if resp.Usage.PromptTokensDetails != nil && resp.Usage.PromptTokensDetails.CachedTokens > 0 {
			reply.TokensCached = intPtr(resp.Usage.PromptTokensDetails.CachedTokens)
		}
	}
	return reply, nil
}

// --- Gemini ---

type geminiPart struct {
	Text string `json:"text"`
}

type geminiContent struct {
	Role  string       `json:"role,omitempty"`
	Parts []geminiPart `json:"parts"`
}

type geminiGenerationConfig struct {
	Temperature     *float64 `json:"temperature,omitempty"`
	TopP            *float64 `json:"topP,omitempty"`
	MaxOutputTokens *int     `json:"maxOutputTokens,omitempty"`
}

type geminiRequest struct {
	SystemInstruction *geminiContent           `json:"systemInstruction,omitempty"`
	Contents          []geminiContent          `json:"contents"`
	GenerationConfig  *geminiGenerationConfig  `json:"generationConfig,omitempty"`
	Tools             []map[string]interface{} `json:"tools,omitempty"`
}

type GeminiUsageMetadata struct {
	PromptTokenCount        int `json:"promptTokenCount"`
	CandidatesTokenCount    int `json:"candidatesTokenCount"`
	CachedContentTokenCount int `json:"cachedContentTokenCount"`
}

type geminiCandidate struct {
	Content struct {
		Parts []geminiPart `json:"parts"`
	} `json:"content"`
	GroundingMetadata *struct {
		WebSearchQueries []string `json:"webSearchQueries"`
		GroundingChunks  []struct {
			Web struct {
				URI   string `json:"uri"`
				Title string `json:"title"`
			} `json:"web"`
		} `json:"groundingChunks"`
	} `json:"groundingMetadata"`
}

type geminiResponse struct {
	Candidates    []geminiCandidate    `json:"candidates"`
	UsageMetadata *GeminiUsageMetadata `json:"usageMetadata"`
}

func (r *ApiAgentRunner) callGemini(ctx context.Context, agent model.Agent, key, system string, transcript []model.DebateMessage) (AgentReply, error) {
	contents := make([]geminiContent, len(transcript))
	for i, m := range transcript {
		role := "user"
		if isOwnTurn(m, agent) {
			role = "model"
		}
		contents[i] = geminiContent{Role: role, Parts: []geminiPart{{Text: fmt.Sprintf("[%s] %s", speakerLabel(m), m.Content)}}}
	}
	var genConfig *geminiGenerationConfig
	if agent.Temperature != nil || agent.TopP != nil || agent.MaxTokens != nil {
		genConfig = &geminiGenerationConfig{
			Temperature:     agent.Temperature,
			TopP:            agent.TopP,
			MaxOutputTokens: agent.MaxTokens,
		}
	}
	body := geminiRequest{
		SystemInstruction: &geminiContent{Parts: []geminiPart{{Text: system}}},
		Contents:          contents,
		GenerationConfig:  genConfig,
	}

	webSearchAllowed := true
	if r.Permissions != nil {
		webSearchAllowed = r.Permissions.IsWebSearchAllowedFor(string(agent.ID))
	}
	if agent.AllowWebSearch != nil {
		webSearchAllowed = *agent.AllowWebSearch
	}
	if webSearchAllowed {
		body.Tools = []map[string]interface{}{
			{"google_search": map[string]interface{}{}},
		}
	}

	url := fmt.Sprintf("%s/%s:generateContent?key=%s", r.GeminiBaseURL, agent.Model, key)
	var resp geminiResponse
	if err := postJSON(ctx, r.Client, url, nil, body, &resp); err != nil {
		return AgentReply{}, err
	}
	if len(resp.Candidates) == 0 || len(resp.Candidates[0].Content.Parts) == 0 {
		return AgentReply{}, fmt.Errorf("gemini response had no candidates")
	}
	reply := AgentReply{Content: resp.Candidates[0].Content.Parts[0].Text}
	if len(resp.Candidates) > 0 && resp.Candidates[0].GroundingMetadata != nil {
		var sources []string
		seen := make(map[string]bool)
		for _, chunk := range resp.Candidates[0].GroundingMetadata.GroundingChunks {
			uri := strings.TrimSpace(chunk.Web.URI)
			title := strings.TrimSpace(chunk.Web.Title)
			if uri != "" && !seen[uri] {
				seen[uri] = true
				if title != "" {
					sources = append(sources, fmt.Sprintf("[%s](%s)", title, uri))
				} else {
					sources = append(sources, uri)
				}
			}
		}
		if len(sources) > 0 {
			reply.GroundingSources = sources
			reply.Content += "\n\n> 🌐 **Verified Web Sources:**\n"
			for _, s := range sources {
				reply.Content += fmt.Sprintf("> - %s\n", s)
			}
		}
	}

	if resp.UsageMetadata != nil {
		reply.TokensIn = intPtr(resp.UsageMetadata.PromptTokenCount)
		reply.TokensOut = intPtr(resp.UsageMetadata.CandidatesTokenCount)
		if resp.UsageMetadata.CachedContentTokenCount > 0 {
			reply.TokensCached = intPtr(resp.UsageMetadata.CachedContentTokenCount)
		}
	}
	return reply, nil
}

// --- Ollama ---

type ollamaMessage struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type ollamaOptions struct {
	Temperature *float64 `json:"temperature,omitempty"`
	TopP        *float64 `json:"top_p,omitempty"`
	NumPredict  *int     `json:"num_predict,omitempty"`
}

type ollamaRequest struct {
	Model    string          `json:"model"`
	Messages []ollamaMessage `json:"messages"`
	Stream   bool            `json:"stream"`
	Options  *ollamaOptions  `json:"options,omitempty"`
}

type ollamaResponse struct {
	Message struct {
		Content string `json:"content"`
	} `json:"message"`
	PromptEvalCount int `json:"prompt_eval_count"`
	EvalCount       int `json:"eval_count"`
}

func (r *ApiAgentRunner) callOllama(ctx context.Context, agent model.Agent, endpoint, system string, transcript []model.DebateMessage) (AgentReply, error) {
	url := strings.TrimRight(endpoint, "/")
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		url = "http://" + url
	}
	if !strings.HasSuffix(url, "/api/chat") {
		url += "/api/chat"
	}

	var messages []ollamaMessage
	if system != "" {
		messages = append(messages, ollamaMessage{Role: "system", Content: system})
	}
	for _, m := range transcript {
		role := "user"
		if isOwnTurn(m, agent) {
			role = "assistant"
		}
		messages = append(messages, ollamaMessage{Role: role, Content: fmt.Sprintf("[%s] %s", speakerLabel(m), m.Content)})
	}

	var opts *ollamaOptions
	if agent.Temperature != nil || agent.TopP != nil || agent.MaxTokens != nil {
		opts = &ollamaOptions{
			Temperature: agent.Temperature,
			TopP:        agent.TopP,
			NumPredict:  agent.MaxTokens,
		}
	}

	body := ollamaRequest{
		Model:    agent.Model,
		Messages: messages,
		Stream:   false,
		Options:  opts,
	}

	var resp ollamaResponse
	if err := postJSON(ctx, r.Client, url, nil, body, &resp); err != nil {
		return AgentReply{}, err
	}

	reply := AgentReply{Content: resp.Message.Content}
	if resp.PromptEvalCount > 0 {
		reply.TokensIn = intPtr(resp.PromptEvalCount)
	}
	if resp.EvalCount > 0 {
		reply.TokensOut = intPtr(resp.EvalCount)
	}
	return reply, nil
}
