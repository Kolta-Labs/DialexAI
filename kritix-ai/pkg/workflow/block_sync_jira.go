package workflow

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	"kritix/pkg/spec"
	"kritix/pkg/tracker"
	"kritix/pkg/triage"
)

// SyncJiraBlock syncs test verification results and defects back to Jira / Linear trackers.
type SyncJiraBlock struct{}

func (b *SyncJiraBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "sync.jira",
		Name:        "Jira / Linear Defect Sync",
		Category:    "sync",
		Description: "Syncs automated verification outcome and failure triage artifacts to issue tracker.",
	}
}

func (b *SyncJiraBlock) resolveTracker(bCtx *Context) (tracker.IssueTracker, bool) {
	if val, ok := bCtx.Get("tracker_client"); ok && val != nil {
		if t, ok := val.(tracker.IssueTracker); ok {
			return t, true
		}
	}

	// Check environment variables for real Jira / Linear credentials
	jiraToken := os.Getenv("JIRA_API_TOKEN")
	jiraURL := os.Getenv("JIRA_BASE_URL")
	jiraEmail := os.Getenv("JIRA_USER_EMAIL")
	jiraProject := os.Getenv("JIRA_PROJECT_KEY")

	if jiraToken != "" && jiraURL != "" && jiraEmail != "" {
		cfg := tracker.JiraConfig{
			BaseURL:    jiraURL,
			UserEmail:  jiraEmail,
			APIToken:   jiraToken,
			ProjectKey: jiraProject,
		}
		if cfg.ProjectKey == "" {
			cfg.ProjectKey = "QA"
		}
		return tracker.NewJiraTracker(cfg, nil), true
	}

	linearKey := os.Getenv("LINEAR_API_KEY")
	linearTeam := os.Getenv("LINEAR_TEAM_ID")
	if linearKey != "" && linearTeam != "" {
		return tracker.NewLinearTracker(tracker.LinearConfig{
			APIKey: linearKey,
			TeamID: linearTeam,
		}, nil), true
	}

	return nil, false
}

func (b *SyncJiraBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	ticketID := ""
	if val, ok := bCtx.Get("bundle"); ok && val != nil {
		if bundle, ok := val.(*spec.MultiArtifactBundle); ok && bundle.TicketID != "" {
			ticketID = bundle.TicketID
		}
	} else if val, ok := bCtx.Get("ticket_id"); ok && val != nil {
		ticketID = fmt.Sprint(val)
	}

	if ticketID == "" {
		return &BlockResult{
			BlockID: "sync.jira",
			Status:  StatusFailed,
			Message: "Missing input: 'ticket_id' or 'bundle' is required in context for Jira defect sync",
			Error:   errors.New("missing ticket_id"),
		}, errors.New("missing ticket_id")
	}

	trackerClient, hasRealTracker := b.resolveTracker(bCtx)
	if !hasRealTracker {
		// No real credentials configured -> Return StatusSimulated, NEVER StatusPassed!
		return &BlockResult{
			BlockID:   "sync.jira",
			Status:    StatusSimulated,
			Simulated: true,
			Message:   fmt.Sprintf("Simulated: No Jira/Linear credentials configured; skipped defect sync for %s", ticketID),
			Data: map[string]interface{}{
				"ticket_id": ticketID,
				"failures":  len(bCtx.Failures),
				"simulated": true,
			},
		}, nil
	}

	if len(bCtx.Failures) > 0 {
		report := triage.DefectReport{
			ID:               ticketID,
			Title:            fmt.Sprintf("[Kritix QA Defect] Failures detected in %s", ticketID),
			Severity:         triage.SeverityMajor,
			Category:         triage.CategoryCodeRegression,
			StepsToReproduce: bCtx.Failures,
			ActualBehavior:   fmt.Sprintf("Pipeline encountered %d failures", len(bCtx.Failures)),
			DiscoveredAt:     time.Now(),
		}
		res, err := trackerClient.CreateIssue(ctx, report)
		if err != nil {
			return &BlockResult{
				BlockID: "sync.jira",
				Status:  StatusFailed,
				Message: fmt.Sprintf("Failed to sync defect to tracker: %v", err),
				Error:   err,
			}, err
		}

		return &BlockResult{
			BlockID: "sync.jira",
			Status:  StatusPassed,
			Message: fmt.Sprintf("Logged defect %s for %d failure(s) in %s", res.IssueID, len(bCtx.Failures), ticketID),
			Data: map[string]interface{}{
				"ticket_id": ticketID,
				"defect_id": res.IssueID,
				"issue_url": res.IssueURL,
				"failures":  len(bCtx.Failures),
			},
		}, nil
	}

	return &BlockResult{
		BlockID: "sync.jira",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Zero defects encountered; Jira ticket %s verified clean", ticketID),
		Data: map[string]interface{}{
			"ticket_id": ticketID,
			"status":    "QA_VERIFIED",
		},
	}, nil
}
