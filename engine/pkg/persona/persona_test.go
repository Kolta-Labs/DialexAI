package persona

import (
	"strings"
	"testing"
)

func TestCompilePrompt(t *testing.T) {
	riskAnalyst := GetBuiltinPersonaDNA("the-risk-analyst")
	if riskAnalyst == nil {
		t.Fatal("expected built-in risk analyst DNA to be present")
	}

	compiled := CompilePrompt(riskAnalyst)

	// Verify header
	if !strings.Contains(compiled, "[COGNITIVE DNA MANDATE: THE RISK ANALYST]") {
		t.Errorf("missing mandate header in compiled prompt: %s", compiled)
	}

	// Verify Layer 1 Core Identity
	if !strings.Contains(compiled, "Staff Infrastructure Risk Analyst") {
		t.Errorf("missing core identity title in compiled prompt")
	}

	// Verify Layer 2 Epistemic Bias
	if !strings.Contains(compiled, "EMPIRICAL_STATISTICAL") {
		t.Errorf("missing epistemic bias mode in compiled prompt")
	}

	// Verify Layer 4 Heuristics
	if !strings.Contains(compiled, "Chesterton's Fence") {
		t.Errorf("missing Chesterton's fence heuristic in compiled prompt")
	}

	// Verify Layer 5 Taboo Space
	if !strings.Contains(compiled, "scale horizontally if latency spikes") {
		t.Errorf("missing taboo constraint in compiled prompt")
	}

	// Verify Layer 7 Adversarial Posture
	if !strings.Contains(compiled, "COUNTER_ATTACKING") {
		t.Errorf("missing combat stance in compiled prompt")
	}

	// Verify Layer 8 Synthesis Mandate
	if !strings.Contains(compiled, "HOLD_MINORITY_REPORT") {
		t.Errorf("missing minority report mandate in compiled prompt")
	}
}

func TestImportExportRoundtrip(t *testing.T) {
	dna := GetBuiltinPersonaDNA("the-pragmatist")
	if dna == nil {
		t.Fatal("expected built-in pragmatist DNA to be present")
	}

	// Test YAML export
	yamlBytes, err := ExportDNA(dna, "yaml")
	if err != nil {
		t.Fatalf("failed to export YAML: %v", err)
	}
	if len(yamlBytes) == 0 {
		t.Fatal("exported YAML is empty")
	}

	// Test YAML import
	importedYAML, err := ImportDNA(yamlBytes, "yaml")
	if err != nil {
		t.Fatalf("failed to import YAML: %v", err)
	}
	if importedYAML.ID != dna.ID || importedYAML.Name != dna.Name {
		t.Errorf("imported DNA mismatch: expected %s, got %s", dna.Name, importedYAML.Name)
	}

	// Test JSON export
	jsonBytes, err := ExportDNA(dna, "json")
	if err != nil {
		t.Fatalf("failed to export JSON: %v", err)
	}

	// Test JSON import
	importedJSON, err := ImportDNA(jsonBytes, "json")
	if err != nil {
		t.Fatalf("failed to import JSON: %v", err)
	}
	if importedJSON.AdversarialPosture.Stance != dna.AdversarialPosture.Stance {
		t.Errorf("imported JSON stance mismatch: expected %s, got %s", dna.AdversarialPosture.Stance, importedJSON.AdversarialPosture.Stance)
	}
}

func TestValidateDNA(t *testing.T) {
	dna := GetBuiltinPersonaDNA("the-risk-analyst")
	if err := ValidateDNA(dna); err != nil {
		t.Errorf("expected valid DNA, got: %v", err)
	}

	invalid := *dna
	invalid.ID = ""
	if err := ValidateDNA(&invalid); err == nil {
		t.Error("expected error on empty ID")
	}

	invalid = *dna
	invalid.EpistemicBias.TheoryVsPractice = 1.5
	if err := ValidateDNA(&invalid); err == nil {
		t.Error("expected error on out-of-bounds theoryVsPractice")
	}
}
