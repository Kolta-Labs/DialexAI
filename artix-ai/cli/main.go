package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"artix/pkg/coder"
	"artix/pkg/forge"
	"artix/pkg/git"
	"artix/pkg/lsp"
	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
	"artix/pkg/steering"
	"artix/pkg/tui"
)

const version = "1.0.0"

func printUsage() {
	fmt.Printf(`Artix AI - Dialectic Software Engineering & Autonomous Coding (v%s)

Usage:
  artix [command] [options] [arguments]

Commands:
  repl          Launch interactive TUI shell with @mentions and /grill-me
  lsp           Launch Language Server Protocol backend for IDEs (VS Code, Zed, etc.)
  plan, spec    Deliberate with Stakeholder Council to produce Story Spec
  code          Execute Domain Coder <-> Reviewer convergence loop
  review        Run Adversarial Reviewer against current git diff and tests
  steering      Manage dynamic steering rules (list, sync, bind)
  persona       Inspect and manage SWE Personas
  daemon        Launch webhook server for GitHub & GitLab automation
  version       Print version

Running 'artix' without arguments enters interactive REPL mode.
Use "artix <command> -h" for detailed options on any command.
`, version)
}

func main() {
	cwd, err := os.Getwd()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error getting current directory: %v\n", err)
		os.Exit(1)
	}

	registry := persona.NewRegistry(cwd)

	if len(os.Args) < 2 {
		repl := tui.NewREPL(cwd, registry, os.Stdin, os.Stdout)
		_ = repl.Run(context.Background())
		return
	}

	cmd := os.Args[1]
	args := os.Args[2:]

	switch cmd {
	case "repl":
		repl := tui.NewREPL(cwd, registry, os.Stdin, os.Stdout)
		_ = repl.Run(context.Background())
	case "lsp":
		server := lsp.NewServer(cwd, os.Stdin, os.Stdout)
		if err := server.Serve(); err != nil {
			fmt.Fprintf(os.Stderr, "LSP server error: %v\n", err)
			os.Exit(1)
		}
	case "plan", "spec":
		handlePlan(cwd, registry, args)
	case "code":
		handleCode(cwd, registry, args)
	case "review":
		handleReview(cwd, registry, args)
	case "steering":
		handleSteering(cwd, args)
	case "persona":
		handlePersona(registry, args)
	case "daemon":
		handleDaemon(cwd, registry, args)
	case "version", "--version", "-v":
		fmt.Printf("artix version %s\n", version)
	case "help", "--help", "-h":
		printUsage()
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", cmd)
		printUsage()
		os.Exit(1)
	}
}

func handlePlan(cwd string, reg *persona.Registry, args []string) {
	fs := flag.NewFlagSet("plan", flag.ExitOnError)
	styleFlag := fs.String("style", "standard", "Spec style vector: standard, ponytail (executive), or caveman (terse)")
	fs.Parse(args)

	remaining := fs.Args()
	if len(remaining) == 0 {
		fmt.Fprintf(os.Stderr, "Error: User story prompt is required. Example: artix plan \"Add OAuth2 Google login\"\n")
		os.Exit(1)
	}
	prompt := strings.Join(remaining, " ")

	repoCtx, err := repo.DetectContext(cwd)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Warning: failed to detect full repo context: %v\n", err)
	}

	council := spec.NewCouncil(reg)
	pCtx := &spec.PlanningContext{
		StoryPrompt: prompt,
		RepoContext: repoCtx,
		Style:       spec.StyleVector(*styleFlag),
	}

	fmt.Println("Assembling Stakeholder Council (PO, Architect, QA Lead, EM)...")
	storySpec, err := council.Plan(context.Background(), pCtx)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Council deliberation failed: %v\n", err)
		os.Exit(1)
	}

	specsDir := filepath.Join(cwd, "docs", "specs")
	_ = os.MkdirAll(specsDir, 0755)
	specPath := filepath.Join(specsDir, fmt.Sprintf("%s.md", storySpec.ID))
	if err := os.WriteFile(specPath, []byte(storySpec.RawMarkdown), 0644); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to write spec file: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("\nGenerated Verified Story Spec: %s\n", specPath)
	fmt.Printf("Title: %s\n", storySpec.Title)
	fmt.Printf("Acceptance Criteria: %d scenarios\n", len(storySpec.AcceptanceCriteria))
	fmt.Printf("Verification Commands: %v\n", storySpec.TestCommands)
}

