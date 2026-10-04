package model

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

// CLIInvoker shells out to local authenticated developer CLI binaries.
type CLIInvoker struct {
	config StageConfig
}

// NewCLIInvoker creates an invoker using developer command-line interfaces.
func NewCLIInvoker(config StageConfig) *CLIInvoker {
	return &CLIInvoker{config: config}
}

// CheckCLIAvailable verifies whether the configured CLI binary exists in $PATH.
func CheckCLIAvailable(binary string) bool {
	if binary == "" {
		return false
	}
	_, err := exec.LookPath(binary)
	return err == nil
}

// DetectInstalledCLIs inspects common AI developer CLIs in the user's environment.
func DetectInstalledCLIs() []ModelDescriptor {
	candidates := []struct {
		bin      string
		name     string
		provider Provider
		vision   bool
		desc     string
	}{
		{"claude", "Anthropic Claude CLI", ProviderAnthropic, true, "Local authenticated Claude CLI"},
		{"codex", "OpenAI Codex / Copilot CLI", ProviderOpenAI, false, "Local authenticated Codex/Copilot CLI"},
		{"antigravity", "Google Antigravity CLI", ProviderGemini, true, "Google Antigravity Developer CLI"},
		{"agy", "AGY CLI", ProviderGemini, true, "Google Antigravity Short Alias"},
		{"ollama", "Ollama CLI", ProviderOllama, true, "Local Ollama CLI runner"},
	}

	var results []ModelDescriptor
	for _, c := range candidates {
		available := CheckCLIAvailable(c.bin)
		results = append(results, ModelDescriptor{
			ID:            "cli:" + c.bin,
			Name:          c.name,
			Provider:      c.provider,
			Mode:          ModeCLI,
			VisionCapable: c.vision,
			Available:     available,
			Description:   c.desc,
		})
	}
	return results
}

func (c *CLIInvoker) Invoke(ctx context.Context, req Request) (*Response, error) {
	start := time.Now()
	bin := c.config.CLIBinary
	if bin == "" {
		bin = "claude"
	}

	if !CheckCLIAvailable(bin) {
		return nil, fmt.Errorf("%w: %s", ErrBinaryNotFound, bin)
	}

	// Format conversation into prompt string
	var sb strings.Builder
	for _, m := range req.Messages {
		sb.WriteString(fmt.Sprintf("[%s]: %s\n", strings.ToUpper(m.Role), m.Content))
	}
	prompt := sb.String()

	var cmd *exec.Cmd
	switch bin {
	case "ollama":
		modelName := c.config.Model
		if modelName == "" {
			modelName = "qwen2.5-coder:latest"
		}
		cmd = exec.CommandContext(ctx, "ollama", "run", modelName, prompt)
	case "claude":
		cmd = exec.CommandContext(ctx, "claude", "-p", prompt)
	case "codex":
		cmd = exec.CommandContext(ctx, "codex", "-q", prompt)
	case "antigravity", "agy":
		cmd = exec.CommandContext(ctx, bin, "prompt", prompt)
	default:
		// Generic CLI: pipe prompt via stdin
		cmd = exec.CommandContext(ctx, bin)
		cmd.Stdin = strings.NewReader(prompt)
	}

	stdout := bytes.NewBuffer(nil)
	stderr := bytes.NewBuffer(nil)
	cmd.Stdout = stdout
	cmd.Stderr = stderr

	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("cli execution error (%s): %v, stderr: %s", bin, err, stderr.String())
	}

	output := strings.TrimSpace(stdout.String())
	return &Response{
		Content:      output,
		Model:        c.config.Model,
		Provider:     ProviderCLI,
		Mode:         ModeCLI,
		OutputTokens: len(strings.Fields(output)),
		Duration:     time.Since(start),
	}, nil
}
