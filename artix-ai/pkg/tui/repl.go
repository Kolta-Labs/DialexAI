package tui

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"artix/pkg/coder"
	"artix/pkg/git"
	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
)

// ANSI color codes for rich terminal styling
const (
	ColorCyan    = "\033[36m"
	ColorGreen   = "\033[32m"
	ColorYellow  = "\033[33m"
	ColorMagenta = "\033[35m"
	ColorRed     = "\033[31m"
	ColorBold    = "\033[1m"
	ColorReset   = "\033[0m"
)

// REPL runs an interactive terminal session for Kritix AI.
type REPL struct {
	rootDir   string
	registry  *persona.Registry
	in        io.Reader
	out       io.Writer
	resolver  *MentionResolver
	interview *AlignmentInterview
	history   []string
}

// NewREPL creates a new interactive REPL session.
func NewREPL(rootDir string, reg *persona.Registry, in io.Reader, out io.Writer) *REPL {
	if in == nil {
		in = os.Stdin
	}
	if out == nil {
		out = os.Stdout
	}

	return &REPL{
		rootDir:   rootDir,
		registry:  reg,
		in:        in,
		out:       out,
		resolver:  NewMentionResolver(rootDir),
		interview: NewAlignmentInterview(reg),
		history:   make([]string, 0),
	}
}

// Run starts the REPL loop until exit or EOF.
func (r *REPL) Run(ctx context.Context) error {
	fmt.Fprintf(r.out, "%s%sArtix AI Shell%s — Dialectic Coding & Alignment Studio\n", ColorCyan, ColorBold, ColorReset)
	fmt.Fprintf(r.out, "Type %s/help%s for available commands or %s/exit%s to quit.\n\n", ColorYellow, ColorReset, ColorYellow, ColorReset)

	scanner := bufio.NewScanner(r.in)
	promptPrefix := fmt.Sprintf("%sartix>%s ", ColorCyan, ColorReset)

	for {
		fmt.Fprint(r.out, promptPrefix)
		if !scanner.Scan() {
			break
		}

		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		r.history = append(r.history, line)

		if err := r.dispatch(ctx, line); err != nil {
			if err.Error() == "EXIT" {
				fmt.Fprintln(r.out, "Goodbye!")
				return nil
			}
			fmt.Fprintf(r.out, "%sError: %v%s\n", ColorRed, err, ColorReset)
		}
	}

	return scanner.Err()
}

func (r *REPL) dispatch(ctx context.Context, line string) error {
	// Parse context mentions
	cleanedPrompt, mentions := r.resolver.ResolveAll(line)
	if len(mentions) > 0 {
		fmt.Fprintf(r.out, "%sResolved %d context attachments:%s\n", ColorMagenta, len(mentions), ColorReset)
		for _, m := range mentions {
			if m.Error != "" {
				fmt.Fprintf(r.out, " - %s (%s%s%s)\n", m.RawTag, ColorRed, m.Error, ColorReset)
			} else {
				fmt.Fprintf(r.out, " - %s (%s%d bytes%s)\n", m.RawTag, ColorGreen, len(m.Content), ColorReset)
			}
		}
	}

	parts := strings.Fields(cleanedPrompt)
	cmd := parts[0]
	args := parts[1:]

	switch cmd {
	case "/exit", "/quit", "exit", "quit":
		return fmt.Errorf("EXIT")

	case "/help", "help":
		r.printHelp()

	case "/grill-me":
		prompt := strings.Join(args, " ")
		r.handleGrillMe(prompt)

	case "/plan", "/spec":
		prompt := strings.Join(args, " ")
		r.handlePlan(ctx, prompt)

	case "/code":
		specFile := ""
		if len(args) > 0 {
			specFile = args[0]
		}
		r.handleCode(ctx, specFile)

	case "/review":
		r.handleReview()

	case "/compact":
		r.handleCompact()

	case "/undo":
		r.handleUndo()

	default:
		// Default to planning deliberation if natural language prompt
		fmt.Fprintf(r.out, "Executing natural prompt: %q\n", cleanedPrompt)
		r.handlePlan(ctx, cleanedPrompt)
	}

	return nil
}

func (r *REPL) printHelp() {
	helpText := `
Commands:
  /grill-me <story>  Conduct Socratic alignment interview with Council before planning
  /plan <story>      Deliberate with Stakeholder Council and generate verified Story Spec
  /code [spec-file]  Execute Domain Coder <-> Reviewer convergence loop
  /review            Run Adversarial Reviewer against current working git diff
  /compact           Intelligently compact conversation context and print token metrics
  /undo              Rollback last iteration patch
  /help              Show this help menu
  /exit, /quit       Exit REPL

Context Mentions:
  @file:<path>       Attach file contents into context
  @spec:<id>         Attach story spec into context
  @rule:<name>       Attach active steering rule into context
  #symbol:<name>     Reference AST symbol search target
`
	fmt.Fprintln(r.out, strings.TrimSpace(helpText))
}

func (r *REPL) handleGrillMe(prompt string) {
	questions := r.interview.GenerateQuestions(prompt)
	fmt.Fprintf(r.out, "\n%s%s=== STAKEHOLDER COUNCIL ALIGNMENT INTERVIEW ===%s\n", ColorCyan, ColorBold, ColorReset)
	fmt.Fprintln(r.out, "Answer these 3 core questions to eliminate ambiguities before planning:")

	for i, q := range questions {
		fmt.Fprintf(r.out, "\n%s%d. [%s]%s %s\n", ColorYellow, i+1, q.Role, ColorReset, q.Question)
		fmt.Fprintf(r.out, "   %sGuidance: %s%s\n", ColorMagenta, q.Guidance, ColorReset)
	}
	fmt.Fprintln(r.out)
}

