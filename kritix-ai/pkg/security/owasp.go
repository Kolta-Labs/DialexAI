package security

import (
	"fmt"
	"regexp"
	"strings"

	"kritix/pkg/driver"
)

// VulnerabilityType classifies security defects.
type VulnerabilityType string

const (
	VulnSQLInjection     VulnerabilityType = "sql_injection"
	VulnXSS              VulnerabilityType = "xss"
	VulnPathTraversal    VulnerabilityType = "path_traversal"
	VulnSecretLeak       VulnerabilityType = "secret_leak"
	VulnPIILeak          VulnerabilityType = "pii_leak"
	VulnVerboseErrorLeak VulnerabilityType = "verbose_error_leak"
)

// Vulnerability records a discovered security flaw.
type Vulnerability struct {
	ID          string            `json:"id"`
	Type        VulnerabilityType `json:"type"`
	Severity    string            `json:"severity"` // "critical", "high", "medium", "low"
	URL         string            `json:"url"`
	Evidence    string            `json:"evidence"`
	Remedy      string            `json:"remedy"`
	OWASPRef    string            `json:"owasp_ref"`
}

// Regex patterns for security auditing
var (
	reCreditCard = regexp.MustCompile(`\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|3[47][0-9]{13})\b`)
	reAWSKey     = regexp.MustCompile(`\bAKIA[0-9A-Z]{16}\b`)
	reJWT        = regexp.MustCompile(`eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}`)
	reStackTrace = regexp.MustCompile(`(?i)(Exception in thread|Traceback \(most recent call last\)|at [a-zA-Z0-9_.]+\([a-zA-Z0-9_]+.java:\d+\))`)
)

// AuditNetworkEvent inspects HTTP traffic for sensitive leaks and verbose error disclosures.
func AuditNetworkEvent(event driver.NetworkEvent, responseBody string) []Vulnerability {
	var vulns []Vulnerability

	// 1. Audit URL for JWT tokens exposed in query string (OWASP A02: Cryptographic Failures)
	if reJWT.MatchString(event.URL) {
		vulns = append(vulns, Vulnerability{
			ID:       "KRITIX-SEC-JWT-URL",
			Type:     VulnSecretLeak,
			Severity: "high",
			URL:      event.URL,
			Evidence: "JWT token detected inside URL query parameters (vulnerable to browser history and proxy log leakage)",
			Remedy:   "Pass authentication tokens via Authorization: Bearer header or secure HttpOnly cookies",
			OWASPRef: "A02:2021-Cryptographic Failures",
		})
	}

	// 2. Audit payload for AWS Secret / Access Keys
	if match := reAWSKey.FindString(responseBody); match != "" {
		vulns = append(vulns, Vulnerability{
			ID:       "KRITIX-SEC-AWS-KEY",
			Type:     VulnSecretLeak,
			Severity: "critical",
			URL:      event.URL,
			Evidence: fmt.Sprintf("Hardcoded AWS Access Key ID exposed in HTTP response body: %s", match),
			Remedy:   "Immediately rotate credentials and load secrets via IAM roles or secret managers",
			OWASPRef: "A07:2021-Identification and Authentication Failures",
		})
	}

	// 3. Audit payload for raw Credit Card PII
	if match := reCreditCard.FindString(responseBody); match != "" {
		vulns = append(vulns, Vulnerability{
			ID:       "KRITIX-SEC-PII-CARD",
			Type:     VulnPIILeak,
			Severity: "critical",
			URL:      event.URL,
			Evidence: fmt.Sprintf("Unmasked credit card PAN exposed in HTTP response: %s", maskCard(match)),
			Remedy:   "Mask credit card numbers to show only the last 4 digits (PCI-DSS compliance)",
			OWASPRef: "A02:2021-Cryptographic Failures",
		})
	}

	// 4. Audit for verbose server stack traces (OWASP A05: Security Misconfiguration)
	if reStackTrace.MatchString(responseBody) {
		vulns = append(vulns, Vulnerability{
			ID:       "KRITIX-SEC-VERBOSE-ERROR",
			Type:     VulnVerboseErrorLeak,
			Severity: "medium",
			URL:      event.URL,
			Evidence: "Raw backend stack trace exposed to client (revealing internal file paths and dependencies)",
			Remedy:   "Disable debug mode in production and return sanitized error responses with correlation IDs",
			OWASPRef: "A05:2021-Security Misconfiguration",
		})
	}

	return vulns
}

// GenerateOWASPFuzzPayloads returns DAST fuzzing payloads for automated injection testing.
func GenerateOWASPFuzzPayloads() map[VulnerabilityType][]string {
	return map[VulnerabilityType][]string{
		VulnSQLInjection: {
			"' OR '1'='1",
			"admin' --",
			"1; DROP TABLE users --",
			"1' UNION SELECT null, username, password FROM users --",
		},
		VulnXSS: {
			"<script>alert('xss')</script>",
			"\"><img src=x onerror=alert(1)>",
			"javascript:alert(1)",
		},
		VulnPathTraversal: {
			"../../../../etc/passwd",
			"..\\..\\..\\windows\\win.ini",
			"%2e%2e%2f%2e%2e%2fetc%2fpasswd",
		},
	}
}

func maskCard(card string) string {
	if len(card) < 4 {
		return "****"
	}
	return strings.Repeat("*", len(card)-4) + card[len(card)-4:]
}
