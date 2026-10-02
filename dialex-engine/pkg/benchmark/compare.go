package benchmark

import (
	"fmt"
	"sort"
	"strings"
)

// GroupSummary aggregates the runs that share a baseline and an independence setting, so that
// "does a blind first round reduce conformity, and does it change quality?" can be read off
// one table.
type GroupSummary struct {
	Baseline                  string
	Independence              string
	Runs                      int
	MeanDeltaQ                float64
	CouncilWinRate            float64
	PValue                    float64
	MeanConvergence           float64
	MeanFirstRoundConvergence float64
	MeanTokenRatio            float64
	// Runs whose scores are weaker evidence: a same-family judge, or heuristic-scored passes.
	JudgeOverlapRuns    int
	JudgeFallbackPasses int
}

// GroupRuns groups runs by (baseline, independence); empty fields from older runs mean
// "solo" and "open". Groups come back in a stable order.
func GroupRuns(runs []BenchmarkRun) []GroupSummary {
	type key struct{ baseline, independence string }
	buckets := map[key][]BenchmarkRun{}
	for _, r := range runs {
		k := key{r.Baseline, r.Independence}
		if k.baseline == "" {
			k.baseline = BaselineSolo
		}
		if k.independence == "" {
			k.independence = "open"
		}
		buckets[k] = append(buckets[k], r)
	}
	var out []GroupSummary
	for k, rs := range buckets {
		sum := ComputeSummary(rs)
		g := GroupSummary{
			Baseline: k.baseline, Independence: k.independence, Runs: len(rs),
			MeanDeltaQ: sum.MeanDeltaQ, CouncilWinRate: sum.CouncilWinRate, PValue: sum.PValue,
		}
		for _, r := range rs {
			g.MeanConvergence += r.CouncilResult.Convergence
			g.MeanFirstRoundConvergence += r.CouncilResult.FirstRoundConvergence
			g.MeanTokenRatio += r.TokenRatio
			g.JudgeFallbackPasses += r.JudgeFallbackPasses
			if r.JudgeOverlap {
				g.JudgeOverlapRuns++
			}
		}
		n := float64(len(rs))
		g.MeanConvergence /= n
		g.MeanFirstRoundConvergence /= n
		g.MeanTokenRatio /= n
		out = append(out, g)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].Baseline != out[j].Baseline {
			return out[i].Baseline < out[j].Baseline
		}
		return out[i].Independence < out[j].Independence
	})
	return out
}

// CompareMarkdown renders the groups as a table, with the caveats that decide how far to trust it.
func CompareMarkdown(groups []GroupSummary) string {
	var b strings.Builder
	b.WriteString("| baseline | independence | runs | council win rate | mean ΔQ | p | convergence (final) | convergence (round 1) | token ratio | same-family judge runs | heuristic-judged passes |\n")
	b.WriteString("|---|---|---|---|---|---|---|---|---|---|---|\n")
	for _, g := range groups {
		fmt.Fprintf(&b, "| %s | %s | %d | %.0f%% | %+.2f | %.3f | %.2f | %.2f | %.2f | %d | %d |\n",
			g.Baseline, g.Independence, g.Runs, g.CouncilWinRate*100, g.MeanDeltaQ, g.PValue,
			g.MeanConvergence, g.MeanFirstRoundConvergence, g.MeanTokenRatio, g.JudgeOverlapRuns, g.JudgeFallbackPasses)
	}
	b.WriteString("\nRead with care: ΔQ is judged by an LLM (self-preference bias if the judge is the same family; passes scored by a keyword heuristic are not judge data). " +
		"Convergence is word overlap, not agreement. A token ratio far from 1.00 means compute was not matched. Small n gives wide intervals.\n")
	return b.String()
}