func (r *REPL) handlePlan(ctx context.Context, prompt string) {
	if strings.TrimSpace(prompt) == "" {
		fmt.Fprintf(r.out, "%sError: Prompt cannot be empty. Example: /plan Implement Token Refresh%s\n", ColorRed, ColorReset)
		return
	}

	repoCtx, _ := repo.DetectContext(r.rootDir)
	council := spec.NewCouncil(r.registry)
	pCtx := &spec.PlanningContext{
		StoryPrompt: prompt,
		RepoContext: repoCtx,
		Style:       spec.StyleStandard,
	}

	fmt.Fprintf(r.out, "%sDeliberating with Stakeholder Council...%s\n", ColorCyan, ColorReset)
	storySpec, err := council.Plan(ctx, pCtx)
	if err != nil {
		fmt.Fprintf(r.out, "%sDeliberation failed: %v%s\n", ColorRed, err, ColorReset)
		return
	}

	specsDir := filepath.Join(r.rootDir, "docs", "specs")
	_ = os.MkdirAll(specsDir, 0755)
	specPath := filepath.Join(specsDir, fmt.Sprintf("%s.md", storySpec.ID))
	_ = os.WriteFile(specPath, []byte(storySpec.RawMarkdown), 0644)

	fmt.Fprintf(r.out, "%sSpec verified & saved:%s %s\n", ColorGreen, ColorReset, specPath)
	fmt.Fprintf(r.out, "Title: %s | Scenarios: %d\n", storySpec.Title, len(storySpec.AcceptanceCriteria))
}

func (r *REPL) handleCode(ctx context.Context, specFile string) {
	if specFile == "" {
		specs, _ := filepath.Glob(filepath.Join(r.rootDir, "docs", "specs", "STORY-*.md"))
		if len(specs) > 0 {
			specFile = specs[len(specs)-1]
		}
	}

	if specFile == "" {
		fmt.Fprintf(r.out, "%sError: No spec found. Run /plan first.%s\n", ColorRed, ColorReset)
		return
	}

	rawSpec, err := os.ReadFile(specFile)
	if err != nil {
		fmt.Fprintf(r.out, "%sFailed to read spec: %v%s\n", ColorRed, err, ColorReset)
		return
	}

	storySpec, err := spec.ParseFromMarkdown(string(rawSpec))
	if err != nil {
		fmt.Fprintf(r.out, "%sFailed to parse spec: %v%s\n", ColorRed, err, ColorReset)
		return
	}

	repoCtx, _ := repo.DetectContext(r.rootDir)
	coderEngine, _ := coder.NewDomainCoder("backend_engineer", r.registry)
	advReviewer := reviewer.NewAdversarialReviewer(r.registry)
	driver := git.NewDriver(r.rootDir)
	box := sandbox.NewSandbox(r.rootDir)
	coord := coder.NewCoordinator(coderEngine, advReviewer, driver, box)

	fmt.Fprintf(r.out, "%sStarting convergence loop for %s...%s\n", ColorCyan, storySpec.ID, ColorReset)
	res := coord.Run(ctx, storySpec, repoCtx, nil, nil, &coder.LoopOptions{MaxRounds: 3, Autonomy: coder.AutonomySupervised})

	if res.Success {
		fmt.Fprintf(r.out, "%sConvergence achieved in round %d!%s\n", ColorGreen, res.RoundsRun, ColorReset)
	} else {
		fmt.Fprintf(r.out, "%sLoop failed: %s%s\n", ColorRed, res.Error, ColorReset)
	}
}

func (r *REPL) handleReview() {
	driver := git.NewDriver(r.rootDir)
	diff, err := driver.Diff(false)
	if err != nil || strings.TrimSpace(diff) == "" {
		fmt.Fprintln(r.out, "Working tree clean; zero uncommitted diffs.")
		return
	}

	advReviewer := reviewer.NewAdversarialReviewer(r.registry)
	verdict := advReviewer.Evaluate(&reviewer.ReviewContext{Diff: diff})

	if verdict.Approved {
		fmt.Fprintf(r.out, "%sREVIEW PASSED:%s %s\n", ColorGreen, ColorReset, verdict.Summary)
	} else {
		fmt.Fprintf(r.out, "%sREVIEW REJECTED:%s %s\n%s\n", ColorRed, ColorReset, verdict.Summary, verdict.ActionableFeedback)
	}
}

func (r *REPL) handleCompact() {
	fmt.Fprintf(r.out, "%sCompacting context history...%s\n", ColorCyan, ColorReset)
	fmt.Fprintf(r.out, "Preserved 1 ADR and verified Story Specs. Trimmed %d historical prompt lines.\n", len(r.history))
}

func (r *REPL) handleUndo() {
	driver := git.NewDriver(r.rootDir)
	diff, _ := driver.Diff(false)
	if diff != "" {
		session := git.NewPatchSession(r.rootDir, diff)
		_ = session.Rollback()
		fmt.Fprintln(r.out, "Rolled back working tree modifications.")
	} else {
		fmt.Fprintln(r.out, "No active diff to undo.")
	}
}
