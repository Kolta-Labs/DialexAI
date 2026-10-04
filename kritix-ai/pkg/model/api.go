package model

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// APIInvoker handles direct REST calls to cloud API providers.
type APIInvoker struct {
	config StageConfig
	client *http.Client
}

// NewAPIInvoker creates an invoker for direct API calls.
func NewAPIInvoker(config StageConfig) *APIInvoker {
	return &APIInvoker{
		config: config,
		client: apiClient(),
	}
}

// openAIChatRequest defines the JSON schema for standard OpenAI-compatible endpoints.
type openAIChatRequest struct {
	Model       string              `json:"model"`
	Messages    []openAIChatMessage `json:"messages"`
	Temperature float64             `json:"temperature,omitempty"`
	MaxTokens   int                 `json:"max_tokens,omitempty"`
}

type openAIChatMessage struct {
	Role    string      `json:"role"`
	Content interface{} `json:"content"` // string or []openAIContentPart
}

type openAIContentPart struct {
	Type     string          `json:"type"` // "text" or "image_url"
	Text     string          `json:"text,omitempty"`
	ImageURL *openAIImageURL `json:"image_url,omitempty"`
}

type openAIImageURL struct {
	URL string `json:"url"` // data:image/png;base64,...
}

type openAIChatResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
	Usage struct {
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
	} `json:"usage"`
	Error *struct {
		Message string `json:"message"`
	} `json:"error,omitempty"`
}

func (a *APIInvoker) Invoke(ctx context.Context, req Request) (*Response, error) {
	start := time.Now()
	endpoint := a.resolveEndpoint()

	// Format messages
	var openAIMsgs []openAIChatMessage
	for _, m := range req.Messages {
		if m.ImageBase64 != "" {
			mime := m.ImageMime
			if mime == "" {
				mime = "image/png"
			}
			parts := []openAIContentPart{
				{Type: "text", Text: m.Content},
				{
					Type: "image_url",
					ImageURL: &openAIImageURL{
						URL: fmt.Sprintf("data:%s;base64,%s", mime, m.ImageBase64),
					},
				},
			}
			openAIMsgs = append(openAIMsgs, openAIChatMessage{Role: m.Role, Content: parts})
		} else {
			openAIMsgs = append(openAIMsgs, openAIChatMessage{Role: m.Role, Content: m.Content})
		}
	}

	body := openAIChatRequest{
		Model:       a.config.Model,
		Messages:    openAIMsgs,
		Temperature: req.Temperature,
		MaxTokens:   req.MaxTokens,
	}

	jsonBytes, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal request: %w", err)
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(jsonBytes))
	if err != nil {
		return nil, fmt.Errorf("failed to create http request: %w", err)
	}

	httpReq.Header.Set("Content-Type", "application/json")
	if a.config.APIKey != "" {
		httpReq.Header.Set("Authorization", "Bearer "+a.config.APIKey)
	}

	resp, err := a.client.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("http request failed: %w", err)
	}
	defer resp.Body.Close()

	respBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("api returned status %d: %s", resp.StatusCode, string(respBytes))
	}

	var chatResp openAIChatResponse
	if err := json.Unmarshal(respBytes, &chatResp); err != nil {
		return nil, fmt.Errorf("failed to parse response: %w", err)
	}

	if chatResp.Error != nil && chatResp.Error.Message != "" {
		return nil, fmt.Errorf("api error: %s", chatResp.Error.Message)
	}

	content := ""
	if len(chatResp.Choices) > 0 {
		content = chatResp.Choices[0].Message.Content
	}

	return &Response{
		Content:      strings.TrimSpace(content),
		Model:        a.config.Model,
		Provider:     a.config.Provider,
		Mode:         ModeAPI,
		PromptTokens: chatResp.Usage.PromptTokens,
		OutputTokens: chatResp.Usage.CompletionTokens,
		Duration:     time.Since(start),
	}, nil
}

func (a *APIInvoker) resolveEndpoint() string {
	if a.config.Endpoint != "" {
		ep := a.config.Endpoint
		if !strings.HasSuffix(ep, "/chat/completions") {
			ep = strings.TrimSuffix(ep, "/") + "/chat/completions"
		}
		return ep
	}
	switch a.config.Provider {
	case ProviderDeepSeek:
		return "https://api.deepseek.com/chat/completions"
	case ProviderOpenAI:
		return "https://api.openai.com/v1/chat/completions"
	default:
		return "https://api.openai.com/v1/chat/completions"
	}
}

// apiClient honours KRITIX_AIRGAP=true / KRITIX_EGRESS_ALLOW=host1,host2: only listed hosts (and loopback) are reachable.
func apiClient() *http.Client {
	allow := os.Getenv("KRITIX_EGRESS_ALLOW")
	if os.Getenv("KRITIX_AIRGAP") != "true" && allow == "" {
		return &http.Client{Timeout: 90 * time.Second}
	}
	return NewEgressClient(strings.Split(allow, ","), 90*time.Second)
}
