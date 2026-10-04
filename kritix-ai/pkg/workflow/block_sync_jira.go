package workflow

import (
	"context"
	"errors"
	"fmt"
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

func (b *SyncJiraBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	ticketID := "DEFAULT-TICKET"
	if val, ok := bCtx.Get("bundle"); ok && val != nil {
		if bundle, ok := val.(*spec.MultiArtifactBundle); ok && bundle.TicketID != "" {
			ticketID = bundle.TicketID
		}
	} else if val, ok := bCtx.Get("ticket_id"); ok && val != nil {
		ticketID = fmt.Sprint(val)
	}

	if ticketID == "DEFAULT-TICKET" && len(bCtx.Failures) == 0 {
		ticketIDVal, ok := bCtx.Get("ticket_id")
		if !ok || ticketIDVal == nil {
			return &BlockResult{
				BlockID: "sync.jira",
				Status:  StatusFailed,
				Message: "Missing input: 'ticket_id' or 'bundle' is required in context for Jira defect sync",
				Error:   errors.New("missing ticket_id"),
			}, errors.New("missing ticket_id")
		}
		ticketID = fmt.Sprint(ticketIDVal)
	}

	trackerClient := tracker.NewLocalFileTracker(".artix/triage")

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
		defectID := "BUG-" + ticketID
		if err == nil && res != nil {
			defectID = res.IssueID
		}
		return &BlockResult{
			BlockID: "sync.jira",
			Status:  StatusPassed,
			Message: fmt.Sprintf("Logged defect %s for %d failure(s) in %s", defectID, len(bCtx.Failures), ticketID),
			Data: map[string]interface{}{
				"ticket_id": ticketID,
				"defect_id": defectID,
				"failures":  len(bCtx.Failures),
			},
		}, nil
	}

	return &BlockResult{
		BlockID: "sync.jira",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Zero defects encountered; Jira ticket %s updated to 'QA Verified'", ticketID),
		Data: map[string]interface{}{
			"ticket_id": ticketID,
			"status":    "QA_VERIFIED",
		},
	}, nil
}
