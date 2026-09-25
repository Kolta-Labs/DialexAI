package model

// TensionStatus represents the dialectic lifecycle stage of an identified tension pair.
type TensionStatus string

const (
	TensionStatusOpen             TensionStatus = "OPEN"
	TensionStatusExplored         TensionStatus = "EXPLORED"
	TensionStatusResolved         TensionStatus = "RESOLVED"
	TensionStatusAcceptedTradeOff TensionStatus = "ACCEPTED_TRADE_OFF"
)

// AgentAssertion records a single model's stance, quote, and round context.
type AgentAssertion struct {
	SeatID            string   `json:"seatId"`
	Provider          Provider `json:"provider"`
	AuthorDisplayName string   `json:"authorDisplayName"`
	Statement         string   `json:"statement"`
	Quote             string   `json:"quote,omitempty"`
	Round             int      `json:"round"`
}

// TensionPair represents an isolated contradiction between two opposing agent assertions,
// grounded in paraconsistent logic (C_n systems) where contradictions are informative assets.
type TensionPair struct {
	ID                 string         `json:"id"`
	Thesis             AgentAssertion `json:"thesis"`
	Antithesis         AgentAssertion `json:"antithesis"`
	UnderlyingConflict string         `json:"underlyingConflict"`
	Synthesis          *string        `json:"synthesis,omitempty"`
	TradeOffRationale  *string        `json:"tradeOffRationale,omitempty"`
	Status             TensionStatus  `json:"status"`
	Severity           float64        `json:"severity"` // 0.0 (minor nuance) to 1.0 (fundamental deadlock)
	DetectedInRound    int            `json:"detectedInRound"`
	ResolvedInRound    *int           `json:"resolvedInRound,omitempty"`
}

// HasOpenTensions returns true if any tension in the list is still OPEN or EXPLORED.
func HasOpenTensions(tensions []TensionPair) bool {
	for _, t := range tensions {
		if t.Status == TensionStatusOpen || t.Status == TensionStatusExplored {
			return true
		}
	}
	return false
}

// OpenTensionCount returns the number of tensions that are not yet RESOLVED or ACCEPTED_TRADE_OFF.
func OpenTensionCount(tensions []TensionPair) int {
	count := 0
	for _, t := range tensions {
		if t.Status == TensionStatusOpen || t.Status == TensionStatusExplored {
			count++
		}
	}
	return count
}
