package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"artix/pkg/knowledge"
	"artix/pkg/policy"
)

func TestRunCLI_VersionAndHelp(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Version command
	code := RunCLI("", nil, []string{"version"}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for version, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Artix AI version") {
		t.Errorf("expected version output, got: %s", stdout.String())
	}

	// 2. Help command
	stdout.Reset()
	code = RunCLI("", nil, []string{"help"}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for help, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Usage:") {
		t.Errorf("expected usage output, got: %s", stdout.String())
	}

	// 3. Empty args prints usage
	stdout.Reset()
	code = RunCLI("", nil, []string{}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for empty args, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Usage:") {
		t.Errorf("expected usage output, got: %s", stdout.String())
	}
}

func TestRunCLI_PersonaAndUnknown(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Persona list
	code := RunCLI("", nil, []string{"persona"}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for persona, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Available SWE Personas") {
		t.Errorf("expected persona listing, got: %s", stdout.String())
	}

	// 2. Unknown command -> exit 1
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"unknown_foo_bar"}, &stdout, &stderr)
	if code != 1 {
		t.Errorf("expected exit code 1 for unknown command, got %d", code)
	}
	if !strings.Contains(stderr.String(), "Unknown command") {
		t.Errorf("expected unknown command error on stderr, got: %s", stderr.String())
	}
}

func TestRunCLI_MergeCommandValidation(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Missing flags on verify-approval -> error mentioning required flags
	code := RunCLI("", nil, []string{"verify-approval"}, &stdout, &stderr)
	if code != 1 {
		t.Errorf("expected exit code 1 for verify-approval without arguments, got %d", code)
	}
	if !strings.Contains(stderr.String(), "--pr") || !strings.Contains(stderr.String(), "--sha") || !strings.Contains(stderr.String(), "--spec") {
		t.Errorf("expected stderr mentioning required flags, got: %s", stderr.String())
	}

	// 2. Invoking deprecated merge command -> error noting deprecation
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"merge"}, &stdout, &stderr)
	if code != 1 {
		t.Errorf("expected exit code 1 for deprecated merge command, got %d", code)
	}
	if !strings.Contains(stderr.String(), "deprecated") || !strings.Contains(stderr.String(), "verify-approval") {
		t.Errorf("expected stderr mentioning deprecation and verify-approval, got: %s", stderr.String())
	}
}

func TestRunCLI_MergeCommand_AcceptsVerdictHashFlag(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. When --verdict-hash is supplied to verify-approval, verify it parses and does not fail with unknown flag
	code := RunCLI("", nil, []string{"verify-approval", "--pr", "1", "--sha", "abcdef123456", "--spec", "S-01", "--verdict-hash", "hash1234"}, &stdout, &stderr)
	if strings.Contains(stderr.String(), "flag provided but not defined: -verdict-hash") {
		t.Fatalf("runVerifyApproval failed to recognize --verdict-hash flag: %s", stderr.String())
	}
	_ = code

	// 2. Deprecated merge alias with verdict hash returns deprecation error
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"merge", "--pr", "1", "--sha", "abcdef123456", "--verdict-hash", "hash1234"}, &stdout, &stderr)
	if code != 1 || !strings.Contains(stderr.String(), "deprecated") {
		t.Fatalf("expected merge alias to exit 1 with deprecation error, got code=%d stderr=%s", code, stderr.String())
	}
}

func TestR13_5_VerifyApprovalCommand(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Missing flags -> error mentioning required flags
	code := RunCLI("", nil, []string{"verify-approval"}, &stdout, &stderr)
	if code != 1 {
		t.Errorf("expected exit code 1 for verify-approval without arguments, got %d", code)
	}
	if !strings.Contains(stderr.String(), "--pr") || !strings.Contains(stderr.String(), "--sha") || !strings.Contains(stderr.String(), "--spec") {
		t.Errorf("expected stderr mentioning required flags, got: %s", stderr.String())
	}

	// 2. Flags accepted
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"verify-approval", "--pr", "1", "--sha", "abcdef123456", "--spec", "S-01", "--verdict-hash", "hash1234"}, &stdout, &stderr)
	if strings.Contains(stderr.String(), "flag provided but not defined") {
		t.Fatalf("runVerifyApproval failed to recognize flags: %s", stderr.String())
	}
}

func TestR13_5_MergeCommand_Deprecated(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// Plain CLI invocation of 'artix merge' must error as deprecated
	code := RunCLI("", nil, []string{"merge"}, &stdout, &stderr)
	if code == 0 {
		t.Errorf("expected non-zero exit code for deprecated 'artix merge'")
	}
	if !strings.Contains(stderr.String(), "deprecated") || !strings.Contains(stderr.String(), "verify-approval") {
		t.Errorf("expected stderr to state 'artix merge' is deprecated and suggest 'artix verify-approval', got: %s", stderr.String())
	}

	// JSON invocation of 'artix merge' must return JSON error noting deprecation
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"merge", "--json"}, &stdout, &stderr)
	if code == 0 {
		t.Errorf("expected non-zero exit code for deprecated 'artix merge --json'")
	}
	if !strings.Contains(stdout.String(), "deprecated") && !strings.Contains(stderr.String(), "deprecated") {
		t.Errorf("expected JSON/stderr output to mention deprecation, got stdout=%s stderr=%s", stdout.String(), stderr.String())
	}
}

