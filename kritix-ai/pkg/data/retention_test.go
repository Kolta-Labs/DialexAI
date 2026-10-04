package data

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPIIScrubbing_ComprehensiveSeededPII(t *testing.T) {
	cases := []struct {
		input       string
		mustContain string
		mustNotHave string
	}{
		{
			input:       "User SSN is 123-45-6789 registered in DB.",
			mustContain: "[REDACTED_SSN]",
			mustNotHave: "123-45-6789",
		},
		{
			input:       "Charged customer card 4111-2222-3333-4444 on Stripe.",
			mustContain: "[REDACTED_CREDIT_CARD]",
			mustNotHave: "4111-2222-3333-4444",
		},
		{
			input:       "Contact primary developer at john.doe@enterprise.com for credentials.",
			mustContain: "[REDACTED_USER]@enterprise.com",
			mustNotHave: "john.doe@",
		},
		{
			input:       "Emergency contact phone number is +1 (555) 234-5678.",
			mustContain: "[REDACTED_PHONE]",
			mustNotHave: "234-5678",
		},
		{
			input:       `Configuration has api_key: "sk_live_1234567890abcdef1234567890"`,
			mustContain: "[REDACTED_SECRET]",
			mustNotHave: "sk_live_1234567890abcdef1234567890",
		},
	}

	for _, c := range cases {
		scrubbed := ScrubPII(c.input)
		if !strings.Contains(scrubbed, c.mustContain) {
			t.Errorf("expected %q in scrubbed output: %s", c.mustContain, scrubbed)
		}
		if strings.Contains(scrubbed, c.mustNotHave) {
			t.Errorf("found unredacted PII %q in output: %s", c.mustNotHave, scrubbed)
		}
	}
}

func TestPurgeExpiredArtifacts(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "retention_test_*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	oldFile := filepath.Join(tempDir, "screenshot_old.png")
	newFile := filepath.Join(tempDir, "screenshot_new.png")

	_ = os.WriteFile(oldFile, []byte("old"), 0644)
	_ = os.WriteFile(newFile, []byte("new"), 0644)

	// Backdate old file by 10 days
	oldTime := time.Now().Add(-10 * 24 * time.Hour)
	_ = os.Chtimes(oldFile, oldTime, oldTime)

	purged, err := PurgeExpiredArtifacts(tempDir, 7*24*time.Hour)
	if err != nil {
		t.Fatalf("failed to purge artifacts: %v", err)
	}
	if purged != 1 {
		t.Errorf("expected 1 file purged, got %d", purged)
	}

	if _, err := os.Stat(oldFile); !os.IsNotExist(err) {
		t.Errorf("expected old file to be deleted")
	}
	if _, err := os.Stat(newFile); err != nil {
		t.Errorf("expected new file to still exist")
	}
}
