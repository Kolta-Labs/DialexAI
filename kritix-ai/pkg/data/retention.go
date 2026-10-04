package data

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// RetentionPolicy defines data retention and automated purging rules under GDPR / SOC 2 CC6.1.
type RetentionPolicy struct {
	SessionStateTTL time.Duration `json:"session_state_ttl"` // Default 30 days
	ScreenshotsTTL  time.Duration `json:"screenshots_ttl"`   // Default 7 days
	HARLogsTTL      time.Duration `json:"har_logs_ttl"`      // Default 14 days
	PromptsTTL      time.Duration `json:"prompts_ttl"`       // Default 0s (never persisted unredacted)
}

// DefaultRetentionPolicy returns compliant enterprise defaults.
func DefaultRetentionPolicy() RetentionPolicy {
	return RetentionPolicy{
		SessionStateTTL: 30 * 24 * time.Hour,
		ScreenshotsTTL:  7 * 24 * time.Hour,
		HARLogsTTL:      14 * 24 * time.Hour,
		PromptsTTL:      0,
	}
}

// PurgeExpiredArtifacts walks an artifact directory and removes files exceeding the retention TTL.
func PurgeExpiredArtifacts(dir string, maxAge time.Duration) (int, error) {
	if dir == "" || maxAge <= 0 {
		return 0, nil
	}

	removed := 0
	cutoff := time.Now().Add(-maxAge)

	err := filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil || info.IsDir() {
			return nil
		}
		if info.ModTime().Before(cutoff) {
			if err := os.Remove(path); err == nil {
				removed++
			}
		}
		return nil
	})

	return removed, err
}

var (
	ssnRegex        = regexp.MustCompile(`\b\d{3}-\d{2}-\d{4}\b`)
	creditCardRegex = regexp.MustCompile(`\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b`)
	emailRegex      = regexp.MustCompile(`\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}\b`)
	phoneRegex      = regexp.MustCompile(`\b(?:\+?1[-. ]?)?\(?([0-9]{3})\)?[-. ]?([0-9]{3})[-. ]?([0-9]{4})\b`)
	apiKeyRegex     = regexp.MustCompile(`(?i)(api[_-]?key|bearer|token|secret|password)["'\s:=]+([a-zA-Z0-9_\-\.]{16,})`)
)

// ScrubPII removes sensitive PII and credentials from text before passing to LLM context or logs.
func ScrubPII(input string) string {
	if input == "" {
		return ""
	}

	res := ssnRegex.ReplaceAllString(input, "[REDACTED_SSN]")
	res = creditCardRegex.ReplaceAllString(res, "[REDACTED_CREDIT_CARD]")
	res = phoneRegex.ReplaceAllString(res, "[REDACTED_PHONE]")
	res = apiKeyRegex.ReplaceAllString(res, `$1: [REDACTED_SECRET]`)

	// Scrub emails while preserving domain for routing if needed
	res = emailRegex.ReplaceAllStringFunc(res, func(email string) string {
		parts := strings.Split(email, "@")
		if len(parts) == 2 {
			return "[REDACTED_USER]@" + parts[1]
		}
		return "[REDACTED_EMAIL]"
	})

	return res
}