func handleCode(cwd string, reg *persona.Registry, args []string) {
	fs := flag.NewFlagSet("code", flag.ExitOnError)
	domainFlag := fs.String("domain", "backend_engineer", "Target SWE domain persona (e.g. backend_engineer, android_engineer)")
	autonomyFlag := fs.String("autonomy", "supervised", "Autonomy gate: supervised, interactive, or autonomous")
	maxRoundsFlag := fs.Int("rounds", 3, "Maximum convergence rounds")
	providerFlag := fs.String("provider", "", "Model provider that writes the patches: anthropic, openai, gemini, grok, deepseek, mistral, ollama (API key from ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY, XAI_API_KEY, DEEPSEEK_API_KEY, MISTRAL_API_KEY)")
	modelFlag := fs.String("model", "", "Model name for --provider")
	reviewProvider := fs.String("review-provider", "", "Provider for the model reviewer (default: same as --provider; use a different one for a truly adversarial review)")
	reviewModel := fs.String("review-model", "", "Model for --review-provider (default: same as --model)")
	noModelReview := fs.Bool("no-model-review", false, "Skip the model review of acceptance criteria (rule-based checks only)")
	fs.Parse(args)

	repoCtx, err := repo.DetectContext(cwd)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error detecting repository context: %v\n", err)
		os.Exit(1)
	}

	var specFile string
	if len(fs.Args()) > 0 {
		specFile = fs.Args()[0]
	} else {
		specs, _ := filepath.Glob(filepath.Join(cwd, "docs", "specs", "STORY-*.md"))
		if len(specs) > 0 {
			specFile = specs[len(specs)-1]
		}
	}

	if specFile == "" {
		fmt.Fprintf(os.Stderr, "Error: No story spec specified and none found in docs/specs/.\nRun 'artix plan' first.\n")
		os.Exit(1)
	}

	rawSpec, err := os.ReadFile(specFile)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error reading spec %s: %v\n", specFile, err)
		os.Exit(1)
	}

	storySpec, err := spec.ParseFromMarkdown(string(rawSpec))
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error parsing spec: %v\n", err)
		os.Exit(1)
	}

	domainCoder, err := coder.NewDomainCoder(*domainFlag, reg)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error creating domain coder: %v\n", err)
		os.Exit(1)
	}

	advReviewer := reviewer.NewAdversarialReviewer(reg)
	driver := git.NewDriver(cwd)
	box := sandbox.NewSandbox(cwd)
	coord := coder.NewCoordinator(domainCoder, advReviewer, driver, box)

	agg := steering.NewAggregator(cwd)
	rules, _ := agg.CollectLocalRules()
	binder := steering.NewBinder(steering.SteeringConfig{}, rules)
	coderSteering := binder.CompilePersonaSteering(*domainFlag)
	revSteering := binder.CompilePersonaSteering("adversarial_code_reviewer")

	opts := &coder.LoopOptions{
		MaxRounds: *maxRoundsFlag,
		Autonomy:  coder.AutonomyLevel(*autonomyFlag),
	}
	if *providerFlag == "" {
		fmt.Fprintf(os.Stderr, "Error: artix code needs a model to write the patches. Pass --provider and --model (e.g. --provider anthropic --model <model-name>) and set the provider's API key in your environment.\n")
		os.Exit(1)
	}
	modelRunner, agent, err := coder.NewAPIRunnerFromEnv(*providerFlag, *modelFlag, os.Getenv)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error: %v\n", err)
		os.Exit(1)
	}
	if !*noModelReview {
		rp, rm, rRunner, rAgent := *providerFlag, *modelFlag, modelRunner, agent
		if *reviewProvider != "" {
			rp, rm = *reviewProvider, *reviewModel
			if rm == "" {
				rm = *modelFlag
			}
			var rerr error
			if rRunner, rAgent, rerr = coder.NewAPIRunnerFromEnv(rp, rm, os.Getenv); rerr != nil {
				fmt.Fprintf(os.Stderr, "Error: reviewer: %v\n", rerr)
				os.Exit(1)
			}
		}
		advReviewer.SetCritic(reviewer.RunnerCritic(rRunner, rAgent))
	}
	opts.PatchGenerator = coder.NewRunnerPatchGenerator(modelRunner, agent, domainCoder, coder.PromptContext{
		Spec: storySpec, RepoContext: repoCtx, SteeringContext: coderSteering,
	})

	fmt.Printf("Starting convergence loop for Spec: %s (%s)...\n", storySpec.ID, storySpec.Title)
	res := coord.Run(context.Background(), storySpec, repoCtx, coderSteering, revSteering, opts)

	if res.Success {
		fmt.Printf("\nSUCCESS: Convergence achieved in round %d!\n", res.RoundsRun)
		if res.CommitHash != "" {
			fmt.Printf("Committed: %s\n", res.CommitHash)
		}
	} else {
		fmt.Printf("\nFAILED to converge: %s\n", res.Error)
		os.Exit(1)
	}
}

