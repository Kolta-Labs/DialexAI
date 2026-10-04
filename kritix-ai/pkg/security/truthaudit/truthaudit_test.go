package truthaudit

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAuditor_RealRepo(t *testing.T) {
	auditor := NewAuditor("../../..")
	res, err := auditor.RunAll()
	if err != nil {
		t.Fatalf("unexpected error running auditor: %v", err)
	}

	if !res.Passed {
		for _, v := range res.Violations {
			t.Errorf("[%s] %s (line %d): %s", v.Gate, v.File, v.Line, v.Description)
		}
	}
}

func TestAuditor_MutationTests_ChecksFailOnViolations(t *testing.T) {
	tempDir := t.TempDir()
	auditor := &Auditor{
		RepoRoot:  tempDir,
		KritixDir: tempDir,
	}

	// 1. Provenance mutation test: missing harness_run_id or bad hash
	benchPath := filepath.Join(tempDir, "benchmark.json")
	_ = os.WriteFile(benchPath, []byte(`{
		"target_app_name": "corpus-classifier",
		"executed_target": "corpus-classifier",
		"raw_log_path": "nonexistent.jsonl",
		"raw_log_sha256": "badhash"
	}`), 0644)

	violations, _ := auditor.CheckProvenance()
	if len(violations) == 0 {
		t.Errorf("expected Provenance check to fail on missing harness_run_id and missing raw log")
	}

	// 2. Label honesty mutation: Medusa claimed without docker_digest
	_ = os.WriteFile(benchPath, []byte(`{
		"target_app_name": "Medusa Storefront (245k LOC)",
		"executed_target": "corpus-classifier"
	}`), 0644)

	violations, _ = auditor.CheckLabelHonesty()
	if len(violations) == 0 {
		t.Errorf("expected LabelHonesty check to fail on Medusa without docker_image_digests")
	}

	// 3. Stats gate mutation: t.Logf Note on CI
	testFile := filepath.Join(tempDir, "pkg", "sample_test.go")
	_ = os.MkdirAll(filepath.Dir(testFile), 0755)
	_ = os.WriteFile(testFile, []byte(`package pkg
import "testing"
func TestMockStatsSample(t *testing.T) {
	t.Logf("Note on CI: bound is 10%%")
}`), 0644)

	violations, _ = auditor.CheckStatsGate()
	if len(violations) == 0 {
		t.Errorf("expected StatsGate check to fail on loose t.Logf Note on CI")
	}

	// 4. Independence gate mutation: artificial repeat loop
	indepTestFile := filepath.Join(tempDir, "pkg", "study_test.go")
	_ = os.WriteFile(indepTestFile, []byte(`package pkg
import "testing"
func TestStudyLoop(t *testing.T) {
	for repeat := 0; repeat < 5; repeat++ {
		_ = repeat
	}
}`), 0644)

	violations, _ = auditor.CheckIndependenceGate()
	if len(violations) == 0 {
		t.Errorf("expected IndependenceGate to fail on artificial repeat loop")
	}

	// 5. Synthetic headline mutation: unlabelled token reduction headline in README
	readmeFile := filepath.Join(tempDir, "README.md")
	_ = os.WriteFile(readmeFile, []byte(`# Product
- **90-95% Token Reduction**: unlabelled marketing claim
`), 0644)

	violations, _ = auditor.CheckSyntheticHeadline()
	if len(violations) == 0 {
		t.Errorf("expected SyntheticHeadline to fail on unlabelled token reduction claim")
	}

	// 6. Hygiene mutation: stray TestSample function
	hygieneFile := filepath.Join(tempDir, "pkg", "scratch_test.go")
	_ = os.WriteFile(hygieneFile, []byte(`package pkg
import "testing"
func TestDebugScratch(t *testing.T) {}
`), 0644)

	violations, _ = auditor.CheckHygiene()
	if len(violations) == 0 {
		t.Errorf("expected Hygiene check to fail on TestDebugScratch")
	}
}
