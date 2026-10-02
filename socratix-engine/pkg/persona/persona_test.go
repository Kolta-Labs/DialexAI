package persona

import (
	"math"
	"strings"
	"testing"

	"socratix/pkg/model"
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

func TestValidateDNARejectsNaNAndDefaults(t *testing.T) {
	nan := math.NaN()
	for name, mutate := range map[string]func(*model.PersonaDNA){
		"NaN theory":   func(d *model.PersonaDNA) { d.EpistemicBias.TheoryVsPractice = nan },
		"NaN rigor":    func(d *model.PersonaDNA) { d.EpistemicBias.RigorThreshold = nan },
		"NaN tenacity": func(d *model.PersonaDNA) { d.AdversarialPosture.TenacityScore = nan },
		"neg tenacity": func(d *model.PersonaDNA) { d.AdversarialPosture.TenacityScore = -0.1 },
		"formality 6":  func(d *model.PersonaDNA) { d.CommunicationVector.FormalityLevel = 6 },
		"formality -1": func(d *model.PersonaDNA) { d.CommunicationVector.FormalityLevel = -1 },
		"empty name":   func(d *model.PersonaDNA) { d.Name = "" },
	} {
		dna := *GetBuiltinPersonaDNA("the-risk-analyst")
		mutate(&dna)
		if err := ValidateDNA(&dna); err == nil {
			t.Errorf("%s: expected validation error", name)
		}
	}
	if err := ValidateDNA(nil); err == nil {
		t.Error("nil must be an error")
	}
	dna := *GetBuiltinPersonaDNA("the-risk-analyst")
	dna.CommunicationVector.FormalityLevel, dna.SchemaVersion = 0, ""
	if err := ValidateDNA(&dna); err != nil || dna.CommunicationVector.FormalityLevel != 3 || dna.SchemaVersion == "" {
		t.Errorf("defaults not applied: %+v %v", dna, err)
	}
}

func TestImportDNAFormats(t *testing.T) {
	yamlDoc := "id: p1\nname: Tester\nepistemicBias:\n  theoryVsPractice: 0.3\n"
	for name, tc := range map[string]struct{ raw, hint string }{
		"yaml hint":       {yamlDoc, "yaml"},
		"yaml sniffed":    {yamlDoc, ""},
		"yaml doc marker": {"---\n" + yamlDoc, ""},
		"json sniffed":    {`{"id":"p1","name":"Tester","epistemicBias":{"theoryVsPractice":0.3}}`, ""},
		"json leading ws": {"\n  " + `{"id":"p1","name":"Tester"}`, "json"},
	} {
		dna, err := ImportDNA([]byte(tc.raw), tc.hint)
		if err != nil || dna.ID != "p1" || dna.Name != "Tester" {
			t.Errorf("%s: %+v, %v", name, dna, err)
		}
	}
	for name, raw := range map[string]string{
		"empty":        "",
		"no id":        `{"name":"x"}`,
		"out of range": `{"id":"a","name":"b","epistemicBias":{"theoryVsPractice":7}}`,
		"not a map":    "just some words",
	} {
		if _, err := ImportDNA([]byte(raw), ""); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestCompilePromptOmitsEmptyLayersAndHandlesNil(t *testing.T) {
	if CompilePrompt(nil) != "" {
		t.Error("nil DNA must compile to empty string")
	}
	out := CompilePrompt(&model.PersonaDNA{ID: "x", Name: "Minimal"})
	if !strings.Contains(out, "MINIMAL") {
		t.Errorf("name header missing: %q", out)
	}
	for _, absent := range []string{"TABOO SPACE", "HEURISTIC LIBRARY", "DOMAIN ONTOLOGY", "SUPPLEMENTARY", "CORE IDENTITY"} {
		if strings.Contains(out, absent) {
			t.Errorf("empty layer %q must be omitted", absent)
		}
	}
	full := GetBuiltinPersonaDNA("the-risk-analyst")
	full.RawCustomPrompt = "Always cite a source."
	if got := CompilePrompt(full); !strings.Contains(got, "Always cite a source.") {
		t.Error("custom prompt must be appended")
	}
}

func TestBuiltinPersonasAreValidCompileAndAreIndependentCopies(t *testing.T) {
	// Only these two personas ship full 8-layer DNA in the engine today.
	ids := []string{"the-risk-analyst", "the-pragmatist"}
	for _, id := range ids {
		dna := GetBuiltinPersonaDNA(id)
		if dna == nil {
			t.Fatalf("%s missing", id)
		}
		if err := ValidateDNA(dna); err != nil {
			t.Errorf("built-in %s invalid: %v", id, err)
		}
		if strings.TrimSpace(CompilePrompt(dna)) == "" {
			t.Errorf("built-in %s compiles to nothing", id)
		}
		// Mutating a returned value must not leak into later lookups.
		dna.Name = "MUTATED"
		if GetBuiltinPersonaDNA(id).Name == "MUTATED" {
			t.Errorf("%s: built-in DNA shares state between callers", id)
		}
	}
	if GetBuiltinPersonaDNA("risk-analyst").ID != "the-risk-analyst" {
		t.Error("alias lookup failed")
	}
	if GetBuiltinPersonaDNA("no-such-persona") != nil {
		t.Error("unknown persona must return nil")
	}
}
