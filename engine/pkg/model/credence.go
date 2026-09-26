package model

import "time"

// Hypothesis represents a discrete competing strategy or thesis under deliberation.
type Hypothesis struct {
	ID          string `json:"id"`
	Index       int    `json:"index"`       // 1, 2, 3...
	Label       string `json:"label"`       // Short title (e.g. "Kafka + CDC")
	Description string `json:"description"` // Full thesis description
	ColorHex    string `json:"colorHex"`    // Deterministic palette color (e.g. "#10B981")
}

// PersonaCredence captures an individual model's subjective probability assignment.
type PersonaCredence struct {
	PersonaID          string             `json:"personaId"`
	PersonaName        string             `json:"personaName"`
	HypothesisCredence map[string]float64 `json:"hypothesisCredence"` // HypothesisID -> [0.0, 1.0]
	CertaintyScore     float64            `json:"certaintyScore"`     // Meta-confidence [0.0, 1.0]
	CoreRationale      string             `json:"coreRationale"`      // 1-2 sentence epistemic defense
}

// TippingPoint marks a high-leverage piece of evidence that caused a major belief shift.
type TippingPoint struct {
	RoundIndex         int     `json:"roundIndex"`
	EvidenceSnippet    string  `json:"evidenceSnippet"`
	LikelihoodRatio    float64 `json:"likelihoodRatio"`
	AffectedHypothesis string  `json:"affectedHypothesis"`
	ShiftDelta         float64 `json:"shiftDelta"` // e.g. +0.34
}

// RoundCredenceSnapshot stores the aggregated epistemic state after a completed round.
type RoundCredenceSnapshot struct {
	RoundIndex         int                `json:"roundIndex"`
	Timestamp          time.Time          `json:"timestamp"`
	PersonaCredences   []PersonaCredence  `json:"personaCredences"`
	AggregatedCredence map[string]float64 `json:"aggregatedCredence"` // HypothesisID -> [0.0, 1.0]
	Entropy            float64            `json:"entropy"`            // Shannon entropy in bits
	DominantHypothesis string             `json:"dominantHypothesis"`
	TippingPoints      []TippingPoint     `json:"tippingPoints,omitempty"`
}

// CredenceLedger maintains the full historical trajectory of belief evolution.
type CredenceLedger struct {
	DiscussionID string                  `json:"discussionId"`
	Topic        string                  `json:"topic"`
	Hypotheses   []Hypothesis            `json:"hypotheses"`
	Snapshots    []RoundCredenceSnapshot `json:"snapshots"`
	FinalEntropy float64                 `json:"finalEntropy"`
	Status       string                  `json:"status"` // "CONVERGED" | "STALEMATE" | "IN_PROGRESS"
}
