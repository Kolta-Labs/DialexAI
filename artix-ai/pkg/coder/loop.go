package coder

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"artix/pkg/audit"
	"artix/pkg/git"
	"artix/pkg/policy"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
	"artix/pkg/steering"
)

// AutonomyLevel defines human-in-the-loop gates.
type AutonomyLevel string

const (
	AutonomySupervised  AutonomyLevel = "supervised"  // Confirm before apply & commit
	AutonomyInteractive AutonomyLevel = "interactive" // Callback per iteration
	AutonomyAutonomous  AutonomyLevel = "autonomous"  // Auto-commit on green tests
)

// LoopOptions specifies execution limits and autonomy behavior.
type LoopOptions struct {
	MaxRounds        int           `json:"maxRounds"`
	Autonomy         AutonomyLevel `json:"autonomy"`
	Domain           string        `json:"domain"`
	TestTimeout      time.Duration `json:"testTimeout"`
	Budget           *TokenBudget  `json:"budget,omitempty"`
	AnalyzerCommands []string      `json:"analyzerCommands,omitempty"`
	OnIteration      func(round int, diff string, v *reviewer.ReviewVerdict) bool
	MockPatchGen     func(round int, feedback string) string // for tests and offline runs
	// PatchGenerator produces each round's patch, typically NewRunnerPatchGenerator. Without
	// it (or MockPatchGen) the loop cannot generate code and stops immediately.
	PatchGenerator                     PatchGenerator
	Approver                           string              `json:"approver,omitempty"`
	ForgeApproval                      *policy.PRApproval  `json:"forgeApproval,omitempty"`
	MaxConsecutiveIdenticalRejections int                 `json:"maxConsecutiveIdenticalRejections,omitempty"`
	TestCommandsConfirmed              bool                `json:"testCommandsConfirmed,omitempty"`
	ConfirmTestCommands                func(commands []string) bool
}

// LoopResult represents the final convergence outcome.
type LoopResult struct {
	Success      bool                    `json:"success"`
	RoundsRun    int                     `json:"roundsRun"`
	FinalVerdict *reviewer.ReviewVerdict `json:"finalVerdict"`
	AppliedPatch string                  `json:"appliedPatch,omitempty"`
	CommitHash   string                  `json:"commitHash,omitempty"`
	CostReport   *CostReport             `json:"costReport,omitempty"`
	Error        string                  `json:"error,omitempty"`
}

// ConvergenceCoordinator manages the iterative Coder <--> Reviewer <--> Sandbox loop.
type ConvergenceCoordinator struct {
	coder    *DomainCoder
	reviewer *reviewer.AdversarialReviewer
	driver   *git.Driver
	sandbox  *sandbox.Sandbox
}

// NewCoordinator creates a new convergence coordinator.
func NewCoordinator(
	coder *DomainCoder,
	rev *reviewer.AdversarialReviewer,
	driver *git.Driver,
	box *sandbox.Sandbox,
) *ConvergenceCoordinator {
	return &ConvergenceCoordinator{
		coder:    coder,
		reviewer: rev,
		driver:   driver,
		sandbox:  box,
	}
}