func handleReview(cwd string, reg *persona.Registry, args []string) {
	driver := git.NewDriver(cwd)
	diff, err := driver.Diff(false)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error reading git diff: %v\n", err)
		os.Exit(1)
	}

	if strings.TrimSpace(diff) == "" {
		fmt.Println("Working tree clean; nothing to review.")
		return
	}

	advReviewer := reviewer.NewAdversarialReviewer(reg)
	rCtx := &reviewer.ReviewContext{
		Diff: diff,
	}

	verdict := advReviewer.Evaluate(rCtx)
	if verdict.Approved {
		fmt.Printf("REVIEW PASSED: %s\n", verdict.Summary)
	} else {
		fmt.Printf("REVIEW REJECTED: %s\n\n%s\n", verdict.Summary, verdict.ActionableFeedback)
		os.Exit(1)
	}
}

func handleSteering(cwd string, args []string) {
	mgr := steering.NewManager(cwd)

	if len(args) == 0 || args[0] == "list" {
		rules, err := mgr.ListRules()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error collecting steering rules: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Found %d steering rules:\n", len(rules))
		for _, r := range rules {
			fmt.Printf(" - [%s] %s (%s)\n", r.SourceType, r.Name, r.RelPath)
		}
		return
	}

	if args[0] == "bind" && len(args) >= 3 {
		personaID := args[1]
		ruleID := args[2]
		if err := mgr.BindRule(personaID, ruleID); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to bind rule: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Bound rule %q to persona %q\n", ruleID, personaID)
		return
	}

	if args[0] == "sync" {
		fmt.Println("Syncing external steering rules...")
		syncer := steering.NewRemoteSyncer()
		fmt.Println("Remote syncer initialized. Cache at ~/.artix/cache/steering")
		_ = syncer
		return
	}

	fmt.Fprintf(os.Stderr, "Unknown steering subcommand: %s. Supported: list, sync, bind <persona> <rule>\n", args[0])
}

func handlePersona(reg *persona.Registry, args []string) {
	personas := reg.List()
	fmt.Printf("Available SWE Personas (%d):\n", len(personas))
	for _, p := range personas {
		fmt.Printf(" - %-25s | %s (%s)\n", p.ID, p.Name, p.Role)
	}
}

func handleDaemon(cwd string, reg *persona.Registry, args []string) {
	fs := flag.NewFlagSet("daemon", flag.ExitOnError)
	addr := fs.String("addr", ":8080", "Server listen address")
	fs.Parse(args)

	workerDir := filepath.Join(cwd, ".artix", "worker_cache")
	worker := forge.NewRemoteWorker(workerDir, reg)

	server := forge.NewWebhookServer(forge.WebhookServerConfig{
		ListenAddr: *addr,
		Worker:     worker,
	})

	fmt.Printf("Artix Daemon listening on %s (Endpoints: /healthz, /webhook/github, /webhook/gitlab)...\n", *addr)
	if err := http.ListenAndServe(*addr, server.Handler()); err != nil {
		fmt.Fprintf(os.Stderr, "Daemon server error: %v\n", err)
		os.Exit(1)
	}
}
