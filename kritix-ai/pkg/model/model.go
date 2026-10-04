package model

import (
	"context"
	"errors"
	"time"
)

// Mode represents how an AI model is invoked.
type Mode string

const (
	ModeAPI   Mode = "api"   // Direct cloud REST API call
	ModeCLI   Mode = "cli"   // Developer CLI subshell (claude, codex, agy, ollama)
	ModeLocal Mode = "local" // 100% offline local endpoint (Ollama, vLLM, LocalAI)
)

// Stage represents the distinct phases of the testing department workflow.
type Stage string

const (
	StagePO        Stage = "po"        // Requirements, user stories, BDD/Gherkin generation
	StageExplorer  Stage = "explorer"  // Autonomous "manual" exploratory tester (multimodal vision + CDP)
	StageSDET      Stage = "sdet"      // SDET test script synthesis & self-healing locators
	StageSecurity  Stage = "security"  // OWASP Top 10 fuzzing & PII leak scanning
	StageTriage    Stage = "triage"    // Deterministic repro generation & Git blame RCA
)

// Provider identifies the underlying AI vendor or runtime.
type Provider string

const (
	ProviderOpenAI    Provider = "openai"
	ProviderAnthropic Provider = "anthropic"
	ProviderGemini    Provider = "gemini"
	ProviderDeepSeek  Provider = "deepseek"
	ProviderOllama    Provider = "ollama"
	ProviderVLLM      Provider = "vllm"
	ProviderCLI       Provider = "cli"
	ProviderCustom    Provider = "custom"
)

// Message represents a prompt turn in chat format.
type Message struct {
	Role         string   `json:"role"`                    // "system", "user", "assistant"
	Content      string   `json:"content"`                 // Text content
	ImageBase64  string   `json:"image_base64,omitempty"`  // Optional base64 encoded PNG/JPEG for vision
	ImageMime    string   `json:"image_mime,omitempty"`    // e.g. "image/png"
}

// Request encapsulates a request to an AI model.
type Request struct {
	Stage       Stage     `json:"stage"`
	Messages    []Message `json:"messages"`
	Temperature float64   `json:"temperature,omitempty"`
	MaxTokens   int       `json:"max_tokens,omitempty"`
	StopTokens  []string  `json:"stop_tokens,omitempty"`
}

// Response encapsulates the completion from an AI model.
type Response struct {
	Content      string        `json:"content"`
	Model        string        `json:"model"`
	Provider     Provider      `json:"provider"`
	Mode         Mode          `json:"mode"`
	PromptTokens int           `json:"prompt_tokens,omitempty"`
	OutputTokens int           `json:"output_tokens,omitempty"`
	Duration     time.Duration `json:"duration"`
}

// ModelDescriptor details an available model.
type ModelDescriptor struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Provider      Provider `json:"provider"`
	Mode          Mode     `json:"mode"`
	VisionCapable bool     `json:"vision_capable"`
	Available     bool     `json:"available"`
	Description   string   `json:"description,omitempty"`
}

// StageConfig defines model binding for a specific testing lifecycle stage.
type StageConfig struct {
	Stage     Stage    `json:"stage"`
	Mode      Mode     `json:"mode"`
	Provider  Provider `json:"provider"`
	Model     string   `json:"model"`
	Endpoint  string   `json:"endpoint,omitempty"`   // Custom API or local host (e.g. http://localhost:11434/v1)
	APIKey    string   `json:"api_key,omitempty"`    // API Key (if ModeAPI)
	CLIBinary string   `json:"cli_binary,omitempty"` // Binary name in $PATH (e.g. claude, codex, agy, ollama)
	Fallback  *StageConfig `json:"fallback,omitempty"` // Optional fallback configuration
}

// RouterConfig contains the full per-stage configuration.
type RouterConfig struct {
	DefaultMode     Mode                  `json:"default_mode"`
	DefaultEndpoint string                `json:"default_endpoint"`
	Stages          map[Stage]StageConfig `json:"stages"`
}

// Common errors.
var (
	ErrNoModelConfigured = errors.New("no model configured for stage")
	ErrExecutionFailed   = errors.New("model execution failed")
	ErrUnsupportedMode   = errors.New("unsupported model execution mode")
	ErrBinaryNotFound    = errors.New("cli binary not found in PATH")
)

// Invoker is the interface for executing model requests.
type Invoker interface {
	Invoke(ctx context.Context, req Request) (*Response, error)
}
