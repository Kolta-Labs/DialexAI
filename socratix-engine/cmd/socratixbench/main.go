// Command socratixbench runs the Null Hypothesis Benchmarking Suite headlessly.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"

	"socratix/pkg/benchmark"
	"socratix/pkg/model"
	"socratix/pkg/quickstart"
	"socratix/pkg/runner"
	"socratix/pkg/store"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(1)
	}

	switch os.Args[1] {
	case "list":
		runList()
	case "run":
		runBenchmark(os.Args[2:])
	case "compare":
		runCompare(os.Args[2:])
	case "-h", "--help", "help":
		printUsage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", os.Args[1])
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println(`socratixbench — Socratix Deliberation Benchmarking & Evaluation CLI

Usage:
  socratixbench list                 List all 10 canonical benchmark dilemmas
  socratixbench run [flags]          Execute a dual-arm benchmark evaluation
  socratixbench compare [--dir d]    Table of stored runs grouped by baseline and independence

Flags:
  --case string      Case ID to evaluate (e.g. DB01, DB02, or "all", default: "DB01")
  --rounds int       Deliberation rounds for Council arm (default: 2)
  --baseline string  solo (default) or self_consistency: same number of model calls as the council
  --council string   mixed (default) or onekey: personas on --provider only (one API key)
  --provider, --model, --onekey-mode   settings for --council onekey
  --independence     open (default), blind, anon or blind+anon: what council seats see of each other
  --judge-provider, --judge-model   choose the judge (e.g. ollama: free, and a different family)
  --allow-judge-overlap   permit a judge from the same provider as an arm (flagged in the results)
  --format string    Output format: text, markdown, json (default: "text")
  --dir string       Dialex config directory (default: platform-standard)`)
}

func runList() {
	cases := benchmark.BundledDialexBench10()
	fmt.Printf("%-6s | %-24s | %s\n", "ID", "DOMAIN", "TITLE")
	fmt.Println(strings.Repeat("-", 80))
	for _, c := range cases {
		fmt.Printf("%-6s | %-24s | %s\n", c.ID, c.Domain, c.Title)
	}
}