func TestRunCLI_KnowledgeRatify(t *testing.T) {
	tmpDir := t.TempDir()

	// Setup CODEOWNERS file in tmpDir
	codeownersContent := "* @alice @bob\n"
	if err := os.WriteFile(filepath.Join(tmpDir, "CODEOWNERS"), []byte(codeownersContent), 0644); err != nil {
		t.Fatal(err)
	}

	// Create a proposed Knowledge Item in .artix/knowledge/
	kStore := knowledge.NewStore(tmpDir)
	ki := &knowledge.KnowledgeItem{
		ID:           "ki-cli-01",
		Title:        "Memory Safety Invariant",
		Category:     knowledge.CategoryArchitecture,
		Context:      "Handler pool allocation",
		Breakthrough: "Use sync.Pool",
		Status:       "proposed",
	}
	if err := kStore.Save(ki); err != nil {
		t.Fatalf("failed to save proposed KI: %v", err)
	}

	var stdout, stderr bytes.Buffer

	// 1. Unauthorized approver must fail
	code := RunCLI(tmpDir, nil, []string{"knowledge", "ratify", "ki-cli-01", "--approver", "mallory"}, &stdout, &stderr)
	if code == 0 {
		t.Errorf("expected failure when non-CODEOWNER ratifies KI, got exit code 0")
	}
	if !strings.Contains(stderr.String(), "CODEOWNERS") && !strings.Contains(stderr.String(), "not authorized") {
		t.Errorf("expected unauthorized error on stderr, got: %s", stderr.String())
	}

	// 2. Legitimate CODEOWNER ratifies KI -> success
	stdout.Reset()
	stderr.Reset()
	code = RunCLI(tmpDir, nil, []string{"knowledge", "ratify", "ki-cli-01", "--approver", "alice"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("expected exit code 0 for legitimate ratification, got %d: %s", code, stderr.String())
	}

	// Verify KI is now active in store
	updated, err := kStore.Get("ki-cli-01")
	if err != nil {
		t.Fatalf("failed to retrieve updated KI: %v", err)
	}
	if !updated.IsActive() || updated.Status != "active" {
		t.Errorf("expected updated KI to be active, got status=%q isActive=%v", updated.Status, updated.IsActive())
	}
	if updated.ApprovedBy != "alice" {
		t.Errorf("expected ApprovedBy=alice, got %q", updated.ApprovedBy)
	}
}

func TestRunCLI_Code_DisjointModelFamilies_Rejection(t *testing.T) {
	tmpDir := t.TempDir()

	// Create story spec in docs/specs/
	specsDir := filepath.Join(tmpDir, "docs", "specs")
	_ = os.MkdirAll(specsDir, 0755)
	specPath := filepath.Join(specsDir, "STORY-001.md")
	specContent := `# Spec STORY-001: Implement Feature

User Story: As a user I want a feature

## Acceptance Criteria
- [Scenario 1] Given state, When action, Then success

## Verification Commands
` + "```bash\necho ok\n```\n"
	if err := os.WriteFile(specPath, []byte(specContent), 0644); err != nil {
		t.Fatal(err)
	}

	// Policy enforcing disjoint model families
	policyFile := filepath.Join(tmpDir, "policy.json")
	pol := policy.Policy{
		Reviewer: policy.ReviewerPolicyConfig{
			EnforceDisjointModelFamilies: true,
		},
		AllowedTestCommands: []string{"echo ok"},
	}
	data, _ := json.Marshal(pol)
	_ = os.WriteFile(policyFile, data, 0644)
	key := "policy-key"
	policy.SetTrustedKey("test-key", key)
	_ = policy.SignPolicyFile(policyFile, key)
	policy.SetDefaultPolicyPath(policyFile)
	defer func() {
		policy.SetDefaultPolicyPath("/etc/artix/policy.json")
		policy.ResetCache()
	}()

	t.Setenv("ANTHROPIC_API_KEY", "mock-anthropic-key")

	var stdout, stderr bytes.Buffer
	// Both coder and critic are set to same provider "anthropic"
	code := RunCLI(tmpDir, nil, []string{
		"code",
		"--provider", "anthropic",
		"--model", "claude-3-5-sonnet",
		"--review-provider", "anthropic",
		"--review-model", "claude-3-5-sonnet",
		"--confirm-tests",
		specPath,
	}, &stdout, &stderr)

	if code == 0 {
		t.Errorf("expected failure when coder and critic share same model family under EnforceDisjointModelFamilies")
	}
	if !strings.Contains(stderr.String(), "disjoint model families") && !strings.Contains(stderr.String(), "critic model family") {
		t.Errorf("expected error mentioning disjoint model families, got: %s", stderr.String())
	}
}

func TestAutonomyGateErrorText_SignedPolicyRequirement(t *testing.T) {
	tmpDir := t.TempDir()
	policy.ResetCache()
	defer policy.ResetCache()

	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("CI", "true")

	var stdout, stderr bytes.Buffer
	code := RunCLI(tmpDir, nil, []string{
		"code",
		"--autonomy", "autonomous",
		"dummy.md",
	}, &stdout, &stderr)

	if code == 0 {
		t.Fatalf("expected failure for autonomous mode without verified signed policy in enterprise mode")
	}

	errOutput := stderr.String()
	if strings.Contains(errOutput, "ARTIX_ALLOW_AUTONOMOUS") {
		t.Errorf("CLI error message must NOT mention obsolete ARTIX_ALLOW_AUTONOMOUS, got: %s", errOutput)
	}
	if !strings.Contains(errOutput, "signed policy") && !strings.Contains(errOutput, "allowAutonomous") {
		t.Errorf("CLI error message must describe signed policy / allowAutonomous requirement, got: %s", errOutput)
	}

	// Verify README does not contain ARTIX_ALLOW_AUTONOMOUS
	readmeBytes, err := os.ReadFile("../README.md")
	if err == nil {
		if strings.Contains(string(readmeBytes), "ARTIX_ALLOW_AUTONOMOUS") {
			t.Errorf("README.md must NOT mention ARTIX_ALLOW_AUTONOMOUS")
		}
	}
}





