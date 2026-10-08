package forge

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"os"
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

	// Exact matches for standard protected/default branches
	if b == "main" || b == "master" || b == "trunk" || b == "develop" || b == "dev" || b == "staging" || b == "prod" || b == "production" {
		return true
	}
	// Prefix matches for standard protected branch hierarchies
	if strings.HasPrefix(b, "release/") || strings.HasPrefix(b, "releases/") || strings.HasPrefix(b, "hotfix/") || strings.HasPrefix(b, "hotfixes/") {
		return true
	}
	return false
}

// IsDefinitiveRejection returns true only if the error represents an explicit human/policy rejection
// (e.g., changes requested, dismissed review, stale commit mismatch, author self-approval),
// and false for non-definitive states (no reviews yet, 5xx server errors, network timeouts).
func IsDefinitiveRejection(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	if strings.Contains(msg, "changes requested") ||
		strings.Contains(msg, "dismissed") ||
		strings.Contains(msg, "stale commit") ||
		strings.Contains(msg, "cannot approve their own") ||
		strings.Contains(msg, "not authorized in allowedapprovers") ||
		strings.Contains(msg, "unverified or forged") ||
		strings.Contains(msg, "bot or app account") {
		return true
	}
	return false
}

// verifyPhase1AuditBinding asserts that the candidate SHA and spec ID are bound to a Phase 1 audit record.
func verifyPhase1AuditBinding(logPath, storyID, candidateSHA string) error {
	if logPath == "" {
		return nil
	}
	f, err := os.Open(logPath)
	if err != nil {
		if os.IsNotExist(err) {
			return fmt.Errorf("phase 1 audit log missing: no records found at %s", logPath)
		}
		return fmt.Errorf("cannot read audit log: %w", err)
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	foundBinding := false

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		var ev audit.AuditEvent
		if err := json.Unmarshal([]byte(line), &ev); err != nil {
			continue
		}

		evStoryID := ev.StorySpecID
		evCandidateSHA := ""
		if ev.Details != nil {
			if s, ok := ev.Details["storyId"].(string); ok && s != "" {
				evStoryID = s
			}
			if c, ok := ev.Details["candidateSHA"].(string); ok && c != "" {
				evCandidateSHA = c
			} else if c, ok := ev.Details["commitHash"].(string); ok && c != "" {
				evCandidateSHA = c
			}
		}

		if (ev.EventType == "CANDIDATE_PUSHED" || ev.EventType == audit.EventCodeConvergence || ev.EventType == "code.convergence") &&
			evStoryID == storyID && evCandidateSHA == candidateSHA {
			foundBinding = true
			break
		}
	}

	if !foundBinding {
		return fmt.Errorf("candidate SHA %s is not bound to a signed Phase 1 audit record for spec %s", candidateSHA, storyID)
	}
	return nil
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

	// Verify Phase 1 audit binding when audit logger is configured
	if auditLogger != nil {
		if err := verifyPhase1AuditBinding(auditLogger.LogPath(), storyID, candidateSHA); err != nil {
			return nil, fmt.Errorf("phase 2 verification error: %w", err)
		}
	}

	approval, err := verifier(ctx, candidateSHA)
	if err != nil {
		// Clean up remote candidate branch ONLY on definitive negative reviews
		if IsDefinitiveRejection(err) && driver != nil && remote != "" && prBranch != "" {
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
		if IsDefinitiveRejection(err) && driver != nil && remote != "" && prBranch != "" {
			_ = CleanupCandidateBranch(ctx, driver, remote, prBranch)
		}
		return nil, fmt.Errorf("forge approval validation failed: %w", err)
	}

	if auditLogger != nil {
		_ = auditLogger.Emit(audit.AuditEvent{
			EventType: audit.EventCodeConvergence,
			Status:    "APPROVAL_VERIFIED",
			Approver:  approval.ApproverUsername,
			Details: map[string]any{
				"storyId":    storyID,
				"commitHash": candidateSHA,
				"prBranch":   prBranch,
				"state":      "APPROVAL_VERIFIED",
			},
		})
	}

	return approval, nil
}

