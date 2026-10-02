package orchestrator

import (
	"strings"
	"testing"
)

func TestLoopVerbatimDuplicateBlockIntercepted(t *testing.T) {
	text := "The Cybernetic Anschluss: On the Hubris of the Grand Synthesis.\n" +
		"We observe that categorical imperatives cannot be reconciled with stochastic gradient descent without introducing formal contradictions into the ontological framework.\n" +
		"Specifically, as demonstrated in Theorem 4.2 of recursive semantics, the alignment guarantee degenerates asymptotically."
	r := InspectLoop(text, []string{text}, 0.65, 100)
	if !r.Detected || len([]rune(r.LongestContiguousMatch)) < 100 || r.DuplicatedTurnRound != 1 {
		t.Fatalf("got %+v", r)
	}
}

func TestLoopJaccardThreshold(t *testing.T) {
	turn1 := "The fundamental constraint in distributed consensus is network partitioning and latency asymmetry across wide-area networks."
	para := "The fundamental constraint in distributed consensus protocols is network partitioning and latency asymmetry across regional networks."
	novel := "In contrast to network partitioning, modern cryptographic proofs like zk-SNARKs eliminate the latency verification bottleneck entirely."
	if r := InspectLoop(para, []string{turn1}, 0.60, 180); !r.Detected {
		t.Errorf("paraphrase should loop: %+v", r)
	}
	if r := InspectLoop(novel, []string{turn1}, 0.60, 180); r.Detected {
		t.Errorf("novel should be clean: %+v", r)
	}
}

func TestLoopCleanEdgeCases(t *testing.T) {
	long := strings.Repeat("alpha beta gamma delta ", 10)
	for name, r := range map[string]LoopResult{
		"blank":     InspectLoop("   ", []string{long}, 0.65, 180),
		"no priors": InspectLoop(long, nil, 0.65, 180),
		"short":     InspectLoop("too short", []string{"too short"}, 0.65, 180),
		"code only": InspectLoop("```x```", []string{long}, 0.65, 180),
	} {
		if r.Detected {
			t.Errorf("%s: %+v", name, r)
		}
	}
}

func TestLoopPersistentDuplicateDetectedAtDefaults(t *testing.T) {
	s := "Persistent identical argument repeated forever."
	r := InspectLoop(s, []string{s}, 0.65, 180)
	if !r.Detected || r.SimilarityScore != 1.0 && r.SimilarityScore < 0.65 {
		t.Fatalf("got %+v", r)
	}
	if !strings.Contains(r.Reason, "Structural loop detected: Jaccard similarity 100% >= 65%") {
		t.Errorf("reason %q", r.Reason)
	}
}

func TestLoopAgreementEchoOnlyAfterFourPriors(t *testing.T) {
	a := "Agreed: the proposal is sound and we should proceed with the migration plan."
	if r := InspectLoop(a, []string{a, "x"}, 0.65, 180); r.Detected {
		t.Errorf("fewer than 4 priors must not flag: %+v", r)
	}
	r := InspectLoop(a, []string{a, a, a, a}, 0.65, 180)
	if !r.Detected || r.LongestContiguousMatch != "Repeated agreement formula" {
		t.Errorf("got %+v", r)
	}
}

func TestLongestCommonSubstring(t *testing.T) {
	if got := LongestCommonSubstring("xxabcdyy", "zzabcdww"); got != "abcd" {
		t.Errorf("got %q", got)
	}
	if got := LongestCommonSubstring("abc", "xyz"); got != "" {
		t.Errorf("got %q", got)
	}
}
