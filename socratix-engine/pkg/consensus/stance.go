package consensus

import (
	"regexp"
	"strings"

	"socratix/pkg/model"
)

// Stance represents an agent's agreement position on a single message.
type Stance int

const (
	StanceDisagree Stance = iota
	StanceAgreed
	StanceConceded
)

// Result is the outcome of evaluating consensus across all agents.
type Result struct {
	Achieved    bool     `json:"achieved"`
	Ongoing     bool     `json:"ongoing"`  // if not Achieved, is it still going (agents haven't all spoken yet)?
	NotReady    string   `json:"notReady"` // if neither Achieved nor Ongoing, why not (disabled, minRounds, etc)?
	AgreedSeats []string `json:"agreedSeats"`
	AgreedCount int      `json:"agreedCount"`
	TotalCount  int      `json:"totalCount"`
	Ratio       float64  `json:"ratio"`
}

var (
	consensusPrefixRegex = regexp.MustCompile(`(?mi)^(?:>\s*)*(?:#{1,6}\s*)?(?:\[\s*)?(?:\*{1,2}|_{1,2})?\s*(?:AGREED|CONCUR|CONSENSUS REACHED|UNANIMOUS AGREEMENT|I AGREE)\b\s*(?:\])?\s*[:—\-]?(?:\*{1,2}|_{1,2})?`)

	negationRegex = regexp.MustCompile(`(?i)(?:not\s+agree|do\s+not\s+agree|don't\s+agree|cannot\s+agree|can't\s+agree|disagree|disagrees|not\s+concede|do\s+not\s+concede|cannot\s+concede|refuse\s+to\s+concede|still\s+contest)\b`)

	concessionPatterns = []string{
		"i concede to",
		"concede to the position",
		"concede to the council",
		"nothing left to contest",
		"nothing left to audit",
		"i align with the council",
		"align with the other participants",
		"align with the panel",
		"no further substantive disagreement",
		"no further disagreement",
		"no remaining objections",
		"nothing new to add and agree",
		"fully concur with",
		"i fully concur",
		"i fully concede",
	}
)

// IsTurnAgreed checks if a message content indicates agreement or concession.
// It matches the prefix regex without following negation, or concession phrases.
func IsTurnAgreed(content string) bool {
	trimmed := strings.TrimSpace(content)
	if trimmed == "" {
		return false
	}

	// Check prefix match
	if matchesPrefix(trimmed) {
		return true
	}

	// Check heuristic concession patterns
	if matchesHeuristics(trimmed) {
		return true
	}

	return false
}

// matchesPrefix checks if the content starts with a consensus marker (AGREED, CONCUR, etc),
// ignoring markdown and negation that follows the marker on the same line.
func matchesPrefix(content string) bool {
	lines := strings.Split(content, "\n")
	if len(lines) == 0 {
		return false
	}

	firstLine := lines[0]
	if trimmed := strings.TrimSpace(firstLine); trimmed == "" {
		return false
	}

	match := consensusPrefixRegex.FindStringIndex(firstLine)
	if match == nil {
		return false
	}

	// Check if negation appears after the match on the same line
	remainder := firstLine[match[1]:]
	if negationRegex.MatchString(remainder) {
		return false
	}

	return true
}

// matchesHeuristics checks if the content contains concession language patterns
// and is not negated.
func matchesHeuristics(content string) bool {
	trimmed := strings.TrimSpace(content)
	if trimmed == "" {
		return false
	}

	// Sample opening and closing to avoid scanning huge content
	sample := buildSample(trimmed)
	lower := strings.ToLower(sample)

	// Fast reject if strong negation present
	if negationRegex.MatchString(lower) {
		return false
	}

	// Check for concession patterns
	for _, pattern := range concessionPatterns {
		if strings.Contains(lower, pattern) {
			return true
		}
	}

	return false
}

// buildSample returns first 250 + last 250 chars of content for heuristic analysis.
func buildSample(content string) string {
	clean := strings.TrimSpace(content)
	if len(clean) <= 250 {
		return clean
	}

	first := clean[:250]
	last := clean[len(clean)-250:]
	return first + " " + last
}

