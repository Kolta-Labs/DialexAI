package runner

import (
	"bytes"
	"context"
	"fmt"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"socratix/pkg/model"
)

// CliAgentRunner shells out to a local CLI (claude, agy, ...) per turn. Command always
// comes from the global Settings CliCommands for the agent's provider — Agent.CliCommand is
// ignored (kept only so old persisted discussions still decode). Split on spaces and run as
// a process; the prompt is piped via stdin, full stdout+stderr is the reply.
//
// Cancelling ctx (hard stop) kills the process immediately via exec.CommandContext, which
// sends SIGKILL on timeout/cancellation — that's what makes hard-stop actually immediate
// instead of waiting out the current turn.
type CliAgentRunner struct {
	Commands         model.CliCommands
	TimeoutSeconds   int
	WorkspaceFolders []model.FolderScope
	Permissions      *model.PermissionConfig
	// AllowPermissionBypass lets the runner add or auto-retry with --dangerously-skip-permissions
	// for vendor CLIs. Off by default: a debate tool should not silently hand a third-party
	// coding agent unprompted tool access. Set DIALEX_ALLOW_CLI_PERMISSION_BYPASS=1 to opt in;
	// a flag the user wrote into their own CLI command is always honoured.
	AllowPermissionBypass bool
}

func permissionBypassFromEnv() bool { return os.Getenv("DIALEX_ALLOW_CLI_PERMISSION_BYPASS") == "1" }

// NewCliAgentRunner builds a runner with the same 120s default timeout the Kotlin
// CliAgentRunner has.
func NewCliAgentRunner(commands model.CliCommands) *CliAgentRunner {
	return NewCliAgentRunnerWithContext(commands, nil, nil)
}

// NewCliAgentRunnerWithContext builds a runner configured with attached workspace folders and permissions.
func NewCliAgentRunnerWithContext(commands model.CliCommands, folders []model.FolderScope, permissions *model.PermissionConfig) *CliAgentRunner {
	return &CliAgentRunner{
		Commands:         commands,
		TimeoutSeconds:   120,
		WorkspaceFolders: folders,
		Permissions:      permissions,

		AllowPermissionBypass: permissionBypassFromEnv(),
	}
}

