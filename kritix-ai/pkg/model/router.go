package model

import (
	"context"
	"fmt"
	"sync"
)

// Router orchestrates model invocation per stage with fallback support.
type Router struct {
	mu     sync.RWMutex
	config RouterConfig
}

// NewRouter constructs a StageRouter with the provided configuration.
func NewRouter(cfg RouterConfig) *Router {
	if cfg.Stages == nil {
		cfg.Stages = make(map[Stage]StageConfig)
	}
	return &Router{config: cfg}
}

// DefaultRouterConfig returns a recommended production default router configuration.
func DefaultRouterConfig() RouterConfig {
	return RouterConfig{
		DefaultMode:     ModeLocal,
		DefaultEndpoint: "http://localhost:11434",
		Stages: map[Stage]StageConfig{
			StagePO: {
				Stage:    StagePO,
				Mode:     ModeLocal,
				Provider: ProviderOllama,
				Model:    "qwen2.5-coder:latest",
				Fallback: &StageConfig{
					Stage:     StagePO,
					Mode:      ModeCLI,
					Provider:  ProviderAnthropic,
					CLIBinary: "claude",
				},
			},
			StageExplorer: {
				Stage:    StageExplorer,
				Mode:     ModeLocal,
				Provider: ProviderOllama,
				Model:    "qwen2.5-vl:latest",
				Fallback: &StageConfig{
					Stage:     StageExplorer,
					Mode:      ModeCLI,
					Provider:  ProviderCLI,
					CLIBinary: "claude",
				},
			},
			StageSDET: {
				Stage:    StageSDET,
				Mode:     ModeLocal,
				Provider: ProviderOllama,
				Model:    "qwen2.5-coder:latest",
				Fallback: &StageConfig{
					Stage:     StageSDET,
					Mode:      ModeCLI,
					Provider:  ProviderCLI,
					CLIBinary: "codex",
				},
			},
			StageSecurity: {
				Stage:    StageSecurity,
				Mode:     ModeLocal,
				Provider: ProviderOllama,
				Model:    "gemma2:latest",
			},
			StageTriage: {
				Stage:    StageTriage,
				Mode:     ModeLocal,
				Provider: ProviderOllama,
				Model:    "qwen2.5-coder:latest",
				Fallback: &StageConfig{
					Stage:     StageTriage,
					Mode:      ModeCLI,
					Provider:  ProviderCLI,
					CLIBinary: "claude",
				},
			},
		},
	}
}

// GetStageConfig retrieves the configuration for a given testing stage.
func (r *Router) GetStageConfig(stage Stage) (StageConfig, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	cfg, ok := r.config.Stages[stage]
	return cfg, ok
}

// SetStageConfig updates the configuration for a specific stage.
func (r *Router) SetStageConfig(stage Stage, cfg StageConfig) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.config.Stages[stage] = cfg
}

// InvokeStage dispatches the request to the configured model for the stage.
func (r *Router) InvokeStage(ctx context.Context, stage Stage, req Request) (*Response, error) {
	req.Stage = stage
	r.mu.RLock()
	stageCfg, ok := r.config.Stages[stage]
	r.mu.RUnlock()

	if !ok {
		// Fallback to default local config
		stageCfg = StageConfig{
			Stage:    stage,
			Mode:     r.config.DefaultMode,
			Provider: ProviderOllama,
			Model:    "qwen2.5-coder:latest",
			Endpoint: r.config.DefaultEndpoint,
		}
	}

	resp, err := r.executeConfig(ctx, stageCfg, req)
	if err == nil {
		return resp, nil
	}

	// Attempt fallback if configured
	if stageCfg.Fallback != nil {
		resp, fallbackErr := r.executeConfig(ctx, *stageCfg.Fallback, req)
		if fallbackErr == nil {
			return resp, nil
		}
		return nil, fmt.Errorf("primary error: %v, fallback error: %w", err, fallbackErr)
	}

	return nil, err
}

func (r *Router) executeConfig(ctx context.Context, cfg StageConfig, req Request) (*Response, error) {
	invoker, err := r.createInvoker(cfg)
	if err != nil {
		return nil, err
	}
	return invoker.Invoke(ctx, req)
}

func (r *Router) createInvoker(cfg StageConfig) (Invoker, error) {
	switch cfg.Mode {
	case ModeAPI:
		return NewAPIInvoker(cfg), nil
	case ModeCLI:
		return NewCLIInvoker(cfg), nil
	case ModeLocal:
		return NewLocalInvoker(cfg), nil
	default:
		return nil, fmt.Errorf("%w: %s", ErrUnsupportedMode, cfg.Mode)
	}
}

// ListAllAvailableModels inspects environment and returns models across Local, CLI, and API.
func (r *Router) ListAllAvailableModels(ctx context.Context) []ModelDescriptor {
	var all []ModelDescriptor

	// 1. Local models (Ollama / vLLM)
	localModels, err := DiscoverLocalModels(ctx, r.config.DefaultEndpoint)
	if err == nil {
		all = append(all, localModels...)
	}

	// 2. CLI tools in $PATH
	cliModels := DetectInstalledCLIs()
	all = append(all, cliModels...)

	// 3. Known API providers
	all = append(all, []ModelDescriptor{
		{ID: "api:openai/gpt-4o", Name: "GPT-4o", Provider: ProviderOpenAI, Mode: ModeAPI, VisionCapable: true, Available: true, Description: "OpenAI Flagship Model"},
		{ID: "api:anthropic/claude-3-7-sonnet", Name: "Claude 3.7 Sonnet", Provider: ProviderAnthropic, Mode: ModeAPI, VisionCapable: true, Available: true, Description: "Anthropic Hybrid Reasoning Model"},
		{ID: "api:gemini/gemini-2.0-flash", Name: "Gemini 2.0 Flash", Provider: ProviderGemini, Mode: ModeAPI, VisionCapable: true, Available: true, Description: "Google Multimodal Ultra-Fast Model"},
		{ID: "api:deepseek/deepseek-chat", Name: "DeepSeek V3", Provider: ProviderDeepSeek, Mode: ModeAPI, VisionCapable: false, Available: true, Description: "DeepSeek High-Performance Chat"},
		{ID: "api:deepseek/deepseek-reasoner", Name: "DeepSeek R1", Provider: ProviderDeepSeek, Mode: ModeAPI, VisionCapable: false, Available: true, Description: "DeepSeek Reasoning Engine"},
	}...)

	return all
}