// Evaluate determines if consensus has been achieved given current round and transcript.
// Returns Result with Achieved=true, or Ongoing=true if not all agents have spoken,
// or NotReady with a reason.
func Evaluate(currentRound int, transcript []model.DebateMessage, cfg model.DebateConfig) Result {
	result := Result{}

	// Get consensus config with sensible defaults
	mode := model.ConsensusModeUnanimous
	minRounds := 2
	strategy := model.ConsensusStrategyPrefixAndPattern
	threshold := 1.0

	if cfg.Consensus != nil {
		if cfg.Consensus.Mode != "" {
			mode = cfg.Consensus.Mode
		}
		if cfg.Consensus.MinRoundsBeforeExit > 0 {
			minRounds = cfg.Consensus.MinRoundsBeforeExit
		}
		if cfg.Consensus.ConsensusThreshold > 0 {
			threshold = cfg.Consensus.ConsensusThreshold
		}
		if cfg.Consensus.Strategy != "" {
			strategy = cfg.Consensus.Strategy
		}
	} else if cfg.ConsensusTolerance > 0 {
		threshold = cfg.ConsensusTolerance
		if threshold < 1.0 {
			mode = model.ConsensusModeSupermajority
		}
	}

	if mode == model.ConsensusModeDisabled {
		result.NotReady = "Consensus detection disabled."
		return result
	}

	if currentRound < minRounds {
		result.NotReady = "Minimum rounds not reached."
		return result
	}

	agents := cfg.Agents()
	result.TotalCount = len(agents)
	if len(agents) == 0 {
		result.NotReady = "No active agents."
		return result
	}

	// Find the latest user comment index
	lastUserCommentIdx := -1
	for i := len(transcript) - 1; i >= 0; i-- {
		if transcript[i].IsUserComment {
			lastUserCommentIdx = i
			break
		}
	}

	// Find latest turn for each agent after last user comment
	latestTurnsPerSeat := make(map[string]*model.DebateMessage)
	for _, agent := range agents {
		for i := len(transcript) - 1; i > lastUserCommentIdx; i-- {
			msg := transcript[i]
			if (msg.SeatID == agent.ID || (msg.SeatID == "" && msg.AgentID == agent.Provider)) &&
				!msg.IsError && !msg.IsUserComment && !msg.IsSystem {
				latestTurnsPerSeat[agent.ID] = &msg
				break
			}
		}
	}

	// If any agent hasn't spoken yet, debate is ongoing
	for _, agent := range agents {
		if latestTurnsPerSeat[agent.ID] == nil {
			result.Ongoing = true
			for _, t := range latestTurnsPerSeat {
				if t != nil && isTurnInConsensus(*t, strategy) {
					result.AgreedCount++
				}
			}
			return result
		}
	}

	// All agents have spoken; evaluate consensus
	for _, agent := range agents {
		turn := latestTurnsPerSeat[agent.ID]
		if turn != nil && isTurnInConsensus(*turn, strategy) {
			result.AgreedSeats = append(result.AgreedSeats, agent.ID)
			result.AgreedCount++
		}
	}

	result.Ratio = float64(result.AgreedCount) / float64(result.TotalCount)

	// Determine if consensus is achieved based on mode and threshold
	consensusReached := false

	// Mode fallback: UNANIMOUS with tolerance in [0.5, 0.999] becomes SUPERMAJORITY
	actualMode := mode
	if mode == model.ConsensusModeUnanimous && cfg.ConsensusTolerance > 0.5 && cfg.ConsensusTolerance < 0.999 {
		actualMode = model.ConsensusModeSupermajority
	}

	switch actualMode {
	case model.ConsensusModeUnanimous:
		consensusReached = result.AgreedCount == result.TotalCount
	case model.ConsensusModeSupermajority:
		targetThreshold := threshold
		if targetThreshold < 0.5 || targetThreshold > 0.999 {
			targetThreshold = 0.66
		}
		consensusReached = result.Ratio >= targetThreshold
	case model.ConsensusModeSimpleMajority:
		consensusReached = result.Ratio > 0.50
	}

	result.Achieved = consensusReached
	result.Ongoing = !consensusReached

	return result
}

// isTurnInConsensus checks if a single turn indicates agreement based on strategy.
func isTurnInConsensus(msg model.DebateMessage, strategy model.ConsensusStrategy) bool {
	if strategy == model.ConsensusStrategyPrefixAndPattern {
		return matchesPrefix(msg.Content)
	}

	// HEURISTIC_HYBRID or MODEL_CLASSIFIED fallback
	if matchesPrefix(msg.Content) {
		return true
	}

	if matchesHeuristics(msg.Content) {
		return true
	}

	return false
}
