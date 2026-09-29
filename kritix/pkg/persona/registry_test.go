package persona

import (
	"os"
	"path/filepath"
	"testing"

	"dialex/pkg/model"
)

func TestRegistryBuiltins(t *testing.T) {
	reg := NewRegistry("")

	expected := []string{
		"product_owner_lead",
		"senior_software_architect",
		"qa_testing_lead",
		"engineering_manager",
		"senior_staff_engineer",
		"backend_engineer",
		"android_engineer",
		"ios_engineer",
		"business_domain_expert",
		"adversarial_code_reviewer",
		"security_auditor",
	}

	for _, id := range expected {
		p, ok := reg.Get(id)
		if !ok {
			t.Errorf("expected builtin persona %q to exist", id)
			continue
		}
		if p.DNA == nil {
			t.Errorf("expected persona %q to have non-nil DNA", id)
		}
		if !p.IsSystem {
			t.Errorf("expected persona %q to have IsSystem=true", id)
		}
	}
}

func TestRegistryCustomPersona(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_persona_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	reg := NewRegistry(tempDir)

	custom := model.Persona{
		ID:          "custom_cloud_finops",
		Name:        "Cloud FinOps Architect",
		Role:        "Cloud Cost & Resource Optimizer",
		Category:    "Architecture & Operations",
		Description: "Specialist in AWS/GCP bill optimization, reserved instances, and idle worker culling.",
		DNA: &model.PersonaDNA{
			ID:   "custom_cloud_finops",
			Name: "Cloud FinOps Architect",
			Role: "Cloud Cost & Resource Optimizer",
			CoreIdentity: model.CoreIdentity{
				Title: "Principal FinOps Engineer",
			},
		},
	}

	// 1. Save custom persona to repo scope
	if err := reg.SaveCustom(custom, true); err != nil {
		t.Fatalf("failed to save custom persona: %v", err)
	}

	// 2. Verify file was written to disk
	expectedFile := filepath.Join(tempDir, ".kritix", "personas", "custom_cloud_finops.json")
	if _, err := os.Stat(expectedFile); err != nil {
		t.Errorf("expected custom persona file to exist at %s", expectedFile)
	}

	// 3. Verify retrieval from registry
	retrieved, ok := reg.Get("custom_cloud_finops")
	if !ok {
		t.Fatalf("expected custom persona to be in registry")
	}
	if retrieved.Name != "Cloud FinOps Architect" {
		t.Errorf("unexpected name: %s", retrieved.Name)
	}

	// 4. Delete custom persona
	if err := reg.DeleteCustom("custom_cloud_finops"); err != nil {
		t.Fatalf("failed to delete custom persona: %v", err)
	}
	if _, ok := reg.Get("custom_cloud_finops"); ok {
		t.Errorf("expected custom persona to be removed from registry")
	}
}
