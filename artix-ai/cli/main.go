package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"artix/pkg/audit"
	"artix/pkg/coder"
	"artix/pkg/forge"
	"artix/pkg/git"
	"artix/pkg/lsp"
	"artix/pkg/persona"
	"artix/pkg/policy"
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
  audit         Audit log management and tamper verification (audit verify)
  steering      Manage dynamic steering rules (list, sync, bind)
  persona       Inspect and manage SWE Personas
  gc            Clean up stale and orphaned shadow worktrees
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
	case "audit":
		handleAudit(cwd, args)
	case "steering":
		handleSteering(cwd, args)
	case "persona":
		handlePersona(registry, args)
	case "gc":
		handleGC(cwd, args)
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
	providerFlag := fs.String("provider", "", "Model provider for Stakeholder Council deliberation: anthropic, openai, gemini, grok, deepseek, mistral, ollama")
	modelFlag := fs.String("model", "", "Model name for --provider")
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
	method := "deterministic_template"
	rounds := 1

	if *providerFlag != "" {
		mRunner, agent, rerr := coder.NewAPIRunnerFromEnv(*providerFlag, *modelFlag, os.Getenv)
		if rerr != nil {
			fmt.Fprintf(os.Stderr, "Warning: failed to initialize model runner (%v), falling back to structured template\n", rerr)
		} else {
			council.SetRunner(mRunner, agent)
			method = "multi_persona_deliberation"
			rounds = 3
			fmt.Printf("Assembling Stakeholder Council with AI Deliberation (%s/%s)...\n", *providerFlag, *modelFlag)
		}
	} else {
		fmt.Println("Assembling Stakeholder Council (Deterministic Template Mode)...")
	}

	pCtx := &spec.PlanningContext{
		StoryPrompt: prompt,
		RepoContext: repoCtx,
		Style:       spec.StyleVector(*styleFlag),
	}

	storySpec, err := council.Plan(context.Background(), pCtx)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Council deliberation failed: %v\n", err)
		os.Exit(1)
	}

	prov := spec.BuildStoryProvenance(storySpec, pCtx, *providerFlag, *modelFlag, council.Members())

	specsDir := filepath.Join(cwd, "docs", "specs")
	specPath, provPath, err := spec.WriteSpecWithProvenance(specsDir, storySpec, prov)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to persist spec and provenance: %v\n", err)
		os.Exit(1)
	}

	// Emit audit event
	_ = audit.Default(cwd).Emit(audit.AuditEvent{
		EventType: audit.EventSpecDeliberation,
		Status:    "SUCCESS",
		Details: map[string]any{
			"specId":             storySpec.ID,
			"title":              storySpec.Title,
			"deliberationMethod": method,
			"deliberationRounds": rounds,
			"provider":           *providerFlag,
			"model":              *modelFlag,
		},
	})

	fmt.Printf("\nGenerated Verified Story Spec: %s\n", specPath)
	fmt.Printf("Generated Provenance Sidecar: %s\n", provPath)
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
	maxDiffKb := fs.Int("max-diff-kb", 500, "Maximum diff size in KB before critic hard rejects with truncation error")
	fs.Parse(args)

	// Enterprise Safety Gate: Autonomous commits require explicit policy / opt-in
	if *autonomyFlag == "autonomous" && !policy.IsAutonomousAllowed() {
		fmt.Fprintf(os.Stderr, "Error: Enterprise safety violation: --autonomy autonomous is disabled by default in enterprise/CI environments or disallowed by enterprise policy.\nSet ARTIX_ALLOW_AUTONOMOUS=1 or configure enterprise policy to explicitly permit unattended autonomous commits.\n")
		os.Exit(1)
	}

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
	if *maxDiffKb > 0 {
		advReviewer.SetMaxCriticDiffBytes(*maxDiffKb * 1024)
	}

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

	// Emit audit event
	auditStatus := "SUCCESS"
	if !res.Success {
		auditStatus = "FAILED"
	}
	_ = audit.Default(cwd).Emit(audit.AuditEvent{
		EventType: audit.EventCodeConvergence,
		Status:    auditStatus,
		Details: map[string]any{
			"specId":     storySpec.ID,
			"title":      storySpec.Title,
			"domain":     *domainFlag,
			"roundsRun":  res.RoundsRun,
			"commitHash": res.CommitHash,
			"error":      res.Error,
		},
	})

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

	status := "SUCCESS"
	if !verdict.Approved {
		status = "REJECTED"
	}
	_ = audit.Default(cwd).Emit(audit.AuditEvent{
		EventType: audit.EventReviewerVerdict,
		Status:    status,
		Details: map[string]any{
			"summary":        verdict.Summary,
			"blockingIssues": verdict.BlockingIssues,
			"warnings":       verdict.Warnings,
		},
	})

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
		_ = audit.Default(cwd).Emit(audit.AuditEvent{
			EventType: audit.EventSteeringBind,
			Status:    "SUCCESS",
			Details: map[string]any{
				"personaId": personaID,
				"ruleId":    ruleID,
			},
		})
		fmt.Printf("Bound rule %q to persona %q\n", ruleID, personaID)
		return
	}

	if args[0] == "pending" {
		pending, err := mgr.ListPendingRules()
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error listing pending rules: %v\n", err)
			os.Exit(1)
		}
		if len(pending) == 0 {
			fmt.Println("No pending steering rules awaiting review.")
			return
		}
		fmt.Printf("Pending Steering Rules Review Queue (%d):\n", len(pending))
		for _, pr := range pending {
			fmt.Printf(" - [%s] %s (Taboo: %v, Roles: %v, Author: %s)\n   Text: %q\n",
				pr.Hash, pr.Name, pr.IsTaboo, pr.TargetRoles, pr.Author, pr.RuleText)
		}
		return
	}

	if args[0] == "approve" && len(args) >= 2 {
		hash := args[1]
		approver := "senior_architect"
		if len(args) >= 3 {
			approver = args[2]
		}
		rule, err := mgr.ApproveRule(hash, approver)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Failed to approve rule: %v\n", err)
			os.Exit(1)
		}
		_ = audit.Default(cwd).Emit(audit.AuditEvent{
			EventType: audit.EventSteeringBind,
			Status:    "SUCCESS",
			Details: map[string]any{
				"ruleId":     rule.ID,
				"action":     "approve",
				"approver":   approver,
				"ruleHash":   hash,
			},
		})
		fmt.Printf("Successfully approved rule %q (%s) as %s.\n", rule.ID, rule.Name, approver)
		return
	}

	if args[0] == "reject" && len(args) >= 2 {
		hash := args[1]
		if err := mgr.RejectRule(hash); err != nil {
			fmt.Fprintf(os.Stderr, "Failed to reject rule: %v\n", err)
			os.Exit(1)
		}
		fmt.Printf("Rejected and removed pending rule %q.\n", hash)
		return
	}

	if args[0] == "sync" {
		fmt.Println("Syncing external steering rules...")
		syncer := steering.NewRemoteSyncer()
		fmt.Println("Remote syncer initialized. Cache at ~/.artix/cache/steering")
		_ = syncer
		return
	}

	fmt.Fprintf(os.Stderr, "Unknown steering subcommand: %s. Supported: list, pending, approve <hash> [role], reject <hash>, sync, bind <persona> <rule>\n", args[0])
}

