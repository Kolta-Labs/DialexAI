package consensus

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

var jsonCodeBlockRegex = regexp.MustCompile("(?s)```(?:json)?\\s*(\\{.*?\\})\\s*```")

// TensionDetector isolates and tracks dialectic contradiction pairs across rounds.
type TensionDetector struct{}

// NewTensionDetector constructs a ready-to-use TensionDetector.
func NewTensionDetector() *TensionDetector {
	return &TensionDetector{}
}

type tensionJSONResponse struct {
	NewTensions []struct {
		Thesis struct {
			SeatID            string `json:"seatId"`
			Provider          string `json:"provider"`
			AuthorDisplayName string `json:"authorDisplayName"`
			Statement         string `json:"statement"`
			Quote             string `json:"quote"`
			Round             int    `json:"round"`
		} `json:"thesis"`
		Antithesis struct {
			SeatID            string `json:"seatId"`
			Provider          string `json:"provider"`
			AuthorDisplayName string `json:"authorDisplayName"`
			Statement         string `json:"statement"`
			Quote             string `json:"quote"`
			Round             int    `json:"round"`
		} `json:"antithesis"`
		UnderlyingConflict string  `json:"underlyingConflict"`
		Severity           float64 `json:"severity"`
	} `json:"newTensions"`
	ResolvedTensionUpdates []struct {
		ID                string  `json:"id"`
		Status            string  `json:"status"` // "RESOLVED", "ACCEPTED_TRADE_OFF", "EXPLORED"
		Synthesis         *string `json:"synthesis,omitempty"`
		TradeOffRationale *string `json:"tradeOffRationale,omitempty"`
	} `json:"resolvedTensionUpdates"`
}

// AnalyzeRound performs an end-of-round dialectic pass. It identifies new contradictions
// between agents and checks if existing tensions were synthesized or acknowledged as trade-offs.
func (d *TensionDetector) AnalyzeRound(
	ctx context.Context,
	r runner.AgentRunner,
	analysisAgent model.Agent,
	topic string,
	currentRound int,
	roundMessages []model.DebateMessage,
	existingTensions []model.TensionPair,
	compactionModel string,
) ([]model.TensionPair, error) {
	// If fewer than 2 agent messages in the round, cannot form a cross-agent contradiction pair
	agentTurns := filterAgentTurns(roundMessages)
	if len(agentTurns) < 2 {
		return existingTensions, nil
	}

	if r == nil {
		return d.HeuristicAnalyzeRound(topic, currentRound, roundMessages, existingTensions), nil
	}

	prompt := buildAnalysisPrompt(topic, currentRound, agentTurns, existingTensions)
	analysisAgent.SystemPrompt = "You are a Paraconsistent Dialectic Analyzer. Isolate exact technical contradiction pairs (thesis vs antithesis) between agents and evaluate synthesis/trade-off status."

	resp, err := r.Respond(ctx, analysisAgent, prompt, "", "", nil, compactionModel)
	if err != nil {
		// Fallback to offline heuristic
		return d.HeuristicAnalyzeRound(topic, currentRound, roundMessages, existingTensions), nil
	}

	parsed, err := parseTensionResponse(resp.Content)
	if err != nil {
		return d.HeuristicAnalyzeRound(topic, currentRound, roundMessages, existingTensions), nil
	}

	return mergeTensionResults(parsed, currentRound, existingTensions), nil
}

func filterAgentTurns(messages []model.DebateMessage) []model.DebateMessage {
	var result []model.DebateMessage
	for _, m := range messages {
		if !m.IsError && !m.IsSystem && !m.IsUserComment && strings.TrimSpace(m.Content) != "" {
			result = append(result, m)
		}
	}
	return result
}

