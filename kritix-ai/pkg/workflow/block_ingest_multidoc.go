package workflow

import (
	"context"
	"errors"
	"fmt"

	"kritix/pkg/spec"
)

// IngestMultiDocBlock implements real ingest.multi-doc processing and cross-document reconciliation.
type IngestMultiDocBlock struct{}

func (b *IngestMultiDocBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "ingest.multi-doc",
		Name:        "Ingest Feature Documents",
		Category:    "ingest",
		Description: "Ingests feature documentation bundle (Jira, FDD, Copy Matrix, Analytics Schema) and performs cross-artifact reconciliation.",
	}
}

func (b *IngestMultiDocBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	val, ok := bCtx.Get("bundle")
	var bundle *spec.MultiArtifactBundle

	if ok && val != nil {
		if b, ok := val.(*spec.MultiArtifactBundle); ok {
			bundle = b
		}
	}

	if bundle == nil {
		ticketID, _ := bCtx.Get("ticket_id")
		fdd, _ := bCtx.Get("fdd_text")
		if ticketID == nil && fdd == nil {
			return &BlockResult{
				BlockID: "ingest.multi-doc",
				Status:  StatusFailed,
				Message: "Missing required input: no 'bundle', 'ticket_id', or 'fdd_text' found in execution context",
				Error:   errors.New("missing specification bundle"),
			}, errors.New("missing specification bundle")
		}

		bundle = &spec.MultiArtifactBundle{
			TicketID:         fmt.Sprint(ticketID),
			FeatureDesignDoc: fmt.Sprint(fdd),
			CopyMatrix:       make(map[string]string),
			AnalyticsEvents:  make([]spec.AnalyticsEventDef, 0),
		}
	}

	report, err := bundle.ReconcileAndAudit(0.85)
	if err != nil {
		return &BlockResult{
			BlockID: "ingest.multi-doc",
			Status:  StatusFailed,
			Message: fmt.Sprintf("Spec reconciliation halted: %v", err),
			Error:   err,
		}, err
	}

	bCtx.Set("bundle", bundle)
	bCtx.Set("reconciliation_report", report)

	return &BlockResult{
		BlockID: "ingest.multi-doc",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Successfully ingested and reconciled specification for %s (Confidence: %.2f, %d copy keys, %d analytics events)",
			bundle.TicketID, report.CalculatedConfidence, len(bundle.CopyMatrix), len(bundle.AnalyticsEvents)),
		Data: map[string]interface{}{
			"ticket_id":  bundle.TicketID,
			"confidence": report.CalculatedConfidence,
			"copy_keys":  len(bundle.CopyMatrix),
			"analytics":  len(bundle.AnalyticsEvents),
		},
	}, nil
}
