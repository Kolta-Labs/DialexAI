package security

import (
	"testing"
)

func TestWAFSuppressionWindow(t *testing.T) {
	waf := NewWAFSuppressionWindow("enterprise-waf-secret-key-12345", "https://siem.internal/events")

	// 1. Generate token and inject headers
	headers := waf.InjectWAFHeaders(nil, "run-98765")
	if headers["X-Kritix-Security-Scan-Token"] == "" {
		t.Fatalf("expected X-Kritix-Security-Scan-Token header")
	}

	token := headers["X-Kritix-Security-Scan-Token"]

	// 2. Verify token
	if !waf.VerifyScanToken(token) {
		t.Errorf("expected token to verify successfully")
	}

	// 3. Tampered token rejected
	if waf.VerifyScanToken(token + "tampered") {
		t.Errorf("tampered token should be rejected")
	}

	// 4. SIEM notice generation
	notice := waf.CreateSIEMNotice("SCAN_START", "run-98765", "https://staging.internal/api", "10.0.4.15", 30)
	if notice.EventType != "SCAN_START" || notice.TestExecutionID != "run-98765" {
		t.Errorf("unexpected SIEM notice payload: %+v", notice)
	}
}
