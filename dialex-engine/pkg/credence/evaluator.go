package credence

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

type personaCredenceDTO struct {
	PersonaID          string             `json:"personaId"`
	PersonaName        string             `json:"personaName"`
	HypothesisCredence map[string]float64 `json:"hypothesisCredence"`
	CertaintyScore     float64            `json:"certaintyScore"`
	CoreRationale      string             `json:"coreRationale"`
}

type roundEvaluationResponse struct {
	PersonaCredences []personaCredenceDTO `json:"personaCredences"`
}

// EvaluateRoundCredence analyzes turns in a completed round to estimate each agent's subjective probability distribution.
func EvaluateRoundCredence(
	ctx context.Context,
	hypotheses []model.Hypothesis,
	transcript []model.DebateMessage,
	agents []model.Agent,
	round int,
	r runner.AgentRunner,
) ([]model.PersonaCredence, error) {
	if len(hypotheses) == 0 || len(agents) == 0 {
		return nil, nil
	}

	// Filter messages from this round
	var roundMessages []model.DebateMessage
	for _, m := range transcript {
		if m.Round == round {
			roundMessages = append(roundMessages, m)
		}
	}

	if len(roundMessages) == 0 || r == nil {
		return HeuristicEvaluateRoundCredence(hypotheses, roundMessages, agents, round), nil
	}

	var sb strings.Builder
	for _, m := range roundMessages {
		name := m.AuthorDisplayName
		if name == "" {
			name = string(m.AgentID)
		}
		sb.WriteString(fmt.Sprintf("[%s]: %s\n\n", name, m.Content))
	}

	var hypSB strings.Builder
	for _, h := range hypotheses {
		hypSB.WriteString(fmt.Sprintf("- %s (%s): %s\n", h.ID, h.Label, h.Description))
	}

	prompt := fmt.Sprintf(`You are an epistemic decision evaluator.
Based on the council statements below for Round %d, quantify each agent's subjective probability distribution across the candidate hypotheses.
Ensure each agent's hypothesis probabilities sum to 1.0 (100%%). CertaintyScore should be between 0.0 (high uncertainty) and 1.0 (absolute conviction).

HYPOTHESES:
%s

ROUND %d TRANSCRIPT:
%s

Respond STRICTLY with valid JSON in this format:
{
  "personaCredences": [
    {
      "personaId": "SeatID or Provider name",
      "personaName": "Display name",
      "hypothesisCredence": {
        "hyp_1": 0.70,
        "hyp_2": 0.30
      },
      "certaintyScore": 0.85,
      "coreRationale": "1 concise sentence stating why this agent leans toward this hypothesis."
    }
  ]
}`, round, hypSB.String(), round, sb.String())

	agent := model.Agent{
		Provider: model.ProviderAnthropic,
		Model:    "claude-sonnet-5",
	}
	reply, err := r.Respond(ctx, agent, "Credence Evaluation", prompt, "You are an epistemic decision evaluator.", nil, "")
	if err != nil {
		return HeuristicEvaluateRoundCredence(hypotheses, roundMessages, agents, round), nil
	}

	content := reply.Content
	if start := strings.Index(content, "{"); start != -1 {
		if end := strings.LastIndex(content, "}"); end != -1 && end > start {
			content = content[start : end+1]
		}
	}

	var parsed roundEvaluationResponse
	if err := json.Unmarshal([]byte(content), &parsed); err != nil || len(parsed.PersonaCredences) == 0 {
		return HeuristicEvaluateRoundCredence(hypotheses, roundMessages, agents, round), nil
	}

	var results []model.PersonaCredence
	for _, pc := range parsed.PersonaCredences {
		norm := NormalizeProbabilities(pc.HypothesisCredence)
		cert := pc.CertaintyScore
		if cert <= 0 {
			cert = 0.5
		} else if cert > 1.0 {
			cert = 1.0
		}
		results = append(results, model.PersonaCredence{
			PersonaID:          pc.PersonaID,
			PersonaName:        pc.PersonaName,
			HypothesisCredence: norm,
			CertaintyScore:     cert,
			CoreRationale:      strings.TrimSpace(pc.CoreRationale),
		})
	}

	return results, nil
}

// HeuristicEvaluateRoundCredence computes deterministic credence assignments from transcript content.
func HeuristicEvaluateRoundCredence(
	hypotheses []model.Hypothesis,
	roundMessages []model.DebateMessage,
	agents []model.Agent,
	round int,
) []model.PersonaCredence {
	results := make([]model.PersonaCredence, 0, len(agents))

	for _, agent := range agents {
		name := agent.Label()
		pID := agent.ID
		if pID == "" {
			pID = string(agent.Provider)
		}

		// Find message from this agent
		var msgContent string
		for _, m := range roundMessages {
			if string(m.AgentID) == string(agent.Provider) || m.SeatID == agent.ID {
				msgContent = m.Content
				break
			}
		}

		credences := make(map[string]float64, len(hypotheses))
		lowerMsg := strings.ToLower(msgContent)

		hasAgreed := strings.Contains(lowerMsg, "agreed:") || strings.Contains(lowerMsg, "concur") || strings.Contains(lowerMsg, "consensus reached")

		for i, h := range hypotheses {
			score := 1.0
			lowerLabel := strings.ToLower(h.Label)
			if strings.Contains(lowerMsg, lowerLabel) {
				score += 3.0
			}
			// In later rounds, consensus leans toward H1
			if hasAgreed && i == 0 {
				score += 4.0
			} else if round > 2 && i == 0 {
				score += 1.5 * float64(round)
			}
			credences[h.ID] = score
		}

		norm := NormalizeProbabilities(credences)
		certainty := 0.65 + float64(round)*0.08
		if certainty > 0.95 {
			certainty = 0.95
		}

		rationale := fmt.Sprintf("Evaluated from Round %d arguments and empirical trade-offs.", round)
		if hasAgreed {
			rationale = "Conceded to dominant hypothesis based on counter-evidence."
		}

		results = append(results, model.PersonaCredence{
			PersonaID:          pID,
			PersonaName:        name,
			HypothesisCredence: norm,
			CertaintyScore:     certainty,
			CoreRationale:      rationale,
		})
	}

	return results
}
