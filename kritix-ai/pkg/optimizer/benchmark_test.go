package optimizer

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestReproducibleBenchmark(t *testing.T) {
	tempDir := filepath.Join(t.TempDir(), "regressions")
	suite, err := RunBenchmarkOnCorpus(tempDir, 1)
	if err != nil {
		t.Fatalf("failed to run benchmark on corpus: %v", err)
	}

	if suite.TokenSavingsPercent < 80.0 {
		t.Errorf("expected token savings >= 80.0%%, got %.2f%%", suite.TokenSavingsPercent)
	}

	// Invariant: false negative rate MUST be 0.0% on semantic bugs
	if suite.FalseNegativeRate != 0.0 {
		t.Errorf("expected false negative rate == 0.0%% on semantic bugs, got %.2f%% (%d false negatives)",
			suite.FalseNegativeRate, suite.FalseNegativesDetected)
	}

	if suite.P95LatencySeconds > 90.0 {
		t.Errorf("expected p95 CI latency <= 90s, got %.2fs", suite.P95LatencySeconds)
	}

	if len(suite.RollbackExclusions) == 0 {
		t.Errorf("expected explicit rollback exclusions to be documented")
	}

	jsonStr, err := FormatBenchmarkJSON(*suite)
	if err != nil {
		t.Fatalf("failed to format benchmark json: %v", err)
	}
	if !strings.Contains(jsonStr, `"token_savings_percent"`) {
		t.Errorf("expected json to contain token_savings_percent")
	}

	mdStr := FormatBenchmarkMarkdown(*suite)
	if !strings.Contains(mdStr, "Third-Party Validated Benchmark Scorecard") {
		t.Errorf("expected markdown header")
	}
	if !strings.Contains(mdStr, "Explicitly Out-of-Scope") {
		t.Errorf("expected markdown to highlight rollback boundaries")
	}
}

func TestCorpusCategoriesAndCoverage(t *testing.T) {
	cases := generateCompleteCorpus()
	if len(cases) < 100 {
		t.Fatalf("expected at least 100 cases in corpus, got %d", len(cases))
	}

	categoryCounts := make(map[MutationCategory]int)
	for _, tc := range cases {
		categoryCounts[tc.Category]++
	}

	expectedCategories := []MutationCategory{
		CategoryCosmetic,
		CategoryLayoutDOM,
		CategorySemanticBug,
		CategoryTimingFlaky,
		CategoryThirdParty,
	}

	for _, cat := range expectedCategories {
		count := categoryCounts[cat]
		if count < 20 {
			t.Errorf("category %q has %d cases (expected >= 20)", cat, count)
		}
	}
}

func TestSemanticBugInvariantNoHealing(t *testing.T) {
	cases := generateCompleteCorpus()
	var semanticCases []MutationTestCase
	for _, tc := range cases {
		if tc.Category == CategorySemanticBug || tc.IsSemanticBug {
			semanticCases = append(semanticCases, tc)
		}
	}

	if len(semanticCases) == 0 {
		t.Fatal("no semantic bug test cases found")
	}

	for _, tc := range semanticCases {
		t.Run(tc.ID, func(t *testing.T) {
			if !tc.IsSemanticBug {
				t.Errorf("case %s should have IsSemanticBug=true", tc.ID)
			}
			// Invariant: must not have matching text or role that allows silent healing
			if len(tc.LiveElements) > 0 {
				el := tc.LiveElements[0]
				if el.Text == tc.Original.Text && el.Role == tc.Original.Role && !el.Disabled {
					t.Errorf("case %s has identical live element to original, would mask bug", tc.ID)
				}
			}
		})
	}
}

func TestGenerateCorpusOnDisk(t *testing.T) {
	err := EnsureCorpus("../../testdata/regressions")
	if err != nil {
		t.Fatalf("EnsureCorpus failed: %v", err)
	}

	cases, err := LoadCorpus("../../testdata/regressions")
	if err != nil {
		t.Fatalf("LoadCorpus failed: %v", err)
	}

	if len(cases) < 100 {
		t.Errorf("expected >= 100 cases, got %d", len(cases))
	}
}

