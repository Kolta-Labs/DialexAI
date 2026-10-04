package model

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// LocalInvoker handles 100% air-gapped / offline local model servers (Ollama, vLLM, LocalAI).
type LocalInvoker struct {
	config StageConfig
	client *http.Client
}

// NewLocalInvoker creates an invoker for local model endpoints.
func NewLocalInvoker(config StageConfig) *LocalInvoker {
	endpoint := config.Endpoint
	if endpoint == "" {
		endpoint = "http://localhost:11434"
	}
	config.Endpoint = endpoint

	return &LocalInvoker{
		config: config,
		client: &http.Client{Timeout: 3 * time.Second},
	}
}

// ollamaTagsResponse maps Ollama /api/tags endpoint.
type ollamaTagsResponse struct {
	Models []struct {
		Name       string `json:"name"`
		Model      string `json:"model"`
		ModifiedAt string `json:"modified_at"`
		Size       int64  `json:"size"`
		Details    struct {
			Family     string `json:"family"`
			Parameter  string `json:"parameter_size"`
			QuantLevel string `json:"quantization_level"`
		} `json:"details"`
	} `json:"models"`
}

// DiscoverLocalModels queries the local Ollama or vLLM daemon for installed models.
func DiscoverLocalModels(ctx context.Context, endpoint string) ([]ModelDescriptor, error) {
	if endpoint == "" {
		endpoint = "http://localhost:11434"
	}
	endpoint = strings.TrimSuffix(endpoint, "/")

	client := &http.Client{Timeout: 3 * time.Second}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint+"/api/tags", nil)
	if err != nil {
		return nil, err
	}

	resp, err := client.Do(req)
	if err != nil {
		// Try standard /v1/models if Ollama tags failed
		return discoverVLLMModels(ctx, endpoint, client)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return discoverVLLMModels(ctx, endpoint, client)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var tagsResp ollamaTagsResponse
	if err := json.Unmarshal(body, &tagsResp); err != nil {
		return nil, err
	}

	var descriptors []ModelDescriptor
	for _, m := range tagsResp.Models {
		nameLower := strings.ToLower(m.Name)
		isVision := strings.Contains(nameLower, "vl") ||
			strings.Contains(nameLower, "vision") ||
			strings.Contains(nameLower, "paligemma") ||
			strings.Contains(nameLower, "llava")

		desc := fmt.Sprintf("%s (%s, %s)", m.Name, m.Details.Parameter, m.Details.QuantLevel)

		descriptors = append(descriptors, ModelDescriptor{
			ID:            "local:" + m.Name,
			Name:          m.Name,
			Provider:      ProviderOllama,
			Mode:          ModeLocal,
			VisionCapable: isVision,
			Available:     true,
			Description:   desc,
		})
	}
	return descriptors, nil
}

func discoverVLLMModels(ctx context.Context, endpoint string, client *http.Client) ([]ModelDescriptor, error) {
	v1URL := endpoint + "/v1/models"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, v1URL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("local daemon unreachable at %s: %w", endpoint, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("local /v1/models returned HTTP %d", resp.StatusCode)
	}

	var v1Resp struct {
		Data []struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&v1Resp); err != nil {
		return nil, err
	}

	var descriptors []ModelDescriptor
	for _, m := range v1Resp.Data {
		nameLower := strings.ToLower(m.ID)
		isVision := strings.Contains(nameLower, "vl") || strings.Contains(nameLower, "vision")

		descriptors = append(descriptors, ModelDescriptor{
			ID:            "local:" + m.ID,
			Name:          m.ID,
			Provider:      ProviderVLLM,
			Mode:          ModeLocal,
			VisionCapable: isVision,
			Available:     true,
			Description:   "Local vLLM / OpenAI-compatible model",
		})
	}
	return descriptors, nil
}

// ollamaChatRequest represents the Ollama native chat completion format.
type ollamaChatRequest struct {
	Model    string               `json:"model"`
	Messages []ollamaChatMessage  `json:"messages"`
	Stream   bool                 `json:"stream"`
	Options  map[string]interface{} `json:"options,omitempty"`
}

type ollamaChatMessage struct {
	Role    string   `json:"role"`
	Content string   `json:"content"`
	Images  []string `json:"images,omitempty"` // base64 strings
}

type ollamaChatResponse struct {
	Model      string `json:"model"`
	Message    struct {
		Role    string `json:"role"`
		Content string `json:"content"`
	} `json:"message"`
	Done       bool   `json:"done"`
	TotalDuration int64 `json:"total_duration"`
	PromptEvalCount int `json:"prompt_eval_count"`
	EvalCount       int `json:"eval_count"`
}

func (l *LocalInvoker) Invoke(ctx context.Context, req Request) (*Response, error) {
	start := time.Now()
	endpoint := strings.TrimSuffix(l.config.Endpoint, "/")

	// Format messages for Ollama native or vLLM /v1
	var oMsgs []ollamaChatMessage
	for _, m := range req.Messages {
		msg := ollamaChatMessage{
			Role:    m.Role,
			Content: m.Content,
		}
		if m.ImageBase64 != "" {
			msg.Images = []string{m.ImageBase64}
		}
		oMsgs = append(oMsgs, msg)
	}

	modelName := l.config.Model
	if modelName == "" {
		modelName = "qwen2.5-coder:latest"
	}

	chatReq := ollamaChatRequest{
		Model:    modelName,
		Messages: oMsgs,
		Stream:   false,
	}
	if req.Temperature > 0 {
		chatReq.Options = map[string]interface{}{
			"temperature": req.Temperature,
		}
	}

	reqBytes, err := json.Marshal(chatReq)
	if err != nil {
		return nil, fmt.Errorf("failed to encode ollama request: %w", err)
	}

	postURL := endpoint + "/api/chat"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, postURL, bytes.NewReader(reqBytes))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := l.client.Do(httpReq)
	if err != nil {
		// Fallback to OpenAI-compatible v1 invoker for local vLLM/LocalAI
		v1Invoker := NewAPIInvoker(StageConfig{
			Mode:     ModeLocal,
			Provider: ProviderVLLM,
			Model:    modelName,
			Endpoint: endpoint + "/v1/chat/completions",
		})
		return v1Invoker.Invoke(ctx, req)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		respBody, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("ollama returned %d: %s", resp.StatusCode, string(respBody))
	}

	var oResp ollamaChatResponse
	if err := json.NewDecoder(resp.Body).Decode(&oResp); err != nil {
		return nil, fmt.Errorf("failed to decode ollama response: %w", err)
	}

	return &Response{
		Content:      strings.TrimSpace(oResp.Message.Content),
		Model:        oResp.Model,
		Provider:     ProviderOllama,
		Mode:         ModeLocal,
		PromptTokens: oResp.PromptEvalCount,
		OutputTokens: oResp.EvalCount,
		Duration:     time.Since(start),
	}, nil
}
