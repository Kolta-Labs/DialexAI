package socratic

import (
	"dialex/pkg/model"
)

// SocraticTurnRequest represents user input during an active Socratic session.
type SocraticTurnRequest struct {
	Message       string               `json:"message"`
	Topic         string               `json:"topic"`
	Stance        model.SocraticStance `json:"stance"`
	Stage         model.SocraticStage  `json:"stage"`
	Context       string               `json:"context,omitempty"`
	AttachedFiles []model.AttachedFile `json:"attachedFiles,omitempty"`
}

// SocraticTurnResponse represents the persona's response and epistemic state updates.
type SocraticTurnResponse struct {
	ProbeQuestion        string                     `json:"probeQuestion"`
	NewStage             model.SocraticStage        `json:"newStage"`
	LedgerUpdates        []model.SocraticLedgerItem `json:"ledgerUpdates"`
	DiscoveredBlindSpots []string                   `json:"discoveredBlindSpots"`
	DefendedInvariants   []string                   `json:"defendedInvariants"`
}

// SocraticDigestRequest is the payload to generate the final Socratic Digest.
type SocraticDigestRequest struct {
	Topic        string                     `json:"topic"`
	Transcript   []model.DebateMessage      `json:"transcript"`
	Ledger       []model.SocraticLedgerItem `json:"ledger"`
	ProjectID    string                     `json:"projectId"`
	DiscussionID string                     `json:"discussionId"`
}

// SocraticElevateRequest is the payload to clone interview insights into a Council debate.
type SocraticElevateRequest struct {
	ProjectID          string               `json:"projectId"`
	Digest             model.SocraticDigest `json:"digest"`
	ParentDiscussionID string               `json:"parentDiscussionId"`
}

// ElevateResult returns the newly spawned Council discussion ID.
type ElevateResult struct {
	NewDiscussionID string `json:"newDiscussionId"`
	ProjectID       string `json:"projectId"`
	Topic           string `json:"topic"`
}
