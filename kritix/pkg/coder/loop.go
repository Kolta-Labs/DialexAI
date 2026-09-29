package coder

import (
	"context"
	"fmt"
	"time"

	"kritix/pkg/git"
	"kritix/pkg/repo"
	"kritix/pkg/reviewer"
	"kritix/pkg/sandbox"
	"kritix/pkg/spec"
	"kritix/pkg/steering"
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
	MaxRounds     int                               `json:"maxRounds"`
	Autonomy      AutonomyLevel                     `json:"autonomy"`
	Domain        string                            `json:"domain"`
	TestTimeout   time.Duration                     `json:"testTimeout"`
	OnIteration   func(round int, diff string, v *reviewer.ReviewVerdict) bool
	MockPatchGen  func(round int, feedback string) string // for tests and offline runs
}

// LoopResult represents the final convergence outcome.
type LoopResult struct {
	Success       bool                     `json:"success"`
	RoundsRun     int                      `json:"roundsRun"`
	FinalVerdict  *reviewer.ReviewVerdict  `json:"finalVerdict"`
	AppliedPatch  string                   `json:"appliedPatch,omitempty"`
	CommitHash    string                   `json:"commitHash,omitempty"`
	Error         string                   `json:"error,omitempty"`
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

	var lastFeedback string
	var priorFailures []string
	var activeSession *git.PatchSession

	for round := 1; round <= maxRounds; round++ {
		res.RoundsRun = round

		// 1. Coder generates patch
		var patch string
		if opts != nil && opts.MockPatchGen != nil {
			patch = opts.MockPatchGen(round, lastFeedback)
		} else {
			// In live mode, caller provides generator or connects to model runner
			patch = ""
		}

		if patch == "" {
			res.Error = fmt.Sprintf("no patch generated in round %d", round)
			break
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
		for _, cmdStr := range s.TestCommands {
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

		// 5. Reviewer evaluates diff + tests + steering
		rCtx := &reviewer.ReviewContext{
			Diff:            diff,
			TestResults:     testResults,
			SteeringContext: revSteering,
		}
		verdict := c.reviewer.Evaluate(rCtx)
		res.FinalVerdict = verdict

		// Interactive callback hook
		if opts != nil && opts.OnIteration != nil {
			keepGoing := opts.OnIteration(round, diff, verdict)
			if !keepGoing {
				_ = activeSession.Rollback()
				res.Error = "stopped by user in interactive mode"
				return res
			}
		}

		// 6. Check convergence
		if verdict.Approved {
			res.Success = true
			res.AppliedPatch = patch

			// Handle Autonomy Gate
			if autonomy == AutonomyAutonomous {
				commitMsg := fmt.Sprintf("feat: %s (Spec: %s)", s.Title, s.ID)
				if hash, err := c.driver.CommitAll(commitMsg); err == nil {
					res.CommitHash = hash
				}
			}
			return res
		}

		// If failed, rollback this round's patch before trying next round
		_ = activeSession.Rollback()
		lastFeedback = verdict.ActionableFeedback
	}

	if !res.Success && res.Error == "" {
		res.Error = fmt.Sprintf("failed to converge after %d rounds", maxRounds)
	}

	return res
}
