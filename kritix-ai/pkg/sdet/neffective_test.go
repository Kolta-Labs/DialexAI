package sdet

import (
	"encoding/json"
	"os"
	"testing"
)

func TestNEffectiveComputation_CurrentCorpus(t *testing.T) {
	corpusPath := findCorpusPath(t, "testdata/corpus/independent_heal_corpus.json")
	data, err := os.ReadFile(corpusPath)
	if err != nil {
		t.Fatalf("Failed to read frozen corpus: %v", err)
	}

	var corpus struct {
		Cases []HealTestCase `json:"cases"`
	}
	if err := json.Unmarshal(data, &corpus); err != nil {
		t.Fatalf("Unmarshal error: %v", err)
	}

	nEff, clusters := ComputeNEffectiveClusters(corpus.Cases)
	t.Logf("Total raw cases in corpus: %d", len(corpus.Cases))
	t.Logf("Computed n_effective (distinct semantic-bug clusters): %d", nEff)

	for h, ids := range clusters {
		t.Logf("Cluster [%s...]: %d cases (example: %s)", h[:8], len(ids), ids[0])
	}
}
