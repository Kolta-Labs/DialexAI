package credence

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"socratix/pkg/model"
	"socratix/pkg/runner"
)

var defaultHypothesisColors = []string{
	"#10B981", // Emerald
	"#6366F1", // Indigo
	"#F43F5E", // Coral
	"#F59E0B", // Amber
}

type extractedHypothesisDTO struct {
	Label       string `json:"label"`
	Description string `json:"description"`
}

type hypothesisExtractionResponse struct {
	Hypotheses []extractedHypothesisDTO `json:"hypotheses"`
}

// ExtractHypotheses parses an architectural dilemma topic and context into 2 to 4 discrete competing hypotheses.
func ExtractHypotheses(ctx context.Context, topic, contextStr string, r runner.AgentRunner) ([]model.Hypothesis, error) {
	if r == nil {
		return HeuristicExtractHypotheses(topic, contextStr), nil
	}

	prompt := fmt.Sprintf(`You are an expert systems architect and decision theorist.
Decompose the following architectural or technical decision dilemma into 2 to 4 mutually exclusive, exhaustive candidate hypotheses (competing technical strategies).

TOPIC: %s
CONTEXT: %s

Respond STRICTLY with valid JSON in this format:
{
  "hypotheses": [
    {
      "label": "Short Title (e.g. Distributed Event-Driven with Kafka)",
      "description": "Specific architecture, infrastructure trade-offs, and operational assumptions."
    }
  ]
}`, topic, contextStr)

	agent := model.Agent{
		Provider: model.ProviderAnthropic,
		Model:    "claude-sonnet-5",
	}
	reply, err := r.Respond(ctx, agent, topic, prompt, "You are an expert systems architect and decision theorist.", nil, "")
	if err != nil {
		return HeuristicExtractHypotheses(topic, contextStr), nil
	}

	content := reply.Content
	// Extract JSON block if surrounded by markdown code fences
	if start := strings.Index(content, "{"); start != -1 {
		if end := strings.LastIndex(content, "}"); end != -1 && end > start {
			content = content[start : end+1]
		}
	}

	var parsed hypothesisExtractionResponse
	if err := json.Unmarshal([]byte(content), &parsed); err != nil || len(parsed.Hypotheses) < 2 {
		return HeuristicExtractHypotheses(topic, contextStr), nil
	}

	var results []model.Hypothesis
	for i, h := range parsed.Hypotheses {
		if i >= len(defaultHypothesisColors) {
			break
		}
		label := strings.TrimSpace(h.Label)
		if label == "" {
			label = fmt.Sprintf("Alternative %d", i+1)
		}
		desc := strings.TrimSpace(h.Description)
		if desc == "" {
			desc = label
		}
		results = append(results, model.Hypothesis{
			ID:          fmt.Sprintf("hyp_%d", i+1),
			Index:       i + 1,
			Label:       label,
			Description: desc,
			ColorHex:    defaultHypothesisColors[i],
		})
	}

	if len(results) < 2 {
		return HeuristicExtractHypotheses(topic, contextStr), nil
	}

	return results, nil
}

// HeuristicExtractHypotheses provides deterministic fallback decomposition when an AI runner is unavailable.
func HeuristicExtractHypotheses(topic, contextStr string) []model.Hypothesis {
	cleaned := strings.TrimSpace(topic)
	lower := strings.ToLower(cleaned)

	var alternatives []string

	if strings.Contains(lower, " vs ") {
		parts := strings.Split(cleaned, " vs ")
		for _, p := range parts {
			trimmed := strings.TrimSpace(p)
			if trimmed != "" {
				alternatives = append(alternatives, trimmed)
			}
		}
	} else if strings.Contains(lower, " vs. ") {
		parts := strings.Split(cleaned, " vs. ")
		for _, p := range parts {
			trimmed := strings.TrimSpace(p)
			if trimmed != "" {
				alternatives = append(alternatives, trimmed)
			}
		}
	} else if strings.Contains(lower, " or ") {
		parts := strings.Split(cleaned, " or ")
		for _, p := range parts {
			trimmed := strings.TrimSpace(p)
			if trimmed != "" {
				alternatives = append(alternatives, trimmed)
			}
		}
	}

	if len(alternatives) >= 2 {
		var list []model.Hypothesis
		for i, alt := range alternatives {
			if i >= len(defaultHypothesisColors) {
				break
			}
			list = append(list, model.Hypothesis{
				ID:          fmt.Sprintf("hyp_%d", i+1),
				Index:       i + 1,
				Label:       alt,
				Description: fmt.Sprintf("Adopt strategy: %s to resolve the dilemma.", alt),
				ColorHex:    defaultHypothesisColors[i],
			})
		}
		return list
	}

	// Default fallback: Dual-choice + Status Quo
	return []model.Hypothesis{
		{
			ID:          "hyp_1",
			Index:       1,
			Label:       "Proposed Architectural Shift",
			Description: fmt.Sprintf("Execute proposed change or migration: %s", cleaned),
			ColorHex:    defaultHypothesisColors[0],
		},
		{
			ID:          "hyp_2",
			Index:       2,
			Label:       "Pragmatic Status Quo",
			Description: "Retain current architecture with tactical optimizations and latency mitigations.",
			ColorHex:    defaultHypothesisColors[1],
		},
	}
}
