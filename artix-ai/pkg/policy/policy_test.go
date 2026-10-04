package policy

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestPolicySignatureAndEnterpriseMode(t *testing.T) {
	tempDir := t.TempDir()
	policyFile := filepath.Join(tempDir, "policy.json")

	pol := Policy{
		EnterpriseMode:  true,
		AllowAutonomous: false,
		AuditLogPath:    filepath.Join(tempDir, "enterprise_audit.jsonl"),
	}

	data, err := json.Marshal(pol)
	if err != nil {
		t.Fatalf("failed to marshal policy: %v", err)
	}

	if err := os.WriteFile(policyFile, data, 0644); err != nil {
		t.Fatalf("failed to write policy: %v", err)
	}

	// Sign the policy file
	signingKey := "super-secret-enterprise-policy-key-32b"
	SetTrustedKey("corp-root", signingKey)
	if err := SignPolicyFile(policyFile, signingKey); err != nil {
		t.Fatalf("failed to sign policy: %v", err)
	}

	SetDefaultPolicyPath(policyFile)
	defer func() {
		SetDefaultPolicyPath("/etc/artix/policy.json")
		ResetCache()
	}()

	loaded, err := LoadPolicy(policyFile)
	if err != nil {
		t.Fatalf("failed to load verified policy: %v", err)
	}

	if !loaded.EnterpriseMode {
		t.Errorf("expected EnterpriseMode=true, got false")
	}

	// Test: Enterprise mode cannot be loosened by env vars
	t.Setenv("ARTIX_ENTERPRISE", "0")
	t.Setenv("KRITIX_ENTERPRISE", "0")
	if !IsEnterprise() {
		t.Errorf("IsEnterprise() should remain true when policy enforces enterprise mode despite ARTIX_ENTERPRISE=0")
	}

	// Test: Autonomous mode cannot be enabled by env var if policy disallows it
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "1")
	if IsAutonomousAllowed() {
		t.Errorf("IsAutonomousAllowed() must return false when policy forbids autonomy even if ARTIX_ALLOW_AUTONOMOUS=1")
	}

	// Test: Audit log path cannot be overridden by ARTIX_AUDIT_LOG in enterprise mode
	t.Setenv("ARTIX_AUDIT_LOG", "/tmp/hacked_audit.log")
	effPath := EffectiveAuditLogPath("/some/workspace")
	if effPath == "/tmp/hacked_audit.log" {
		t.Errorf("ARTIX_AUDIT_LOG override should be ignored in enterprise mode, got %s", effPath)
	}
	if effPath != pol.AuditLogPath {
		t.Errorf("expected policy audit log path %s, got %s", pol.AuditLogPath, effPath)
	}
}

func TestUnsignedPolicyRejectedIfNonRoot(t *testing.T) {
	tempDir := t.TempDir()
	policyFile := filepath.Join(tempDir, "untrusted_policy.json")

	pol := Policy{EnterpriseMode: true}
	data, _ := json.Marshal(pol)
	_ = os.WriteFile(policyFile, data, 0644)

	// Attempt to load without signature
	_, err := LoadPolicy(policyFile)
	if err == nil && os.Geteuid() != 0 {
		t.Fatalf("expected error loading unsigned non-root policy file, got nil")
	}
}
