package benchmark

import (
	"strings"
	"unicode"

	"dialex/pkg/model"
)

// RoundConvergence is the mean pairwise Jaccard similarity of the word sets that the council
// seats used in the given round (words of 4+ letters, lower-cased). It is a blunt, model-free
// proxy for "did the seats end up saying the same thing": cheap and reproducible, but it
// measures wording overlap, not whether they agree. Returns 0 with fewer than two seats.
func RoundConvergence(transcript []model.DebateMessage, round int) float64 {
	var sets []map[string]bool
	for _, m := range transcript {
		if m.Round == round && !m.IsError && !m.IsSystem && !m.IsUserComment {
			sets = append(sets, wordSet(m.Content))
		}
	}
	if len(sets) < 2 {
		return 0
	}
	sum, pairs := 0.0, 0
	for i := 0; i < len(sets); i++ {
		for j := i + 1; j < len(sets); j++ {
			sum += jaccard(sets[i], sets[j])
			pairs++
		}
	}
	return sum / float64(pairs)
}

func wordSet(s string) map[string]bool {
	out := map[string]bool{}
	for _, w := range strings.FieldsFunc(strings.ToLower(s), func(r rune) bool { return !unicode.IsLetter(r) }) {
		if len(w) >= 4 {
			out[w] = true
		}
	}
	return out
}

func jaccard(a, b map[string]bool) float64 {
	if len(a) == 0 && len(b) == 0 {
		return 0
	}
	inter := 0
	for w := range a {
		if b[w] {
			inter++
		}
	}
	return float64(inter) / float64(len(a)+len(b)-inter)
}
