package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"strings"

	"socratix/pkg/model"
	"socratix/pkg/orchestrator"
	"socratix/pkg/quickstart"
	"socratix/pkg/runner"
)

// runAsk is the 60-second first run: one API key (or local Ollama), one question, one memo.
// Progress goes to stderr; the memo goes to stdout (or --out), so it pipes cleanly.
func runAsk(args []string) {
	fs := flag.NewFlagSet("ask", flag.ExitOnError)
	modeFlag := fs.String("mode", "premortem", "premortem, redteam or tenthman (see --list)")
	providerFlag := fs.String("provider", "", "anthropic, openai, gemini, grok, deepseek, mistral or ollama (default: the first provider whose API key is set)")
	modelFlag := fs.String("model", "", "model name (default: the provider's balanced default)")
	rounds := fs.Int("rounds", 0, "debate rounds (default: the mode's own)")
	out := fs.String("out", "", "write the memo to this file instead of stdout")
	list := fs.Bool("list", false, "list the modes and exit")
	_ = fs.Parse(args)

	if *list {
		for _, m := range quickstart.Modes() {
			fmt.Printf("%-10s %s: %s (%d rounds)\n", m.ID, m.Name, m.Blurb, m.Rounds)
		}
		return
	}
	topic := strings.TrimSpace(strings.Join(fs.Args(), " "))
	if topic == "-" {
		b, _ := io.ReadAll(os.Stdin)
		topic = strings.TrimSpace(string(b))
	}
	if topic == "" {
		fail("give a plan or question, e.g.  dialex ask --mode premortem \"Move our API to GraphQL in Q1\"")
	}
	mode, ok := quickstart.Get(*modeFlag)
	if !ok {
		fail("unknown mode %q (try: dialex ask --list)", *modeFlag)
	}

	var provider model.Provider
	var err error
	if *providerFlag != "" {
		provider, err = quickstart.ParseProvider(*providerFlag)
	} else {
		provider, err = quickstart.DetectProvider(os.Getenv)
	}
	if err != nil {
		fail("%v", err)
	}
	rnr, err := quickstart.RunnerFor(provider, os.Getenv)
	if err != nil {
		fail("%v", err)
	}
	cfg, err := quickstart.Build(mode, topic, provider, *modelFlag, *rounds)
	if err != nil {
		fail("%v", err)
	}

	calls := len(cfg.Agents())*cfg.MaxRounds + cfg.MaxRounds + 1 // turns, one tension check per round, final verdict
	fmt.Fprintf(os.Stderr, "%s on %s (%s): %d seats, %d rounds, about %d model calls. Personas on one model are a thinking aid, not independent experts.\n",
		mode.Name, provider.BrandName(), cfg.Primary.Model, len(cfg.Agents()), cfg.MaxRounds, calls)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	o := &orchestrator.Orchestrator{RunnerFor: func(model.Agent) runner.AgentRunner { return rnr }}
	res, err := o.Run(ctx, orchestrator.RunOptions{
		Config: cfg,
		OnMessage: func(m model.DebateMessage) {
			if !m.IsSystem && !m.IsError {
				fmt.Fprintf(os.Stderr, "  round %d  %s\n", m.Round, m.AuthorDisplayName)
			}
		},
	})
	if err != nil {
		fail("stopped: %v", err)
	}

	memo := quickstart.Memo(mode, topic, cfg, res)
	if *out != "" {
		if err := os.WriteFile(*out, []byte(memo), 0o644); err != nil {
			fail("write memo: %v", err)
		}
		fmt.Fprintf(os.Stderr, "memo written to %s\n", *out)
		return
	}
	fmt.Print(memo)
}

func fail(format string, a ...any) {
	fmt.Fprintf(os.Stderr, "dialex ask: "+format+"\n", a...)
	os.Exit(1)
}
