package model

// EvidenceSourceType denotes the origin system of an authoritative evidence snippet.
type EvidenceSourceType string

const (
	EvidenceSourceKnowledgeGraph EvidenceSourceType = "KNOWLEDGE_GRAPH"
	EvidenceSourceAttachedFile   EvidenceSourceType = "ATTACHED_FILE"
	EvidenceSourceWorkspaceDoc   EvidenceSourceType = "WORKSPACE_DOC"
)

// EvidenceItem represents an individual factual citation or grounded passage retrieved for a deliberation round.
type EvidenceItem struct {
	ID               string             `json:"id"`
	Round            int                `json:"round"`
	Query            string             `json:"query"`
	SourceType       EvidenceSourceType `json:"sourceType"`
	SourceID         string             `json:"sourceId"`
	SourceTitle      string             `json:"sourceTitle"`
	Snippet          string             `json:"snippet"`
	Score            float64            `json:"score"`
	AttributionBadge string             `json:"attributionBadge"`
	TimestampMs      int64              `json:"timestampMs"`
}

// RoundEvidence packages all retrieved evidence items and the synthesized grounding context for a specific round.
type RoundEvidence struct {
	Round          int            `json:"round"`
	TriggerQueries []string       `json:"triggerQueries"`
	Items          []EvidenceItem `json:"items"`
	SummaryContext string         `json:"summaryContext"`
}