func (r *CliAgentRunner) Respond(
	ctx context.Context,
	agent model.Agent,
	topic, commonContext, commonInstructions string,
	transcript []model.DebateMessage,
	modelOverride string,
) (AgentReply, error) {
	command := r.Commands.ForProvider(agent.Provider)
	prompt := buildCliPrompt(topic, commonContext, commonInstructions, agent, transcript)

	tokens := strings.Fields(command)
	if len(tokens) == 0 {
		return AgentReply{}, fmt.Errorf("no CLI command configured for %s in Settings", agent.Provider)
	}
	// claude/codex/agy all accept `--model <name>` — appending it (only when an override is
	// actually requested, e.g. compaction) swaps the model for just this one call without
	// touching the configured command in Settings.
	if modelOverride != "" {
		tokens = append(tokens, "--model", modelOverride)
	}

	binary, err := ResolveBinary(tokens[0])
	if err != nil {
		return AgentReply{}, fmt.Errorf("CLI '%s' not found. It isn't installed or isn't on your PATH — install it, or fix the command in Settings", tokens[0])
	}

	timeout := time.Duration(r.TimeoutSeconds) * time.Second
	runCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	cliArgs := tokens[1:]
	baseBin := filepath.Base(tokens[0])
	if baseBin == "agy" || baseBin == "gemini" {
		hasPrint := false
		for _, t := range cliArgs {
			if t == "--print" || t == "-p" || t == "--prompt" {
				hasPrint = true
				break
			}
		}
		if !hasPrint {
			cliArgs = append(cliArgs, "--print", prompt)
		}
	}

	// Resolve primary trusted workspace directory
	var trustedDir string
	for _, f := range r.WorkspaceFolders {
		if f.IsTrusted && f.Path != "" {
			if info, statErr := os.Stat(f.Path); statErr == nil && info.IsDir() {
				trustedDir = f.Path
				break
			}
		}
	}

	// Evaluate WebSearch permission for this agent seat
	webSearchAllowed := true
	if r.Permissions != nil {
		webSearchAllowed = r.Permissions.IsWebSearchAllowedFor(string(agent.ID))
	}
	if agent.AllowWebSearch != nil {
		webSearchAllowed = *agent.AllowWebSearch
	}

	// For Claude Code or Gemini CLI in a trusted workspace, pass trust flags to avoid interactive prompt deadlocks
	if r.AllowPermissionBypass && trustedDir != "" && (baseBin == "claude" || baseBin == "agy") && !hasPermissionSkipFlag(tokens) {
		cliArgs = append(cliArgs, "--dangerously-skip-permissions")
	}

	// If web search is explicitly restricted, pass disallow flag if supported
	if !webSearchAllowed && baseBin == "claude" && !containsToken(cliArgs, "--disallow-tools") {
		cliArgs = append(cliArgs, "--disallow-tools", "WebSearch,WebFetch")
	}

	cmd := exec.CommandContext(runCtx, binary, cliArgs...)
	if trustedDir != "" {
		cmd.Dir = trustedDir
	}
	// A killed CLI can leave a grandchild process alive holding stdout/stderr open (e.g. a
	// shell-script wrapper whose own child inherits the pipe) — without WaitDelay, Wait()
	// blocks on that lingering process instead of returning once the direct child is
	// killed. This bounds that wait instead of letting a hard-stop/timeout hang on it.
	cmd.WaitDelay = 1 * time.Second
	cmd.Stdin = strings.NewReader(prompt)
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out

	err = cmd.Run()
	output := out.String()

	// If the CLI failed because headless mode could not prompt for tool permissions (e.g. jetski, claude, agy),
	// auto-retry with --dangerously-skip-permissions under autonomous intervention rules.
	if r.AllowPermissionBypass && isPermissionError(output) && !hasPermissionSkipFlag(tokens) {
		retryTokens := append([]string{}, tokens...)
		retryTokens = append(retryTokens, "--dangerously-skip-permissions")
		var retryOut bytes.Buffer
		retryCmd := exec.CommandContext(runCtx, binary, retryTokens[1:]...)
		retryCmd.WaitDelay = 1 * time.Second
		retryCmd.Stdin = strings.NewReader(prompt)
		retryCmd.Stdout = &retryOut
		retryCmd.Stderr = &retryOut
		retryErr := retryCmd.Run()
		if retryErr == nil && len(strings.TrimSpace(retryOut.String())) > 0 {
			output = retryOut.String()
			err = nil
		}
	}

	if runCtx.Err() == context.DeadlineExceeded {
		return AgentReply{}, fmt.Errorf("CLI '%s' timed out after %ds", command, r.TimeoutSeconds)
	}
	if ctx.Err() != nil {
		// Hard stop: the parent context was cancelled, not the timeout. Propagate as a
		// context error so the orchestrator treats it as a cancellation, not a turn error.
		return AgentReply{}, ctx.Err()
	}
	if err != nil {
		if looksLikeAuthFailure(output) {
			return AgentReply{}, fmt.Errorf("CLI '%s' needs re-authentication — run it interactively to log in, then try again", tokens[0])
		}
		if isPermissionError(output) {
			return AgentReply{}, fmt.Errorf("CLI '%s' requires tool permission approval. Allow the tools in the CLI's own settings, or set DIALEX_ALLOW_CLI_PERMISSION_BYPASS=1 to let Dialex retry with --dangerously-skip-permissions (unsafe): %s", tokens[0], strings.TrimSpace(output))
		}
		return AgentReply{}, fmt.Errorf("CLI '%s' exited with an error: %s", command, strings.TrimSpace(output))
	}
	inTokens := int(math.Ceil(float64(len(prompt)) / 3.8))
	outTokens := int(math.Ceil(float64(len(output)) / 3.8))
	return AgentReply{
		Content:   strings.TrimSpace(output),
		TokensIn:  &inTokens,
		TokensOut: &outTokens,
	}, nil
}

func buildCliPrompt(topic, commonContext, commonInstructions string, agent model.Agent, transcript []model.DebateMessage) string {
	var b strings.Builder
	fmt.Fprintf(&b, "Topic: %s\n", topic)
	if commonContext != "" {
		fmt.Fprintf(&b, "Context: %s\n", commonContext)
	}
	if commonInstructions != "" {
		fmt.Fprintf(&b, "Rules: %s\n", commonInstructions)
	}
	if agent.Context != "" {
		fmt.Fprintf(&b, "%s\n", agent.Context)
	}
	if agent.SystemPrompt != "" {
		fmt.Fprintf(&b, "%s\n", agent.SystemPrompt)
	}
	b.WriteString("--- transcript so far ---\n")
	for _, m := range transcript {
		author := m.AuthorDisplayName
		if author == "" {
			author = string(m.AgentID)
		}
		fmt.Fprintf(&b, "%s: %s\n", author, m.Content)
	}
	b.WriteString("--- your turn ---\n")
	return b.String()
}

func looksLikeAuthFailure(output string) bool {
	lower := strings.ToLower(output)
	for _, needle := range []string{"authenticate", "oauth", "session expired", "not logged in", "unauthorized"} {
		if strings.Contains(lower, needle) {
			return true
		}
	}
	return false
}

