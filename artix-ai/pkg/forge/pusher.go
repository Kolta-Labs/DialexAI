package forge

import (
	"context"
	"fmt"
	"strings"

	"artix/pkg/audit"
	"artix/pkg/git"
	"artix/pkg/policy"
)

// IsProtectedBranch returns true if the target branch is a protected/default repository branch.
func IsProtectedBranch(branch string) bool {
	b := strings.ToLower(strings.TrimSpace(branch))
	b = strings.TrimPrefix(b, "refs/heads/")
	b = strings.TrimPrefix(b, "origin/")
	return b == "main" || b == "master" || b == "trunk" || b == "prod" || b == "production" ||
		strings.HasPrefix(b, "release/") || strings.HasPrefix(b, "v")
}

// NewForgePusher constructs a ForgePusher function with branch protection enforcement.
func NewForgePusher(driver *git.Driver, remote, prBranch string) func(ctx context.Context, commitSHA string) error {
	return func(ctx context.Context, commitSHA string) error {
		if IsProtectedBranch(prBranch) {
			return fmt.Errorf("branch protection violation: forbidden push directly to protected branch %q; autonomous candidates must target a dedicated PR branch", prBranch)
		}
		if driver == nil {
			return fmt.Errorf("git driver is required for ForgePusher")
		}
		refspec := fmt.Sprintf("%s:refs/heads/%s", commitSHA, prBranch)
		_, err := driver.Push(remote, refspec)
		if err != nil {
			return fmt.Errorf("failed to push candidate commit %s to %s/%s: %w", commitSHA, remote, prBranch, err)
		}
		return nil
	}
}

// CleanupCandidateBranch deletes the candidate branch on the remote forge.
func CleanupCandidateBranch(ctx context.Context, driver *git.Driver, remote, prBranch string) error {
	if IsProtectedBranch(prBranch) {
		return fmt.Errorf("refusing to delete protected branch %q", prBranch)
	}
	if driver == nil {
		return fmt.Errorf("git driver required")
	}
	_, err := driver.DeleteRemoteBranch(remote, prBranch)
	return err
}

// VerifyAndMergeCandidate executes Phase 2 of the autonomous flow: verifies server-side forge approval
// on the exact candidate commit SHA and commits audit records.
func VerifyAndMergeCandidate(ctx context.Context, driver *git.Driver, verifier func(ctx context.Context, commitSHA string) (*policy.PRApproval, error), candidateSHA string, auditLogger *audit.Logger, storyID, remote, prBranch string) (*policy.PRApproval, error) {
	if verifier == nil {
		return nil, fmt.Errorf("verifier is required for Phase 2 forge verification")
	}
	approval, err := verifier(ctx, candidateSHA)
	if err != nil {
		// On verification failure, attempt remote candidate cleanup
		if driver != nil && remote != "" && prBranch != "" {
			_ = CleanupCandidateBranch(ctx, driver, remote, prBranch)
		}
		return nil, fmt.Errorf("phase 2 forge approval verification failed: %w", err)
	}

	if approval == nil || !approval.VerifiedByForge {
		if driver != nil && remote != "" && prBranch != "" {
			_ = CleanupCandidateBranch(ctx, driver, remote, prBranch)
		}
		return nil, fmt.Errorf("separation of duties violation: unverified or forged approval")
	}

	if err := policy.ValidateForgeApproval(approval, "artix-agent", "artix-agent"); err != nil {
		if driver != nil && remote != "" && prBranch != "" {
			_ = CleanupCandidateBranch(ctx, driver, remote, prBranch)
		}
		return nil, fmt.Errorf("forge approval validation failed: %w", err)
	}

	if auditLogger != nil {
		_ = auditLogger.Emit(audit.AuditEvent{
			EventType: audit.EventCodeConvergence,
			Status:    "COMMITTED",
			Approver:  approval.ApproverUsername,
			Details: map[string]any{
				"storyId":    storyID,
				"commitHash": candidateSHA,
				"prBranch":   prBranch,
				"state":      "MERGED",
			},
		})
	}

	return approval, nil
}