func handlePersona(reg *persona.Registry, args []string) {
	personas := reg.List()
	fmt.Printf("Available SWE Personas (%d):\n", len(personas))
	for _, p := range personas {
		fmt.Printf(" - %-25s | %s (%s)\n", p.ID, p.Name, p.Role)
	}
}

func handleGC(cwd string, args []string) {
	fs := flag.NewFlagSet("gc", flag.ExitOnError)
	maxAgeFlag := fs.Duration("max-age", 24*time.Hour, "Maximum age threshold for orphaned shadow worktrees (e.g. 24h, 1h)")
	fs.Parse(args)

	wtMgr := git.NewWorktreeManager(cwd)
	pruned, err := wtMgr.GarbageCollect(*maxAgeFlag)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Garbage collection failed: %v\n", err)
		os.Exit(1)
	}

	_ = audit.Default(cwd).Emit(audit.AuditEvent{
		EventType: audit.EventWorktreeGC,
		Status:    "SUCCESS",
		Details: map[string]any{
			"prunedCount": pruned,
			"maxAge":      maxAgeFlag.String(),
		},
	})

	fmt.Printf("Worktree Garbage Collection complete. Pruned %d stale worktree(s).\n", pruned)
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

func handleAudit(cwd string, args []string) {
	if len(args) == 0 {
		fmt.Fprintf(os.Stderr, "Usage: artix audit <subcommand>\n\nSubcommands:\n  verify [path] [--key <secret>]  Verify cryptographic hash-chain integrity of audit logs\n")
		os.Exit(1)
	}

	subcmd := args[0]
	subargs := args[1:]

	switch subcmd {
	case "verify":
		fs := flag.NewFlagSet("audit verify", flag.ExitOnError)
		keyFlag := fs.String("key", "", "HMAC signing key for cryptographic signature verification")
		fs.Parse(subargs)

		logPath := policy.EffectiveAuditLogPath(cwd)
		if len(fs.Args()) > 0 {
			logPath = fs.Args()[0]
		}

		fmt.Printf("Verifying audit log integrity: %s\n", logPath)
		var res *audit.VerificationResult
		var err error
		if *keyFlag != "" {
			res, err = audit.VerifyLog(logPath, *keyFlag)
		} else {
			res, err = audit.VerifyLog(logPath)
		}

		if err != nil {
			fmt.Fprintf(os.Stderr, "\nAUDIT INTEGRITY VIOLATION: %v\n", err)
			os.Exit(1)
		}

		fmt.Printf("AUDIT LOG VERIFIED: %d record(s) valid, hash chain intact (LastHash: %s).\n", res.ValidRecords, res.LastHash[:16]+"...")
	default:
		fmt.Fprintf(os.Stderr, "Unknown audit subcommand: %s. Supported: verify\n", subcmd)
		os.Exit(1)
	}
}