func buildAnalysisPrompt(topic string, currentRound int, turns []model.DebateMessage, existing []model.TensionPair) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("TOPIC: %s\nCURRENT ROUND: %d\n\n", topic, currentRound))

	if len(existing) > 0 {
		sb.WriteString("EXISTING ACTIVE TENSIONS:\n")
		for _, t := range existing {
			sb.WriteString(fmt.Sprintf("- ID: %s | Conflict: %s | Status: %s\n  Thesis (%s): %s\n  Antithesis (%s): %s\n",
				t.ID, t.UnderlyingConflict, t.Status, t.Thesis.AuthorDisplayName, t.Thesis.Statement, t.Antithesis.AuthorDisplayName, t.Antithesis.Statement))
		}
		sb.WriteString("\n")
	} else {
		sb.WriteString("EXISTING ACTIVE TENSIONS: None yet.\n\n")
	}

	sb.WriteString("RECENT ROUND TRANSCRIPT:\n")
	for _, m := range turns {
		author := m.AuthorDisplayName
		if author == "" {
			author = m.AgentID.BrandName()
		}
		sb.WriteString(fmt.Sprintf("--- [%s (Round %d)] ---\n%s\n\n", author, m.Round, m.Content))
	}

	sb.WriteString(`TASK:
1. Identify any NEW direct contradictions or opposing requirements asserted between different agents in this round.
2. Check existing tensions: Did any agent formulate a viable SYNTHESIS (reconciling both requirements) or agree to an ACCEPTED_TRADE_OFF?

CRITICAL: Output ONLY a valid JSON object matching this schema, with no markdown or explanation:
{
  "newTensions": [
    {
      "thesis": {
        "seatId": "...",
        "provider": "ANTHROPIC",
        "authorDisplayName": "...",
        "statement": "Concise 1-sentence thesis assertion",
        "quote": "Short exact quote from turn",
        "round": ` + fmt.Sprintf("%d", currentRound) + `
      },
      "antithesis": {
        "seatId": "...",
        "provider": "OPENAI",
        "authorDisplayName": "...",
        "statement": "Concise 1-sentence opposing antithesis assertion",
        "quote": "Short exact quote from turn",
        "round": ` + fmt.Sprintf("%d", currentRound) + `
      },
      "underlyingConflict": "Short 3-6 word label of the conflict axis (e.g. ACID Quorum vs. Write Latency SLA)",
      "severity": 0.8
    }
  ],
  "resolvedTensionUpdates": [
    {
      "id": "existing-tension-id",
      "status": "RESOLVED",
      "synthesis": "Brief explanation of how the contradiction was synthesized"
    }
  ]
}`)

	return sb.String()
}

func parseTensionResponse(content string) (*tensionJSONResponse, error) {
	trimmed := strings.TrimSpace(content)

	var jsonStr string
	if match := jsonCodeBlockRegex.FindStringSubmatch(trimmed); len(match) > 1 {
		jsonStr = strings.TrimSpace(match[1])
	} else if start := strings.Index(trimmed, "{"); start != -1 {
		if end := strings.LastIndex(trimmed, "}"); end > start {
			jsonStr = trimmed[start : end+1]
		}
	}

	if jsonStr == "" {
		jsonStr = trimmed
	}

	var res tensionJSONResponse
	if err := json.Unmarshal([]byte(jsonStr), &res); err != nil {
		return nil, err
	}
	return &res, nil
}

func mergeTensionResults(resp *tensionJSONResponse, currentRound int, existing []model.TensionPair) []model.TensionPair {
	tensionsMap := make(map[string]model.TensionPair)
	for _, t := range existing {
		tensionsMap[t.ID] = t
	}

	// Apply status updates
	for _, u := range resp.ResolvedTensionUpdates {
		if cur, ok := tensionsMap[u.ID]; ok {
			status := model.TensionStatus(strings.ToUpper(strings.TrimSpace(u.Status)))
			switch status {
			case model.TensionStatusResolved, model.TensionStatusAcceptedTradeOff, model.TensionStatusExplored:
				cur.Status = status
			default:
				cur.Status = model.TensionStatusResolved
			}
			if u.Synthesis != nil && *u.Synthesis != "" {
				cur.Synthesis = u.Synthesis
			}
			if u.TradeOffRationale != nil && *u.TradeOffRationale != "" {
				cur.TradeOffRationale = u.TradeOffRationale
			}
			if cur.Status == model.TensionStatusResolved || cur.Status == model.TensionStatusAcceptedTradeOff {
				rnd := currentRound
				cur.ResolvedInRound = &rnd
			}
			tensionsMap[u.ID] = cur
		}
	}

	// Add new tensions
	for _, nt := range resp.NewTensions {
		if strings.TrimSpace(nt.UnderlyingConflict) == "" || strings.TrimSpace(nt.Thesis.Statement) == "" {
			continue
		}

		// Avoid duplicate underlying conflict
		duplicate := false
		for _, existingT := range tensionsMap {
			if strings.EqualFold(existingT.UnderlyingConflict, nt.UnderlyingConflict) {
				duplicate = true
				break
			}
		}
		if duplicate {
			continue
		}

		sev := nt.Severity
		if sev <= 0.0 || sev > 1.0 {
			sev = 0.75
		}

		id := generateTensionID()
		thesisProvider := model.Provider(strings.ToUpper(strings.TrimSpace(nt.Thesis.Provider)))
		antiProvider := model.Provider(strings.ToUpper(strings.TrimSpace(nt.Antithesis.Provider)))

		tensionsMap[id] = model.TensionPair{
			ID: id,
			Thesis: model.AgentAssertion{
				SeatID:            nt.Thesis.SeatID,
				Provider:          thesisProvider,
				AuthorDisplayName: nt.Thesis.AuthorDisplayName,
				Statement:         strings.TrimSpace(nt.Thesis.Statement),
				Quote:             strings.TrimSpace(nt.Thesis.Quote),
				Round:             currentRound,
			},
			Antithesis: model.AgentAssertion{
				SeatID:            nt.Antithesis.SeatID,
				Provider:          antiProvider,
				AuthorDisplayName: nt.Antithesis.AuthorDisplayName,
				Statement:         strings.TrimSpace(nt.Antithesis.Statement),
				Quote:             strings.TrimSpace(nt.Antithesis.Quote),
				Round:             currentRound,
			},
			UnderlyingConflict: strings.TrimSpace(nt.UnderlyingConflict),
			Status:             model.TensionStatusOpen,
			Severity:           sev,
			DetectedInRound:    currentRound,
		}
	}

	result := make([]model.TensionPair, 0, len(tensionsMap))
	// Keep existing order first
	for _, orig := range existing {
		if updated, ok := tensionsMap[orig.ID]; ok {
			result = append(result, updated)
			delete(tensionsMap, orig.ID)
		}
	}
	// Append newly added ones
	for _, newlyAdded := range tensionsMap {
		result = append(result, newlyAdded)
	}

	return result
}

