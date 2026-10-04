package security

import (
	"errors"
	"testing"
	"time"
)

func TestSignedTargetScopeLifecycle(t *testing.T) {
	secret := "enterprise_dast_signing_key_32bytes_long!"
	allowedPatterns := []string{".staging.internal.com", "localhost", "127.0.0.1"}

	// 1. Issue valid scope
	token, err := IssueSignedScope(secret, "scope-run-001", allowedPatterns, "secops-gate", 15*time.Minute)
	if err != nil {
		t.Fatalf("failed to issue signed scope: %v", err)
	}

	// 2. Validate valid target in scope
	validTargets := []string{
		"https://api.staging.internal.com/v1/users",
		"http://localhost:8080/graphql",
		"http://127.0.0.1:3000/api",
		"https://staging.internal.com/login",
	}
	for _, target := range validTargets {
		if err := VerifyAndValidateTarget(target, token, secret); err != nil {
			t.Errorf("expected target %s to be valid, got: %v", target, err)
		}
	}

	// 3. Reject target outside scope
	invalidTargets := []string{
		"https://production.internal.com/api",
		"https://evil-staging.internal.com",
		"https://api.staging.internal.com.attacker.com",
		"https://google.com",
	}
	for _, target := range invalidTargets {
		if err := VerifyAndValidateTarget(target, token, secret); err == nil {
			t.Errorf("expected target %s to be rejected as out of scope", target)
		} else if !errors.Is(err, ErrTargetOutOfScope) {
			t.Errorf("expected ErrTargetOutOfScope, got: %v", err)
		}
	}

	// 4. Reject tampered token / wrong secret
	if err := VerifyAndValidateTarget("https://api.staging.internal.com/v1", token, "wrong_secret_key_32bytes_long!!!!"); err == nil {
		t.Error("expected error with wrong secret key")
	} else if !errors.Is(err, ErrInvalidSignature) {
		t.Errorf("expected ErrInvalidSignature, got: %v", err)
	}

	// 5. Reject expired scope
	expiredToken, err := IssueSignedScope(secret, "scope-expired", allowedPatterns, "secops-gate", -1*time.Minute)
	if err != nil {
		t.Fatalf("failed to create expired token: %v", err)
	}
	if err := VerifyAndValidateTarget("https://api.staging.internal.com/v1", expiredToken, secret); err == nil {
		t.Error("expected error with expired token")
	} else if !errors.Is(err, ErrScopeExpired) {
		t.Errorf("expected ErrScopeExpired, got: %v", err)
	}
}