// Run executes the convergence loop until tests pass and reviewer signs off.
func (c *ConvergenceCoordinator) Run(
	ctx context.Context,
	s *spec.StorySpec,
	repoCtx *repo.RepositoryContext,
	coderSteering *steering.PersonaSteeringContext,
	revSteering *steering.PersonaSteeringContext,
	opts *LoopOptions,
) *LoopResult {
	maxRounds := 3
	autonomy := AutonomySupervised

	if opts != nil {
		if opts.MaxRounds > 0 {
			maxRounds = opts.MaxRounds
		}
		if opts.Autonomy != "" {
			autonomy = opts.Autonomy
		}
	}

	res := &LoopResult{
		Success:   false,
		RoundsRun: 0,
	}

	var budget *TokenBudget
	if opts != nil && opts.Budget != nil {
		budget = opts.Budget
	} else {
		budget = LoadBudgetFromEnv()
	}

	costReport := &CostReport{
		StoryID: s.ID,
		Rounds:  make([]RoundCost, 0),
	}
	if budget != nil {
		costReport.BudgetStoryCap = budget.MaxStoryTokens
		costReport.BudgetTeamCap = budget.MaxTeamTokens
		costReport.BudgetDayCap = budget.MaxDayTokens
		costReport.BudgetStoryCostCap = budget.MaxStoryCost
		costReport.BudgetTeamCostCap = budget.MaxTeamCost
		costReport.BudgetDayCostCap = budget.MaxDayCost
		costReport.TeamID = budget.TeamID
	}

	// Enterprise Autonomous Gate (ARTIX-SEC-02):
	// In enterprise/CI environments, autonomous commits require an explicit operator opt-in via
	// policy or ARTIX_ALLOW_AUTONOMOUS=1.
	if autonomy == AutonomyAutonomous {
		if !policy.IsAutonomousAllowed() {
			res.Error = "autonomous commit blocked: in enterprise/CI environments, autonomous commits require explicit ARTIX_ALLOW_AUTONOMOUS=1 opt-in or enterprise policy enablement"
			res.CostReport = costReport
			return res
		}
	}

	// Validate test commands against signed enterprise policy before any execution
	effectiveTestCommands, pErr := policy.ValidateTestCommands(s.TestCommands)
	if pErr != nil {
		res.Error = fmt.Sprintf("test execution blocked by policy: %v", pErr)
		res.CostReport = costReport
		return res
	}

	// Compute test commands hash for provenance and audit verification
	cmdBytes := []byte(strings.Join(effectiveTestCommands, "\n"))
	cmdHashArr := sha256.Sum256(cmdBytes)
	testCommandsHash := hex.EncodeToString(cmdHashArr[:])

	// G4: Test-command trust outside enterprise:
	// In supervised/interactive mode outside enterprise, require explicit user confirmation and print command list
	if !policy.IsEnterprise() && opts != nil && (opts.Autonomy == AutonomySupervised || opts.Autonomy == AutonomyInteractive) {
		fmt.Printf("Proposed test commands (%d):\n", len(effectiveTestCommands))
		for i, cmd := range effectiveTestCommands {
			fmt.Printf("  [%d] %s\n", i+1, cmd)
		}

		confirmed := false
		if opts != nil {
			if opts.ConfirmTestCommands != nil {
				confirmed = opts.ConfirmTestCommands(effectiveTestCommands)
			} else if opts.TestCommandsConfirmed {
				confirmed = true
			}
		}
		if !confirmed {
			res.Error = "unconfirmed test commands: supervised/interactive mode outside enterprise requires explicit user confirmation of test commands"
			res.CostReport = costReport
			return res
		}
	}

	var lastFeedback string
	var lastVerdictFeedback string
	var consecutiveIdenticalCount int
	maxIdentical := 2
	if opts != nil && opts.MaxConsecutiveIdenticalRejections > 0 {
		maxIdentical = opts.MaxConsecutiveIdenticalRejections
	}
	var priorFailures []string
	var activeSession *git.PatchSession

	for round := 1; round <= maxRounds; round++ {
		res.RoundsRun = round

		// 1. Coder generates patch
		var patch string
		promptCtx := PromptContext{
			Spec:             s,
			RepoContext:      repoCtx,
			SteeringContext:  coderSteering,
			ReviewerFeedback: lastFeedback,
			PriorFailures:    priorFailures,
		}
		sysPrompt, userPrompt := c.coder.CompilePrompt(&promptCtx)
		coderPromptTokens := EstimateTokens(sysPrompt) + EstimateTokens(userPrompt)
		coderDNATokens := 0
		if c.coder.persona.DNA != nil {
			coderDNATokens = EstimateTokens(CompileDNALayers(c.coder.persona.DNA, TaskCodeGeneration))
		}

		if opts != nil && opts.MockPatchGen != nil {
			patch = opts.MockPatchGen(round, lastFeedback)
		} else if opts != nil && opts.PatchGenerator != nil {
			var genErr error
			patch, genErr = opts.PatchGenerator(ctx, PatchRequest{Round: round, Feedback: lastFeedback, PriorFailures: priorFailures})
			if genErr != nil {
				res.Error = fmt.Sprintf("patch generation failed in round %d: %v", round, genErr)
				break
			}
		} else {
			res.Error = "no patch generator configured: pass LoopOptions.PatchGenerator (a model-backed generator) to generate code"
			break
		}

		if patch == "" {
			res.Error = fmt.Sprintf("no patch generated in round %d", round)
			break
		}

		coderPatchTokens := EstimateTokens(patch)
		totalCoderTokens := coderPromptTokens + coderPatchTokens

		// 2. Apply patch via PatchSession
		activeSession = git.NewPatchSession(repoCtx.RootDir, patch)
		if err := activeSession.Apply(); err != nil {
			lastFeedback = fmt.Sprintf("Patch application failed: %v", err)
			continue
		}

		// 3. Inspect git diff
		diff, err := c.driver.Diff(false)
		if err != nil {
			diff = patch
		}

		// 4. Sandbox executes project test commands
		var testResults []*sandbox.ExecResult
		priorFailures = nil // only the latest round's failures are relevant to the next attempt

		// Script/Makefile indirection check: refuse commands referencing modified scripts/Makefiles before execution
		touchedByPatch := extractTouchedFilesFromDiff(diff)
		var indirectionViolations []string
		for _, cmdStr := range effectiveTestCommands {
			for _, tf := range touchedByPatch {
				if isScriptOrBuildFile(tf) {
					base := filepath.Base(tf)
					if strings.Contains(cmdStr, tf) || strings.Contains(cmdStr, "./"+tf) || (base != "" && strings.Contains(cmdStr, base)) {
						indirectionViolations = append(indirectionViolations, fmt.Sprintf("test execution blocked: command %q references file %q modified by patch (script/Makefile indirection forbidden)", cmdStr, tf))
					}
				}
			}
		}

		if len(indirectionViolations) > 0 {
			for _, iv := range indirectionViolations {
				priorFailures = append(priorFailures, iv)
				testResults = append(testResults, &sandbox.ExecResult{
					Command:  effectiveTestCommands[0],
					ExitCode: 1,
					Stderr:   iv,
				})
			}
		} else {
			for _, cmdStr := range effectiveTestCommands {
				tOpts := &sandbox.ExecOptions{
					Cwd:     repoCtx.RootDir,
					Timeout: 2 * time.Minute,
				}
				if opts != nil && opts.TestTimeout > 0 {
					tOpts.Timeout = opts.TestTimeout
				}
				tRes := c.sandbox.Run(ctx, cmdStr, tOpts)
				testResults = append(testResults, tRes)
				if !tRes.Success() {
					priorFailures = append(priorFailures, fmt.Sprintf("%s: exit %d\n%s", cmdStr, tRes.ExitCode, tRes.Stderr))
				}
			}
		}

		// 4b. Run configured static analyzers (Konsist, Detekt, Semgrep, go vet) in sandbox
		var analyzerFindings []reviewer.AnalyzerFinding
		if opts != nil && len(opts.AnalyzerCommands) > 0 {
			findings, _ := reviewer.RunAnalyzers(ctx, repoCtx.RootDir, c.sandbox, opts.AnalyzerCommands)
			analyzerFindings = findings
			for _, f := range findings {
				if f.Severity == "ERROR" {
					priorFailures = append(priorFailures, fmt.Sprintf("Analyzer %s violation: %s", f.Tool, f.Message))
				}
			}
		}

		// 5. Reviewer evaluates diff + tests + analyzers + steering
		rCtx := &reviewer.ReviewContext{
			Diff:             diff,
			TestResults:      testResults,
			AnalyzerFindings: analyzerFindings,
			SteeringContext:  revSteering,
			Ctx:              ctx,
			Criteria:         criteriaOf(s),
		}
		verdict := c.reviewer.Evaluate(rCtx)
		res.FinalVerdict = verdict

		// Track reviewer tokens and round cost
		reviewerPromptTokens := EstimateTokens(diff)
		for _, crit := range criteriaOf(s) {
			reviewerPromptTokens += EstimateTokens(crit)
		}
		reviewerVerdictTokens := 0
		if verdict != nil {
			reviewerVerdictTokens = EstimateTokens(verdict.Summary) + EstimateTokens(verdict.ActionableFeedback)
			for _, bi := range verdict.BlockingIssues {
				reviewerVerdictTokens += EstimateTokens(bi)
			}
			for _, w := range verdict.Warnings {
				reviewerVerdictTokens += EstimateTokens(w)
			}
		}
		totalReviewerTokens := reviewerPromptTokens + reviewerVerdictTokens
		reviewerDNATokens := 0
		if revSteering != nil && len(revSteering.Taboos.ForbiddenArguments) > 0 {
			reviewerDNATokens = EstimateTokens(fmt.Sprintf("%v", revSteering.Taboos.ForbiddenArguments))
		}

		roundCost := RoundCost{
			Round: round,
			RoleTokens: map[string]int{
				"coder":    totalCoderTokens,
				"reviewer": totalReviewerTokens,
			},
			DNAOverhead: map[string]int{
				"coder_dna":    coderDNATokens,
				"reviewer_dna": reviewerDNATokens,
			},
			TotalRoundTokens: totalCoderTokens + totalReviewerTokens,
		}
		costReport.Rounds = append(costReport.Rounds, roundCost)
		costReport.TotalTokens += roundCost.TotalRoundTokens
		costReport.DNAOverheadTokens += coderDNATokens + reviewerDNATokens

		// Check budget exhaustion (Tokens & Financial USD Caps)
		if budget != nil {
			costPer1k := budget.CostPer1kTokens
			if costPer1k <= 0 {
				costPer1k = 0.003 // Default USD 0.003 per 1,000 tokens (frontier model blended baseline)
			}
			roundCostUSD := (float64(roundCost.TotalRoundTokens) / 1000.0) * costPer1k

			budget.RecordRoundUsage(roundCost.TotalRoundTokens, roundCostUSD)
			costReport.TotalCost += roundCostUSD

			if budget.MaxStoryTokens > 0 && budget.UsedStoryTokens > budget.MaxStoryTokens {
				costReport.Exhausted = true
				costReport.ExhaustionReason = fmt.Sprintf("story token budget exceeded: %d used > %d max cap", budget.UsedStoryTokens, budget.MaxStoryTokens)
			} else if budget.MaxTeamTokens > 0 && budget.UsedTeamTokens > budget.MaxTeamTokens {
				costReport.Exhausted = true
				costReport.ExhaustionReason = fmt.Sprintf("team token budget exceeded: %d used > %d max cap", budget.UsedTeamTokens, budget.MaxTeamTokens)
			} else if budget.MaxDayTokens > 0 && budget.UsedDayTokens > budget.MaxDayTokens {
				costReport.Exhausted = true
				costReport.ExhaustionReason = fmt.Sprintf("daily token budget exceeded: %d used > %d max cap", budget.UsedDayTokens, budget.MaxDayTokens)
			} else if budget.MaxStoryCost > 0 && budget.UsedStoryCost > budget.MaxStoryCost {
				costReport.Exhausted = true
				costReport.ExhaustionReason = fmt.Sprintf("story cost budget exceeded: $%.4f used > $%.4f max cap", budget.UsedStoryCost, budget.MaxStoryCost)
			} else if budget.MaxTeamCost > 0 && budget.UsedTeamCost > budget.MaxTeamCost {
				costReport.Exhausted = true
				costReport.ExhaustionReason = fmt.Sprintf("team cost budget exceeded: $%.4f used > $%.4f max cap", budget.UsedTeamCost, budget.MaxTeamCost)
			} else if budget.MaxDayCost > 0 && budget.UsedDayCost > budget.MaxDayCost {
				costReport.Exhausted = true
				costReport.ExhaustionReason = fmt.Sprintf("daily cost budget exceeded: $%.4f used > $%.4f max cap", budget.UsedDayCost, budget.MaxDayCost)
			}

			if costReport.Exhausted {
				res.CostReport = costReport
				res.Error = fmt.Sprintf("budget exhausted in round %d: %s", round, costReport.ExhaustionReason)
				if activeSession != nil {
					_ = activeSession.Rollback()
				}
				_ = audit.Default(repoCtx.RootDir).Emit(audit.AuditEvent{
					EventType: audit.EventType("budget.exhausted"),
					Status:    "FAILED",
					Details: map[string]any{
						"storyId":          s.ID,
						"roundsRun":        round,
						"totalTokens":      costReport.TotalTokens,
						"totalCost":        costReport.TotalCost,
						"exhaustionReason": costReport.ExhaustionReason,
						"budgetStoryCap":   costReport.BudgetStoryCap,
						"budgetTeamCap":    costReport.BudgetTeamCap,
						"budgetDayCap":     costReport.BudgetDayCap,
						"budgetStoryCost":  costReport.BudgetStoryCostCap,
						"budgetTeamCost":   costReport.BudgetTeamCostCap,
						"budgetDayCost":    costReport.BudgetDayCostCap,
						"dnaOverhead":      costReport.DNAOverheadTokens,
					},
				})
				return res
			}
		}

		// Handle unreviewed status: fail closed and block auto-commit
		if verdict.Status == reviewer.StatusUnreviewed {
			if activeSession != nil {
				_ = activeSession.Rollback()
			}
			res.Success = false
			res.Error = "autonomous commit blocked: review status is 'unreviewed' (model critic was unavailable)"
			return res
		}

		// Interactive callback hook
		if opts != nil && opts.OnIteration != nil {
			keepGoing := opts.OnIteration(round, diff, verdict)
			if !keepGoing {
				_ = activeSession.Rollback()
				res.Error = "stopped by user in interactive mode"
				res.CostReport = costReport
				return res
			}
		}

		// 6. Check convergence
		if verdict.Approved && verdict.Status == reviewer.StatusApproved {
			res.Success = true
			res.AppliedPatch = patch
			res.CostReport = costReport

			// Autonomous commit gate: only reached when ARTIX_ALLOW_AUTONOMOUS=1 has already been
			// confirmed at loop entry (enterprise gate fires before the first round). The guard
			// below exists solely to require at least one verified test command before committing.
			if autonomy == AutonomyAutonomous {
				approver := ""
				var forgeApproval *policy.PRApproval
				if opts != nil {
					approver = opts.Approver
					forgeApproval = opts.ForgeApproval
				}

				if policy.IsEnterprise() || policy.Active().RequireForgeApproval {
					if forgeApproval == nil || !forgeApproval.VerifiedByForge {
						_ = activeSession.Rollback()
						res.Success = false
						res.Error = "autonomous commit blocked: separation of duties violation: caller-supplied or forged approvals are strictly forbidden in enterprise mode; approval must be verified server-side from forge API"
						return res
					}
					if err := policy.ValidateForgeApproval(forgeApproval, "artix-agent", "artix-agent"); err != nil {
						_ = activeSession.Rollback()
						res.Success = false
						res.Error = fmt.Sprintf("autonomous commit blocked: %v", err)
						return res
					}
				} else {
					// Validate approver unconditionally under Separation of Duties
					if err := policy.ValidateApprover("artix-agent", approver); err != nil {
						_ = activeSession.Rollback()
						res.Success = false
						res.Error = fmt.Sprintf("autonomous commit blocked: %v", err)
						return res
					}
				}

				if len(testResults) == 0 {
					res.Error = "autonomous commit refused: the spec defines no test commands, so nothing verified the change"
					return res
				}

				// Post-merge test re-verification: Ensure all test commands pass against the final
				// state before committing (double-checks race conditions vs. the mid-loop run).
				for _, cmdStr := range effectiveTestCommands {
					tOpts := &sandbox.ExecOptions{
						Cwd:     repoCtx.RootDir,
						Timeout: 2 * time.Minute,
					}
					if opts != nil && opts.TestTimeout > 0 {
						tOpts.Timeout = opts.TestTimeout
					}
					reverify := c.sandbox.Run(ctx, cmdStr, tOpts)
					if !reverify.Success() {
						_ = activeSession.Rollback()
						res.Success = false
						res.Error = fmt.Sprintf("post-merge test re-verification failed on %q: exit %d (commit aborted, changes rolled back)", cmdStr, reverify.ExitCode)
						return res
					}
				}

				commitMsg := fmt.Sprintf("feat: %s (Spec: %s)", s.Title, s.ID)
				hash, err := c.driver.CommitAll(commitMsg)
				if err != nil {
					res.Error = fmt.Sprintf("changes approved but commit failed: %v", err)
				} else {
					res.CommitHash = hash
				}
			}

			approverIdentity := ""
			if opts != nil {
				if opts.ForgeApproval != nil {
					approverIdentity = opts.ForgeApproval.ApproverUsername
				} else {
					approverIdentity = opts.Approver
				}
			}
			_ = audit.Default(repoCtx.RootDir).Emit(audit.AuditEvent{
				EventType: audit.EventCodeConvergence,
				Status:    "SUCCESS",
				Approver:  approverIdentity,
				Details: map[string]any{
					"storyId":          s.ID,
					"roundsRun":        res.RoundsRun,
					"success":          true,
					"totalTokens":      costReport.TotalTokens,
					"dnaOverhead":      costReport.DNAOverheadTokens,
					"testCommandsHash": testCommandsHash,
				},
			})
			return res
		}

		// If failed, rollback this round's patch before trying next round
		_ = activeSession.Rollback()
		lastFeedback = verdict.ActionableFeedback

		if !verdict.Approved {
			if verdict.ActionableFeedback == lastVerdictFeedback && verdict.ActionableFeedback != "" {
				consecutiveIdenticalCount++
				if consecutiveIdenticalCount >= maxIdentical && round < maxRounds {
					res.Error = fmt.Sprintf("early convergence loop termination: %d consecutive identical rejections encountered", consecutiveIdenticalCount)
					res.CostReport = costReport
					return res
				}
			} else {
				consecutiveIdenticalCount = 1
				lastVerdictFeedback = verdict.ActionableFeedback
			}
		}
	}

	res.CostReport = costReport
	if !res.Success && res.Error == "" {
		res.Error = fmt.Sprintf("failed to converge after %d rounds", maxRounds)
	}

	_ = audit.Default(repoCtx.RootDir).Emit(audit.AuditEvent{
		EventType: audit.EventCodeConvergence,
		Status:    "FAILED",
		Details: map[string]any{
			"storyId":          s.ID,
			"roundsRun":        res.RoundsRun,
			"success":          res.Success,
			"totalTokens":      costReport.TotalTokens,
			"dnaOverhead":      costReport.DNAOverheadTokens,
			"testCommandsHash": testCommandsHash,
			"error":            res.Error,
		},
	})

	return res
}

