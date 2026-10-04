package security

import (
	"strings"
	"testing"
)

func TestRedactSecrets(t *testing.T) {
	cases := []struct {
		name     string
		input    string
		contains string
		excludes string
	}{
		{
			name:     "Bearer token",
			input:    "GET /api/v1/data Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
			contains: "[REDACTED_TOKEN]",
			excludes: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
		},
		{
			name:     "Stripe API Key",
			input:    "Failed charge with key sk_live_51Abcd1234567890abcdef1234567890",
			contains: "[REDACTED_API_KEY]",
			excludes: "sk_live_51Abcd",
		},
		{
			name:     "AWS Access Key",
			input:    "S3 upload error: AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE",
			contains: "[REDACTED_API_KEY]",
			excludes: "AKIAIOSFODNN7EXAMPLE",
		},
		{
			name:     "Database URL credentials",
			input:    "Connected to postgres://postgres:super_secret_db_pass_123@db.prod.internal:5432/main",
			contains: "postgres://postgres:[REDACTED]@db.prod.internal:5432/main",
			excludes: "super_secret_db_pass_123",
		},
		{
			name:     "Private Key Block",
			input:    "Loaded key:\n-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA0Y...fake...key...content...\n-----END RSA PRIVATE KEY-----\nSuccessfully verified",
			contains: "[REDACTED_PRIVATE_KEY]",
			excludes: "fake...key...content",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			output := RedactSecrets(tc.input)
			if !strings.Contains(output, tc.contains) {
				t.Errorf("expected output to contain %q, got: %s", tc.contains, output)
			}
			if strings.Contains(output, tc.excludes) {
				t.Errorf("expected output NOT to contain %q, got: %s", tc.excludes, output)
			}
		})
	}
}
