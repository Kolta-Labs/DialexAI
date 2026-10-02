package orchestrator

import "testing"

func TestSemanticSimilarityDriftSplit(t *testing.T) {
	topic := "Can AI replace product managers"
	off := SemanticSimilarity("We must evaluate the fluid viscosity and rheometer calibration drag coefficients under non-Newtonian flow.", topic)
	on := SemanticSimilarity("Product managers bridge technical roadmaps with enterprise customer feedback to prioritize engineering headcount.", topic)
	if off >= 0.45 {
		t.Errorf("off-topic %v should be < 0.45", off)
	}
	if on <= 0.45 {
		t.Errorf("on-topic %v should be > 0.45", on)
	}
}

func TestSemanticSimilarityEdges(t *testing.T) {
	if SemanticSimilarity("", "topic words") != 0 || SemanticSimilarity("some text", "  ") != 0 {
		t.Error("blank => 0")
	}
	if SemanticSimilarity("the and of", "enterprise architecture") != 0 {
		t.Error("only stopwords => 0")
	}
	if got := SemanticSimilarity("enterprise architecture", "enterprise architecture"); got != 1 {
		t.Errorf("identical => 1, got %v", got)
	}
	// stem/prefix match: "managers" vs "manager"
	if SemanticSimilarity("manager", "managers") <= 0 {
		t.Error("prefix match")
	}
}