func runBenchmark(args []string) {
	fs := flag.NewFlagSet("run", flag.ExitOnError)
	caseID := fs.String("case", "DB01", "Case ID to evaluate (e.g. DB01, DB02, or 'all')")
	rounds := fs.Int("rounds", 2, "Council debate rounds")
	baseline := fs.String("baseline", benchmark.BaselineSolo, "Baseline arm: solo, or self_consistency (same number of model calls as the council)")
	judgeProv := fs.String("judge-provider", "", "provider for the judge, e.g. ollama (a free local model) or any provider not used by the arms")
	judgeModel := fs.String("judge-model", "", "judge model (default: the provider's balanced default)")
	allowOverlap := fs.Bool("allow-judge-overlap", false, "Allow a judge from the same provider as an arm (biased; the run is flagged)")
	councilKind := fs.String("council", "mixed", "mixed (Claude, ChatGPT, Gemini seats) or onekey (personas on --provider only)")
	provFlag := fs.String("provider", "", "provider for --council onekey (its key must be in settings or the environment)")
	modelFlag := fs.String("model", "", "model for --council onekey (default: the provider's balanced default)")
	onekeyMode := fs.String("onekey-mode", "redteam", "persona set for --council onekey: premortem, redteam or tenthman")
	indep := fs.String("independence", "open", "what council seats see of each other: open, blind, anon or blind+anon")
	format := fs.String("format", "text", "Output format (text, markdown, json)")
	dir := fs.String("dir", "", "Dialex config directory")
	_ = fs.Parse(args)

	cfgDir := *dir
	if cfgDir == "" {
		var err error
		cfgDir, err = store.DefaultConfigDir()
		if err != nil {
			fmt.Fprintf(os.Stderr, "failed to get config dir: %v\n", err)
			os.Exit(1)
		}
	}

	st, err := store.New(cfgDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to initialize store: %v\n", err)
		os.Exit(1)
	}

	benchStore, err := benchmark.NewStore(cfgDir)
	if err != nil {
		benchStore = benchmark.NewMemoryStore()
	}

	state, err := st.Load()
	if err != nil {
		state = model.NewAppState()
	}

	// keys: the settings store first, then the environment (so a bare API key is enough).
	keys := state.ApiKeys.AsMap()
	for p, env := range quickstart.KeyEnv {
		if keys[p] == "" && env != "" {
			keys[p] = os.Getenv(env)
		}
	}
	runnerFor := func(agent model.Agent) runner.AgentRunner {
		if agent.RunMode == model.RunModeCLI {
			return runner.NewCliAgentRunner(state.CliCommands)
		}
		return runner.NewApiAgentRunner(keys)
	}

	benchRunner := benchmark.NewRunner(runnerFor)

	casesToRun := []benchmark.BenchmarkCase{}
	if *caseID == "all" {
		casesToRun = benchStore.ListCases()
	} else {
		c := benchStore.GetCase(*caseID)
		if c == nil {
			fmt.Fprintf(os.Stderr, "case %q not found\n", *caseID)
			os.Exit(1)
		}
		casesToRun = append(casesToRun, *c)
	}

	soloAgent := model.Agent{
		DisplayName: "Claude 3.7 Sonnet (Solo)",
		Provider:    model.ProviderAnthropic,
		Model:       "claude-3-7-sonnet",
		RunMode:     model.RunModeCLI,
		Role:        "Principal Systems Architect",
	}

	councilAgents := []model.Agent{
		{DisplayName: "Claude (Moderator)", Role: "Moderator", Provider: model.ProviderAnthropic, Model: "claude-3-7-sonnet", RunMode: model.RunModeCLI},
		{DisplayName: "ChatGPT (Devil's Advocate)", Role: "Devil's Advocate", Provider: model.ProviderOpenAI, Model: "gpt-4o", RunMode: model.RunModeCLI},
		{DisplayName: "Gemini (Pragmatist)", Role: "Pragmatist", Provider: model.ProviderGemini, Model: "gemini-2.5-pro", RunMode: model.RunModeCLI},
	}

	if *councilKind == "onekey" {
		prov, err := quickstart.ParseProvider(*provFlag)
		if err != nil {
			fmt.Fprintf(os.Stderr, "--council onekey needs --provider: %v\n", err)
			os.Exit(1)
		}
		mode, found := quickstart.Get(*onekeyMode)
		if !found {
			fmt.Fprintf(os.Stderr, "unknown --onekey-mode %q\n", *onekeyMode)
			os.Exit(1)
		}
		councilAgents = quickstart.Seats(mode, prov, *modelFlag)
		soloAgent = model.NewAgent(prov, councilAgents[0].Model)
		soloAgent.DisplayName, soloAgent.Role, soloAgent.RunMode = "Solo "+prov.BrandName(), "Principal Systems Architect", model.RunModeAPI
	}
	switch *indep {
	case "open":
	case "blind":
		benchRunner.CouncilIndependence = &model.IndependenceConfig{BlindFirstRound: true}
	case "anon":
		benchRunner.CouncilIndependence = &model.IndependenceConfig{AnonymizeTranscript: true}
	case "blind+anon":
		benchRunner.CouncilIndependence = &model.IndependenceConfig{BlindFirstRound: true, AnonymizeTranscript: true}
	default:
		fmt.Fprintf(os.Stderr, "--independence must be open, blind, anon or blind+anon\n")
		os.Exit(1)
	}

	judgeAgent, ok := benchmark.PickIndependentJudge(append([]model.Agent{soloAgent}, councilAgents...),
		func(p model.Provider) bool { return keys[p] != "" })
	if *allowOverlap && *councilKind == "onekey" {
		// one key: the judge is the same model as the arms. The run is flagged judgeOverlap.
		judgeAgent, ok = soloAgent, true
		judgeAgent.DisplayName, judgeAgent.Role = "Judge ("+soloAgent.Provider.BrandName()+")", "Chief Systems Architect & Evaluator"
	}
	if *judgeProv != "" {
		jp, err := quickstart.ParseProvider(*judgeProv)
		if err != nil {
			fmt.Fprintf(os.Stderr, "--judge-provider: %v\n", err)
			os.Exit(1)
		}
		judgeAgent = model.NewAgent(jp, *judgeModel)
		if *judgeModel == "" {
			judgeAgent.Model = jp.DefaultModel()
		}
		judgeAgent.DisplayName, judgeAgent.Role = "Judge ("+jp.BrandName()+")", "Chief Systems Architect & Evaluator"
		ok = true // the runner still refuses it if it shares a provider with an arm
	}
	if !ok {
		fmt.Fprintln(os.Stderr, "no provider is independent of the arms; edit the arms or pass --allow-judge-overlap")
		os.Exit(1)
	}
	judgeAgent.RunMode = model.RunModeAPI
	benchRunner.AllowJudgeOverlap = *allowOverlap

	var completedRuns []benchmark.BenchmarkRun

	for _, bc := range casesToRun {
		fmt.Fprintf(os.Stderr, "Evaluating Case %s: %s...\n", bc.ID, bc.Title)
		run, err := benchRunner.ExecuteRunWithBaseline(
			context.Background(),
			bc,
			*baseline,
			soloAgent,
			councilAgents,
			judgeAgent,
			*rounds,
			func(phase string, progress float64) {
				fmt.Fprintf(os.Stderr, "  [%d%%] %s\n", int(progress*100), phase)
			},
		)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Case %s failed: %v\n", bc.ID, err)
			continue
		}
		_ = benchStore.SaveRun(*run)
		completedRuns = append(completedRuns, *run)
	}

	summary := benchmark.ComputeSummary(completedRuns)

	if *format == "json" {
		out := map[string]any{
			"summary": summary,
			"runs":    completedRuns,
		}
		data, _ := json.MarshalIndent(out, "", "  ")
		fmt.Println(string(data))
		return
	}

	// Markdown/Text output
	fmt.Printf("\n=== DIALEX AI BENCHMARK RESULTS ===\n")
	fmt.Printf("Total Evaluated: %d | Council Win Rate: %.1f%% | Mean Delta Q: %+.2f\n",
		summary.TotalRuns, summary.CouncilWinRate*100, summary.MeanDeltaQ)
	fmt.Printf("Statistical Significance: p = %.4f (H0 Rejected: %v)\n\n",
		summary.PValue, summary.IsStatSignificant)

	fmt.Printf("%-6s | %-32s | %-10s | %-10s | %-8s | %s\n", "ID", "TITLE", "SOLO", "COUNCIL", "DELTA", "WINNER")
	fmt.Println(strings.Repeat("-", 85))
	for _, r := range completedRuns {
		title := r.CaseTitle
		if len(title) > 32 {
			title = title[:29] + "..."
		}
		fmt.Printf("%-6s | %-32s | %-10.1f | %-10.1f | %-+8.2f | %s\n",
			r.CaseID, title, r.SoloTotalScore, r.CouncilTotalScore, r.DeltaQ, r.Winner)
	}
}

func runCompare(args []string) {
	fs := flag.NewFlagSet("compare", flag.ExitOnError)
	dir := fs.String("dir", "", "Dialex config directory")
	_ = fs.Parse(args)
	cfgDir := *dir
	if cfgDir == "" {
		var err error
		if cfgDir, err = store.DefaultConfigDir(); err != nil {
			fmt.Fprintf(os.Stderr, "failed to get config dir: %v\n", err)
			os.Exit(1)
		}
	}
	bs, err := benchmark.NewStore(cfgDir)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to open benchmark store: %v\n", err)
		os.Exit(1)
	}
	runs := bs.ListRuns()
	if len(runs) == 0 {
		fmt.Println("no stored runs yet: run `socratixbench run` first")
		return
	}
	fmt.Print(benchmark.CompareMarkdown(benchmark.GroupRuns(runs)))
}
