package benchmark

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"socratix/pkg/model"
	"socratix/pkg/runner"
)

// Rubric scoring asks the judge a yes/no question per checklist item instead of for a holistic
// grade. Each arm is scored on its own, in a separate call that never mentions which arm wrote
// it, so there is no pairwise comparison for a judge to tilt toward its own family or toward the
// longer answer. It is a second, less biased opinion beside the pairwise judge, not a replacement.

// RubricItem is one checklist question taken from the benchmark case.
type RubricItem struct {
	Kind string `json:"kind"` // TRAP, AXIS or BOUNDARY
	Text string `json:"text"`
}

// RubricResult is the checklist outcome for one deliverable.
type RubricResult struct {
	Available bool    `json:"available"`
	Score     float64 `json:"score"` // share of items handled well, 0..1
	Items     int     `json:"items"`
	Error     string  `json:"error,omitempty"`
}

// BuildRubric turns a case into checklist items.
func BuildRubric(c BenchmarkCase) []RubricItem {
	var items []RubricItem
	for _, t := range c.GroundTruthTraps {
		items = append(items, RubricItem{"TRAP", t})
	}
	for _, a := range c.RequiredTradeOffAxes {
		items = append(items, RubricItem{"AXIS", a})
	}
	for _, b := range c.MandatoryBoundaryConditions {
		items = append(items, RubricItem{"BOUNDARY", b})
	}
	return items
}

func rubricPrompt(c BenchmarkCase, items []RubricItem, deliverable string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "You are a strict technical checker. Read the SUBMISSION and answer one yes/no question per item. Quote nothing from outside the submission.\n\nCASE: %s\nDILEMMA: %s\n\nITEMS:\n", c.Title, c.Dilemma)
	for i, it := range items {
		switch it.Kind {
		case "TRAP":
			fmt.Fprintf(&b, "%d. TRAP: does the submission COMMIT this error or assert it as true? \"%s\"\n", i+1, it.Text)
		case "AXIS":
			fmt.Fprintf(&b, "%d. AXIS: does the submission explicitly weigh this trade-off? \"%s\"\n", i+1, it.Text)
		default:
			fmt.Fprintf(&b, "%d. BOUNDARY: does the submission explicitly handle this condition? \"%s\"\n", i+1, it.Text)
		}
	}
	fmt.Fprintf(&b, "\nSUBMISSION:\n%s\n\nReply with ONLY JSON: {\"items\":[{\"id\":1,\"yes\":true},...]} with one entry per item.", deliverable)
	return b.String()
}

// scoreRubric combines the answers: a TRAP counts as handled well when the answer is "no", the
// others when it is "yes". Missing or out-of-range ids make the whole result unusable.
func scoreRubric(items []RubricItem, answers map[int]bool) (float64, bool) {
	if len(items) == 0 || len(answers) != len(items) {
		return 0, false
	}
	good := 0
	for i, it := range items {
		yes, ok := answers[i+1]
		if !ok {
			return 0, false
		}
		if (it.Kind == "TRAP") != yes {
			good++
		}
	}
	return float64(good) / float64(len(items)), true
}

// EvaluateRubric scores one deliverable. A judge error or unusable reply gives Available=false:
// there is deliberately no keyword fallback.
func EvaluateRubric(ctx context.Context, rnr runner.AgentRunner, judge model.Agent, c BenchmarkCase, deliverable string) RubricResult {
	items := BuildRubric(c)
	if rnr == nil || len(items) == 0 {
		return RubricResult{Error: "no judge or no rubric items"}
	}
	reply, err := rnr.Respond(ctx, judge, "Rubric Check", rubricPrompt(c, items, sanitizeSubmission(deliverable)), "", nil, "")
	if err != nil {
		return RubricResult{Items: len(items), Error: err.Error()}
	}
	answers, err := parseRubricAnswers(reply.Content)
	if err != nil {
		return RubricResult{Items: len(items), Error: "unparsable rubric reply"}
	}
	score, ok := scoreRubric(items, answers)
	if !ok {
		return RubricResult{Items: len(items), Error: "rubric reply did not answer every item"}
	}
	return RubricResult{Available: true, Score: score, Items: len(items)}
}

func parseRubricAnswers(content string) (map[int]bool, error) {
	start, end := strings.Index(content, "{"), strings.LastIndex(content, "}")
	if start < 0 || end <= start {
		return nil, fmt.Errorf("no JSON")
	}
	var parsed struct {
		Items []struct {
			ID  int  `json:"id"`
			Yes bool `json:"yes"`
		} `json:"items"`
	}
	if err := json.Unmarshal([]byte(content[start:end+1]), &parsed); err != nil {
		return nil, err
	}
	out := map[int]bool{}
	for _, it := range parsed.Items {
		out[it.ID] = it.Yes
	}
	return out, nil
}
