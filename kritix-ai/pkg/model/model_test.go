package model

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestDefaultRouterConfig(t *testing.T) {
	cfg := DefaultRouterConfig()
	if cfg.DefaultMode != ModeLocal {
		t.Fatalf("expected default mode local, got %s", cfg.DefaultMode)
	}

	stages := []Stage{StagePO, StageExplorer, StageSDET, StageSecurity, StageTriage}
	for _, s := range stages {
		stageCfg, ok := cfg.Stages[s]
		if !ok {
			t.Fatalf("stage %s not found in default config", s)
		}
		if stageCfg.Stage != s {
			t.Fatalf("expected stage %s, got %s", s, stageCfg.Stage)
		}
	}
}

func TestRouterStageFallback(t *testing.T) {
	// Setup a mock server that fails on primary and succeeds on fallback
	primaryServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}))
	defer primaryServer.Close()

	fallbackServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"choices": [{"message": {"content": "fallback success"}}],
			"usage": {"prompt_tokens": 10, "completion_tokens": 5}
		}`))
	}))
	defer fallbackServer.Close()

	router := NewRouter(RouterConfig{
		DefaultMode: ModeAPI,
		Stages: map[Stage]StageConfig{
			StagePO: {
				Stage:    StagePO,
				Mode:     ModeAPI,
				Provider: ProviderCustom,
				Model:    "failing-primary",
				Endpoint: primaryServer.URL,
				Fallback: &StageConfig{
					Stage:    StagePO,
					Mode:     ModeAPI,
					Provider: ProviderCustom,
					Model:    "working-fallback",
					Endpoint: fallbackServer.URL,
				},
			},
		},
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	resp, err := router.InvokeStage(ctx, StagePO, Request{
		Messages: []Message{{Role: "user", Content: "Hello"}},
	})
	if err != nil {
		t.Fatalf("expected fallback to succeed, got error: %v", err)
	}

	if resp.Content != "fallback success" {
		t.Fatalf("expected 'fallback success', got %q", resp.Content)
	}
}

func TestAPIInvoker(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-key" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"choices": [{"message": {"content": "assert status 200"}}],
			"usage": {"prompt_tokens": 15, "completion_tokens": 8}
		}`))
	}))
	defer server.Close()

	invoker := NewAPIInvoker(StageConfig{
		Mode:     ModeAPI,
		Provider: ProviderOpenAI,
		Model:    "gpt-4o",
		Endpoint: server.URL,
		APIKey:   "test-key",
	})

	ctx := context.Background()
	resp, err := invoker.Invoke(ctx, Request{
		Messages: []Message{
			{Role: "system", Content: "You are an SDET"},
			{Role: "user", Content: "Generate test"},
		},
	})

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if resp.Content != "assert status 200" {
		t.Fatalf("expected 'assert status 200', got %q", resp.Content)
	}
	if resp.PromptTokens != 15 || resp.OutputTokens != 8 {
		t.Fatalf("unexpected token counts: prompt=%d, out=%d", resp.PromptTokens, resp.OutputTokens)
	}
}

func TestLocalModelDiscovery(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"models": [
				{
					"name": "qwen2.5-coder:latest",
					"model": "qwen2.5-coder",
					"details": {"family": "qwen2", "parameter_size": "7B", "quantization_level": "Q4_0"}
				},
				{
					"name": "qwen2.5-vl:latest",
					"model": "qwen2.5-vl",
					"details": {"family": "qwen2-vl", "parameter_size": "7B", "quantization_level": "Q4_K_M"}
				}
			]
		}`))
	}))
	defer server.Close()

	models, err := DiscoverLocalModels(context.Background(), server.URL)
	if err != nil {
		t.Fatalf("discovery failed: %v", err)
	}

	if len(models) != 2 {
		t.Fatalf("expected 2 models, got %d", len(models))
	}

	// Verify vision capability detection
	if models[0].VisionCapable {
		t.Errorf("qwen2.5-coder should not be flagged as vision capable")
	}
	if !models[1].VisionCapable {
		t.Errorf("qwen2.5-vl should be flagged as vision capable")
	}
}
