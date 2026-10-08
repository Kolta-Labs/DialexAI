package coder

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
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
	ForgeVerifier                      func(ctx context.Context, commitSHA string) (*policy.PRApproval, error)
	ForgePusher                        func(ctx context.Context, commitSHA string) error
	MaxConsecutiveIdenticalRejections int                 `json:"maxConsecutiveIdenticalRejections,omitempty"`
	TestCommandsConfirmed              bool                `json:"testCommandsConfirmed,omitempty"`
	ConfirmTestCommands                func(commands []string) bool
	Model                              string              `json:"model,omitempty"`
	ReviewerModel                      string              `json:"reviewerModel,omitempty"`
	CoderUsageTracker                  func() *ProviderUsage
	ReviewerUsageTracker               func() *ProviderUsage
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

		if err := budget.ValidatePricing(); err != nil {
			res.Error = err.Error()
			res.CostReport = costReport
			return res
		}
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
		fmt.Fprintf(os.Stderr, "Proposed test commands (%d):\n", len(effectiveTestCommands))
		for i, cmd := range effectiveTestCommands {
			fmt.Fprintf(os.Stderr, "  [%d] %s\n", i+1, cmd)
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

	initialTestCount := reviewer.CountTestsInWorkspace(repoCtx.RootDir)
	initialCoverage := 0.0

	auditLogger := audit.Default(repoCtx.RootDir)
	if policy.IsEnterprise() {
		if initErr := auditLogger.InitError(); initErr != nil {
			res.Success = false
			res.Error = fmt.Sprintf("enterprise audit failure: %v (convergence refused)", initErr)
			return res
		}
	}

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

		// Mid-round budget check: abort immediately before sandbox/critic if coder tokens exhausted cap
		if budget != nil {
			modelKey := "default"
			if opts != nil && opts.Model != "" {
				modelKey = opts.Model
			}
			costPer1k, err := budget.GetModelPrice(modelKey)
			if err != nil {
				res.Error = err.Error()
				res.CostReport = costReport
				return res
			}
			coderCostUSD := (float64(totalCoderTokens) / 1000.0) * costPer1k
			var coderUsage *ProviderUsage
			if opts != nil && opts.CoderUsageTracker != nil {
				coderUsage = opts.CoderUsageTracker()
			}
			budget.RecordRoundUsage(totalCoderTokens, coderCostUSD, coderUsage)
			costReport.TotalTokens += totalCoderTokens
			costReport.TotalCost += coderCostUSD

			if exhausted, reason := budget.CheckExhaustion(); exhausted {
				roundCost := RoundCost{
					Round: round,
					RoleTokens: map[string]int{
						"coder": totalCoderTokens,
					},
					DNAOverhead: map[string]int{
						"coder_dna": coderDNATokens,
					},
					TotalRoundTokens: totalCoderTokens,
				}
				costReport.Rounds = append(costReport.Rounds, roundCost)
				costReport.DNAOverheadTokens += coderDNATokens

				costReport.Exhausted = true
				costReport.ExhaustionReason = reason
				res.CostReport = costReport
				res.Error = fmt.Sprintf("budget exhausted in round %d: %s", round, reason)
				_ = audit.Default(repoCtx.RootDir).Emit(audit.AuditEvent{
					EventType: audit.EventType("budget.exhausted"),
					Status:    "FAILED",
					Details: map[string]any{
						"storyId":          s.ID,
						"roundsRun":        round,
						"totalTokens":      costReport.TotalTokens,
						"totalCost":        costReport.TotalCost,
						"exhaustionReason": costReport.ExhaustionReason,
						"testCommandsHash": testCommandsHash,
					},
				})
				return res
			}
		}

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

		// 4b. Run configured static analyzers (Konsist, Detekt, Semgrep, SwiftLint, go vet) in sandbox
		var analyzerFindings []reviewer.AnalyzerFinding
		hasAnalyzers := opts != nil && len(opts.AnalyzerCommands) > 0
		analyzersRanSuccessfully := false
		if hasAnalyzers {
			findings, execResults := reviewer.RunAnalyzers(ctx, repoCtx.RootDir, c.sandbox, opts.AnalyzerCommands)
			analyzerFindings = findings
			allSuccess := true
			hasRealTool := false
			for i, cmd := range opts.AnalyzerCommands {
				if policy.IsAllowedSemanticRunner(cmd) {
					hasRealTool = true
				}
				if i < len(execResults) && !execResults[i].Success() {
					allSuccess = false
				}
			}
			if allSuccess && hasRealTool {
				analyzersRanSuccessfully = true
			}
			for _, f := range findings {
				if f.Severity == "ERROR" {
					priorFailures = append(priorFailures, fmt.Sprintf("Analyzer %s violation: %s", f.Tool, f.Message))
				}
			}
		}

		currentTestCount := reviewer.CountTestsInWorkspace(repoCtx.RootDir)
		var currentCoverage float64
		for _, tr := range testResults {
			if cov := reviewer.ExtractCoverage(tr.Stdout + "\n" + tr.Stderr); cov > 0 {
				currentCoverage = cov
				break
			}
		}
		if initialCoverage == 0.0 && currentCoverage > 0.0 {
			initialCoverage = currentCoverage
		}

		// 5. Reviewer evaluates diff + tests + analyzers + steering
		rCtx := &reviewer.ReviewContext{
			Diff:                     diff,
			TestResults:              testResults,
			AnalyzerFindings:         analyzerFindings,
			SteeringContext:          revSteering,
			Ctx:                      ctx,
			Criteria:                 criteriaOf(s),
			WorkspaceDir:             repoCtx.RootDir,
			TestCountBefore:          initialTestCount,
			TestCountAfter:           currentTestCount,
			CoverageBefore:           initialCoverage,
			CoverageAfter:            currentCoverage,
			RequiresTestGates:        initialTestCount > 0,
			SemanticRunnerConfigured: hasAnalyzers,
			SemanticRunnerExecuted:   analyzersRanSuccessfully,
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
		costReport.DNAOverheadTokens += coderDNATokens + reviewerDNATokens

		// Check budget exhaustion after reviewer pass
		if budget != nil {
			revModelKey := "default"
			if opts != nil && opts.ReviewerModel != "" {
				revModelKey = opts.ReviewerModel
			} else if opts != nil && opts.Model != "" {
				revModelKey = opts.Model
			}
			costPer1k, err := budget.GetModelPrice(revModelKey)
			if err != nil {
				res.Error = err.Error()
				res.CostReport = costReport
				return res
			}
			reviewerCostUSD := (float64(totalReviewerTokens) / 1000.0) * costPer1k
			var revUsage *ProviderUsage
			if opts != nil && opts.ReviewerUsageTracker != nil {
				revUsage = opts.ReviewerUsageTracker()
			}
			budget.RecordRoundUsage(totalReviewerTokens, reviewerCostUSD, revUsage)
			costReport.TotalTokens += totalReviewerTokens
			costReport.TotalCost += reviewerCostUSD

			if exhausted, reason := budget.CheckExhaustion(); exhausted {
				costReport.Exhausted = true
				costReport.ExhaustionReason = reason
				res.CostReport = costReport
				res.Error = fmt.Sprintf("budget exhausted in round %d: %s", round, reason)
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
						"testCommandsHash": testCommandsHash,
					},
				})
				return res
			}
		} else {
			costReport.TotalTokens += totalReviewerTokens
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

			var forgeApproval *policy.PRApproval
			approverIdentity := ""
			if forgeApproval != nil {
				approverIdentity = forgeApproval.ApproverUsername
			} else if opts != nil {
				approverIdentity = opts.Approver
			}

			// Autonomous commit gate: only reached when ARTIX_ALLOW_AUTONOMOUS=1 has already been
			// confirmed at loop entry (enterprise gate fires before the first round). The guard
			// below exists solely to require at least one verified test command before committing.
			if autonomy == AutonomyAutonomous {
				approver := ""
				if opts != nil {
					approver = opts.Approver
				}

				if len(testResults) == 0 {
					_ = activeSession.Rollback()
					res.Success = false
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

				headBeforeCommit, headBeforeErr := c.driver.HeadHash()
				if headBeforeErr != nil {
					_ = activeSession.Rollback()
					res.Success = false
					res.Error = fmt.Sprintf("failed to resolve repository HEAD before commit: %v", headBeforeErr)
					return res
				}

				// Commit candidate changes to produce post-patch head SHA
				commitMsg := fmt.Sprintf("feat: %s (Spec: %s)", s.Title, s.ID)
				newCommitSHA, commitErr := c.driver.CommitAll(commitMsg)
				if commitErr != nil {
					_ = activeSession.Rollback()
					res.Success = false
					res.Error = fmt.Sprintf("changes approved but commit failed: %v", commitErr)
					return res
				}
				res.CommitHash = newCommitSHA

				// Helper for safe rollback of candidate commit: ensures we NEVER reset if no new commit was made
				safeRollbackCandidate := func() {
					if currentHead, err := c.driver.HeadHash(); err == nil && currentHead == newCommitSHA && newCommitSHA != headBeforeCommit {
						_ = c.driver.ResetHard("HEAD~1")
					}
					_ = activeSession.Rollback()
				}

				if policy.IsEnterprise() || policy.Active().RequireForgeApproval {
					if opts != nil && opts.ForgeApproval != nil {
						safeRollbackCandidate()
						res.Success = false
						res.Error = "autonomous commit blocked: separation of duties violation: caller-supplied ForgeApproval is strictly forbidden in enterprise mode; approvals must be verified server-side from forge API"
						return res
					}
					if opts == nil || opts.ForgeVerifier == nil {
						safeRollbackCandidate()
						res.Success = false
						res.Error = "autonomous commit blocked: separation of duties violation: server-side forge verification is required in enterprise mode"
						return res
					}

					// If ForgePusher is configured, push the candidate commit to the PR branch on the remote
					if opts.ForgePusher != nil {
						if pushErr := opts.ForgePusher(ctx, newCommitSHA); pushErr != nil {
							safeRollbackCandidate()
							res.Success = false
							res.Error = fmt.Sprintf("autonomous candidate push to PR branch failed: %v (commit aborted, changes rolled back)", pushErr)
							return res
						}
					}

					var err error
					// Verify approval against the exact resulting commit being merged
					forgeApproval, err = opts.ForgeVerifier(ctx, newCommitSHA)
					if err != nil {
						safeRollbackCandidate()
						res.Success = false
						res.Error = fmt.Sprintf("autonomous commit blocked: %v", err)
						return res
					}

					if forgeApproval == nil || !forgeApproval.VerifiedByForge {
						safeRollbackCandidate()
						res.Success = false
						res.Error = "autonomous commit blocked: separation of duties violation: caller-supplied or forged approvals are strictly forbidden in enterprise mode; approval must be verified server-side from forge API"
						return res
					}
					if err := policy.ValidateForgeApproval(forgeApproval, "artix-agent", "artix-agent"); err != nil {
						safeRollbackCandidate()
						res.Success = false
						res.Error = fmt.Sprintf("autonomous commit blocked: %v", err)
						return res
					}
					approverIdentity = forgeApproval.ApproverUsername
				} else {
					if opts != nil && opts.ForgeApproval != nil {
						forgeApproval = opts.ForgeApproval
						approverIdentity = forgeApproval.ApproverUsername
					}
					// Validate approver unconditionally under Separation of Duties
					if err := policy.ValidateApprover("artix-agent", approver); err != nil {
						safeRollbackCandidate()
						res.Success = false
						res.Error = fmt.Sprintf("autonomous commit blocked: %v", err)
						return res
					}
				}

				// In enterprise mode, verify audit emission succeeds
				if policy.IsEnterprise() {
					preCommitAuditErr := auditLogger.Emit(audit.AuditEvent{
						EventType: audit.EventCodeConvergence,
						Status:    "PENDING_COMMIT",
						Approver:  approverIdentity,
						Details: map[string]any{
							"storyId":          s.ID,
							"roundsRun":        res.RoundsRun,
							"commitHash":       newCommitSHA,
							"testCommandsHash": testCommandsHash,
						},
					})
					if preCommitAuditErr != nil {
						safeRollbackCandidate()
						res.Success = false
						res.Error = fmt.Sprintf("enterprise audit logging failed: %v (commit aborted, changes rolled back)", preCommitAuditErr)
						return res
					}
				}
			}

			emitErr := auditLogger.Emit(audit.AuditEvent{
				EventType: audit.EventCodeConvergence,
				Status:    "SUCCESS",
				Approver:  approverIdentity,
				Details: map[string]any{
					"storyId":          s.ID,
					"roundsRun":        res.RoundsRun,
					"success":          true,
					"commitHash":       res.CommitHash,
					"totalTokens":      costReport.TotalTokens,
					"dnaOverhead":      costReport.DNAOverheadTokens,
					"testCommandsHash": testCommandsHash,
				},
			})
			if emitErr != nil && policy.IsEnterprise() {
				_ = activeSession.Rollback()
				res.Success = false
				res.Error = fmt.Sprintf("enterprise audit logging failed: %v (commit aborted, changes rolled back)", emitErr)
				return res
			}
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
