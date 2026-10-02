package benchmark

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"unicode"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

// StanceMetrics replaces "word overlap" with what a reader means by agreement: do the seats
// recommend the same thing, and did a seat move to the majority between round 1 and the end?
// A cheap classifier call reads the first and final round and gives every seat a short
// recommendation label (same wording for the same recommendation).
//
// ChangedToMajority is a capitulation PROXY: it counts seats that switched to the final
// majority position. It cannot tell a good argument from social pressure; read the transcripts
// of those cases before concluding anything.
type StanceMetrics struct {
	Available         bool    `json:"available"`
	Seats             int     `json:"seats"`
	AgreementFirst    float64 `json:"agreementFirst"` // share of seat pairs with the same recommendation, round 1
	AgreementFinal    float64 `json:"agreementFinal"`
	PositionChanges   int     `json:"positionChanges"`   // seats whose recommendation differs between round 1 and the end
	ChangedToMajority int     `json:"changedToMajority"` // of those, how many ended on the majority recommendation
	Error             string  `json:"error,omitempty"`
}

func normalizeLabel(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	return strings.Join(strings.FieldsFunc(s, func(r rune) bool { return !unicode.IsLetter(r) && !unicode.IsDigit(r) }), " ")
}

func pairAgreement(labels []string) float64 {
	if len(labels) < 2 {
		return 0
	}
	same, pairs := 0, 0
	for i := 0; i < len(labels); i++ {
		for j := i + 1; j < len(labels); j++ {
			pairs++
			if labels[i] == labels[j] {
				same++
			}
		}
	}
	return float64(same) / float64(pairs)
}

// majority returns the most common label and its count; ties go to the alphabetically first
// label so the result is deterministic.
func majority(labels []string) (string, int) {
	counts := map[string]int{}
	for _, l := range labels {
		counts[l]++
	}
	var keys []string
	for k := range counts {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	best, n := "", 0
	for _, k := range keys {
		if counts[k] > n {
			best, n = k, counts[k]
		}
	}
	return best, n
}

// stanceFromLabels computes the metrics from per-seat labels (same seat order in both rounds).
func stanceFromLabels(first, final []string) StanceMetrics {
	m := StanceMetrics{Available: true, Seats: len(first)}
	m.AgreementFirst, m.AgreementFinal = pairAgreement(first), pairAgreement(final)
	maj, n := majority(final)
	for i := range first {
		if i < len(final) && first[i] != final[i] {
			m.PositionChanges++
			if n > 1 && final[i] == maj {
				m.ChangedToMajority++
			}
		}
	}
	return m
}

// AnalyzeStance runs the classifier over a council transcript. Seats are shown as S1..Sn, so the
// classifier never sees persona names.
func AnalyzeStance(ctx context.Context, rnr runner.AgentRunner, judge model.Agent, c BenchmarkCase, transcript []model.DebateMessage, finalRound int) StanceMetrics {
	if rnr == nil {
		return StanceMetrics{Error: "no classifier runner"}
	}
	var seatOrder []string
	index := map[string]int{}
	for _, m := range transcript {
		if _, ok := index[m.SeatID]; !ok && m.SeatID != "" {
			index[m.SeatID] = len(seatOrder)
			seatOrder = append(seatOrder, m.SeatID)
		}
	}
	if len(seatOrder) < 2 || finalRound < 2 {
		return StanceMetrics{Error: "need at least two seats and two rounds"}
	}
	turn := func(round int) []string {
		out := make([]string, len(seatOrder))
		for _, m := range transcript {
			if m.Round == round && !m.IsError {
				if i, ok := index[m.SeatID]; ok {
					out[i] = m.Content
				}
			}
		}
		return out
	}
	var b strings.Builder
	fmt.Fprintf(&b, "Below, several participants answered this question: %s\n\n", c.Dilemma)
	for _, r := range []struct {
		name  string
		round int
	}{{"round1", 1}, {"final", finalRound}} {
		fmt.Fprintf(&b, "== %s ==\n", r.name)
		for i, text := range turn(r.round) {
			fmt.Fprintf(&b, "S%d: %s\n\n", i+1, strings.TrimSpace(text))
		}
	}
	b.WriteString("For each participant and each of the two sections, give the main recommendation as a short label (2 to 5 words). Use IDENTICAL wording for the same recommendation. " +
		"Reply with ONLY JSON: {\"round1\":[\"label for S1\",...],\"final\":[\"label for S1\",...]}")
	reply, err := rnr.Respond(ctx, judge, "Stance Classification", b.String(), "", nil, "")
	if err != nil {
		return StanceMetrics{Error: err.Error()}
	}
	start, end := strings.Index(reply.Content, "{"), strings.LastIndex(reply.Content, "}")
	var parsed struct {
		Round1 []string `json:"round1"`
		Final  []string `json:"final"`
	}
	if start < 0 || end <= start || json.Unmarshal([]byte(reply.Content[start:end+1]), &parsed) != nil ||
		len(parsed.Round1) != len(seatOrder) || len(parsed.Final) != len(seatOrder) {
		return StanceMetrics{Error: "unusable classifier reply"}
	}
	norm := func(in []string) []string {
		out := make([]string, len(in))
		for i, s := range in {
			out[i] = normalizeLabel(s)
		}
		return out
	}
	return stanceFromLabels(norm(parsed.Round1), norm(parsed.Final))
}