// HeuristicAnalyzeRound performs a pure-Go, deterministic contradiction and synthesis analysis.
// Used when running offline, under test conditions, or when LLM parsing fails.
func (d *TensionDetector) HeuristicAnalyzeRound(
	topic string,
	currentRound int,
	roundMessages []model.DebateMessage,
	existingTensions []model.TensionPair,
) []model.TensionPair {
	agentTurns := filterAgentTurns(roundMessages)
	if len(agentTurns) < 2 {
		return existingTensions
	}

	tensionsMap := make(map[string]model.TensionPair)
	for _, t := range existingTensions {
		tensionsMap[t.ID] = t
	}

	// 1. Check for synthesis or trade-off acceptance in current turns for open tensions
	synthesisKeywords := []string{"synthesiz", "hybrid", "agree with", "compromise", "resolve", "middle ground", "both approaches", "reconcil"}
	tradeOffKeywords := []string{"trade-off", "tradeoff", "accept the risk", "acceptable cost", "sacrifice"}

	for id, tension := range tensionsMap {
		if tension.Status == model.TensionStatusOpen || tension.Status == model.TensionStatusExplored {
			for _, turn := range agentTurns {
				lower := strings.ToLower(turn.Content)
				hasConflictRef := false
				conflictLower := strings.ToLower(tension.UnderlyingConflict)
				words := strings.Fields(strings.ReplaceAll(conflictLower, "vs.", " "))
				for _, w := range words {
					wTrim := strings.Trim(w, ",.- ")
					if len(wTrim) > 4 && strings.Contains(lower, wTrim) {
						hasConflictRef = true
						break
					}
				}
				if !hasConflictRef && len(tensionsMap) <= 2 {
					hasConflictRef = true
				}

				if hasConflictRef {
					for _, sk := range synthesisKeywords {
						if strings.Contains(lower, sk) {
							synthNote := fmt.Sprintf("Synthesized in Round %d by %s: converging on hybrid architecture.", currentRound, turn.AuthorDisplayName)
							rnd := currentRound
							tension.Status = model.TensionStatusResolved
							tension.Synthesis = &synthNote
							tension.ResolvedInRound = &rnd
							tensionsMap[id] = tension
							break
						}
					}
					if tension.Status != model.TensionStatusResolved {
						for _, tk := range tradeOffKeywords {
							if strings.Contains(lower, tk) {
								toNote := fmt.Sprintf("Accepted trade-off in Round %d: documented by %s.", currentRound, turn.AuthorDisplayName)
								rnd := currentRound
								tension.Status = model.TensionStatusAcceptedTradeOff
								tension.TradeOffRationale = &toNote
								tension.ResolvedInRound = &rnd
								tensionsMap[id] = tension
								break
							}
						}
					}
				}
			}
		}
	}

	// 2. Identify new contradiction if fewer than 4 total tensions exist
	if len(tensionsMap) < 4 {
		// Look for two turns from different agents with adversarial or contrasting stances
		adversarialMarkers := []string{"however", "disagree", "risk", "unacceptable", "cannot support", "infeasible", "prefer", "instead", "contrary"}
		var turnA, turnB *model.DebateMessage

		for i := 0; i < len(agentTurns); i++ {
			if len(strings.TrimSpace(agentTurns[i].Content)) < 30 {
				continue
			}
			lowerA := strings.ToLower(agentTurns[i].Content)
			for j := i + 1; j < len(agentTurns); j++ {
				if len(strings.TrimSpace(agentTurns[j].Content)) < 30 {
					continue
				}
				if agentTurns[i].AgentID != agentTurns[j].AgentID {
					lowerB := strings.ToLower(agentTurns[j].Content)
					hasAdv := false
					for _, m := range adversarialMarkers {
						if strings.Contains(lowerA, m) || strings.Contains(lowerB, m) {
							hasAdv = true
							break
						}
					}
					if hasAdv {
						turnA = &agentTurns[i]
						turnB = &agentTurns[j]
						break
					}
				}
			}
			if turnA != nil {
				break
			}
		}

		if turnA != nil && turnB != nil {
			conflictLabel := extractHeuristicConflict(topic, turnA.Content, turnB.Content, len(tensionsMap)+1)
			// Avoid duplicate conflict label
			dup := false
			for _, ex := range tensionsMap {
				if strings.EqualFold(ex.UnderlyingConflict, conflictLabel) {
					dup = true
					break
				}
			}
			if !dup {
				id := generateTensionID()
				tensionsMap[id] = model.TensionPair{
					ID: id,
					Thesis: model.AgentAssertion{
						SeatID:            turnA.SeatID,
						Provider:          turnA.AgentID,
						AuthorDisplayName: turnA.AuthorDisplayName,
						Statement:         truncateStatement(turnA.Content),
						Round:             currentRound,
					},
					Antithesis: model.AgentAssertion{
						SeatID:            turnB.SeatID,
						Provider:          turnB.AgentID,
						AuthorDisplayName: turnB.AuthorDisplayName,
						Statement:         truncateStatement(turnB.Content),
						Round:             currentRound,
					},
					UnderlyingConflict: conflictLabel,
					Status:             model.TensionStatusOpen,
					Severity:           0.75,
					DetectedInRound:    currentRound,
				}
			}
		}
	}

	result := make([]model.TensionPair, 0, len(tensionsMap))
	for _, orig := range existingTensions {
		if updated, ok := tensionsMap[orig.ID]; ok {
			result = append(result, updated)
			delete(tensionsMap, orig.ID)
		}
	}
	for _, newlyAdded := range tensionsMap {
		result = append(result, newlyAdded)
	}

	return result
}