func isPermissionError(output string) bool {
	lowered := strings.ToLower(output)
	return strings.Contains(lowered, "dangerously-skip-permissions") ||
		strings.Contains(lowered, "headless mode cannot prompt for") ||
		strings.Contains(lowered, "permission that headless mode") ||
		strings.Contains(lowered, "no search access granted") ||
		strings.Contains(lowered, "search access") ||
		strings.Contains(lowered, "permission required") ||
		strings.Contains(lowered, "tool required the") ||
		(strings.Contains(lowered, "permission") && strings.Contains(lowered, "auto-denied")) ||
		(strings.Contains(lowered, "permission") && strings.Contains(lowered, "allow-rule")) ||
		(strings.Contains(lowered, "permission") && strings.Contains(lowered, "settings.json"))
}

func hasPermissionSkipFlag(tokens []string) bool {
	for _, t := range tokens {
		if t == "--dangerously-skip-permissions" || t == "--yolo" || t == "--yes" {
			return true
		}
	}
	return false
}

func containsToken(tokens []string, target string) bool {
	for _, t := range tokens {
		if t == target {
			return true
		}
	}
	return false
}

// --- Binary resolution ---
//
// A GUI-launched (or service-supervised) process doesn't reliably inherit the user's login
// shell's PATH additions (nvm, homebrew, etc. added in .zshrc/.bashrc) — Go's exec.LookPath
// only sees the process's own inherited PATH. This resolves against the user's actual login
// shell PATH once (cached), same fix the Kotlin ShellPath.kt applied for the same reason.

var (
	shellPathOnce  sync.Once
	shellPathValue string
)

func loginShellPath() string {
	shellPathOnce.Do(func() {
		if runtime.GOOS == "windows" {
			shellPathValue = os.Getenv("PATH")
			return
		}
		shell := os.Getenv("SHELL")
		if shell == "" {
			shell = "/bin/zsh"
		}
		out, err := exec.Command(shell, "-ilc", "echo -n $PATH").Output()
		if err != nil || len(out) == 0 {
			shellPathValue = os.Getenv("PATH")
		} else {
			shellPathValue = strings.TrimSpace(string(out))
		}

		// Also append well-known user binary directories so that background/daemon runs
		// never miss nvm, homebrew, or local bin tool installations.
		home, _ := os.UserHomeDir()
		if home != "" {
			extraDirs := []string{
				filepath.Join(home, ".local", "bin"),
				filepath.Join(home, ".gemini", "antigravity", "bin"),
				filepath.Join(home, "Library", "Application Support", "Antigravity", "bin"),
				"/opt/homebrew/bin",
				"/usr/local/bin",
				filepath.Join(home, ".cargo", "bin"),
				filepath.Join(home, ".npm-global", "bin"),
			}
			nvmBase := filepath.Join(home, ".nvm", "versions", "node")
			if entries, err := os.ReadDir(nvmBase); err == nil {
				for _, entry := range entries {
					if entry.IsDir() {
						extraDirs = append(extraDirs, filepath.Join(nvmBase, entry.Name(), "bin"))
					}
				}
			}
			for _, d := range extraDirs {
				if !strings.Contains(shellPathValue, d) {
					shellPathValue = shellPathValue + string(os.PathListSeparator) + d
				}
			}
		}
	})
	return shellPathValue
}

// ResolveBinary searches the user's actual login-shell PATH for name and returns an
// absolute path — sidestepping the "process PATH doesn't match login shell PATH" gap
// above. Falls back to exec.LookPath, then alias lookups (e.g. agy <-> antigravity),
// then the bare name unresolved.
func ResolveBinary(name string) (string, error) {
	if filepath.IsAbs(name) {
		return name, nil
	}
	namesToTry := []string{name}
	switch name {
	case "antigravity", "gemini":
		namesToTry = append(namesToTry, "agy")
	case "agy":
		namesToTry = append(namesToTry, "antigravity", "gemini")
	case "claude":
		namesToTry = append(namesToTry, "claude-code")
	}

	pathDirs := strings.Split(loginShellPath(), string(os.PathListSeparator))
	for _, n := range namesToTry {
		for _, dir := range pathDirs {
			if dir == "" {
				continue
			}
			candidate := filepath.Join(dir, n)
			if info, err := os.Stat(candidate); err == nil && !info.IsDir() && isExecutable(info) {
				return candidate, nil
			}
		}
		if resolved, err := exec.LookPath(n); err == nil {
			return resolved, nil
		}
	}
	return "", fmt.Errorf("binary %q not found on PATH", name)
}

func isExecutable(info os.FileInfo) bool {
	if runtime.GOOS == "windows" {
		return true
	}
	return info.Mode()&0111 != 0
}