// criteriaOf flattens a spec's Gherkin scenarios into one line each for the model reviewer.
func criteriaOf(s *spec.StorySpec) []string {
	var out []string
	for _, sc := range s.AcceptanceCriteria {
		out = append(out, fmt.Sprintf("%s: Given %s, When %s, Then %s", sc.Name, sc.Given, sc.When, sc.Then))
	}
	return out
}

func extractTouchedFilesFromDiff(diff string) []string {
	var files []string
	lines := strings.Split(diff, "\n")
	reDiff := regexp.MustCompile(`^diff --git a/(.*) b/(.*)$`)
	rePlus := regexp.MustCompile(`^\+\+\+ b/(.*)$`)
	for _, line := range lines {
		if m := reDiff.FindStringSubmatch(line); len(m) > 2 {
			files = append(files, m[2])
		} else if m := rePlus.FindStringSubmatch(line); len(m) > 1 {
			if m[1] != "/dev/null" {
				files = append(files, m[1])
			}
		}
	}
	return files
}

func isScriptOrBuildFile(path string) bool {
	base := filepath.Base(path)
	ext := strings.ToLower(filepath.Ext(path))
	if base == "Makefile" || base == "makefile" || base == "GNUmakefile" {
		return true
	}
	if ext == ".sh" || ext == ".bash" || ext == ".zsh" || ext == ".py" || ext == ".rb" || ext == ".pl" {
		return true
	}
	return false
}