func extractHeuristicConflict(topic, contentA, contentB string, index int) string {
	combined := strings.ToLower(topic + " " + contentA + " " + contentB)
	if strings.Contains(combined, "latency") || strings.Contains(combined, "throughput") || strings.Contains(combined, "performance") {
		return "Low-Latency Performance vs. Architectural Durability"
	}
	if strings.Contains(combined, "security") || strings.Contains(combined, "auth") || strings.Contains(combined, "risk") {
		return "Zero-Trust Security vs. Developer Operational Velocity"
	}
	if strings.Contains(combined, "consistency") || strings.Contains(combined, "acid") || strings.Contains(combined, "database") {
		return "Immediate ACID Consistency vs. High-Availability Partitioning"
	}
	if strings.Contains(combined, "cost") || strings.Contains(combined, "budget") || strings.Contains(combined, "scale") {
		return "Infrastructure Cost vs. Peak Elastic Scalability"
	}
	return fmt.Sprintf("Dialectic Trade-off Axis #%d", index)
}

func truncateStatement(content string) string {
	lines := strings.Split(content, "\n")
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		trimmed = strings.TrimLeft(trimmed, "#*- \t")
		if len(trimmed) > 30 && !strings.HasPrefix(trimmed, ">") {
			if len(trimmed) > 140 {
				return trimmed[:137] + "..."
			}
			return trimmed
		}
	}
	if len(content) > 140 {
		return content[:137] + "..."
	}
	return content
}

func generateTensionID() string {
	b := make([]byte, 4)
	_, _ = rand.Read(b)
	return fmt.Sprintf("tension_%s", hex.EncodeToString(b))
}
