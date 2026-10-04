package security

import (
	"regexp"
	"strings"
)

var (
	// regexBearer matches Bearer tokens and JWTs
	bearerRegex = regexp.MustCompile(`(?i)(bearer\s+)([A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.?[A-Za-z0-9\-_=]*)`)
	// regexBasicAuth matches Basic auth headers
	basicAuthRegex = regexp.MustCompile(`(?i)(authorization:\s*basic\s+)([A-Za-z0-9+/=]+)`)
	// regexAPIKeys matches common provider API keys
	apiKeyPatterns = []*regexp.Regexp{
		regexp.MustCompile(`(?i)(api[_-]?key["'\s:=]+)(["']?[A-Za-z0-9_\-]{16,}["']?)`),
		regexp.MustCompile(`(?i)(secret[_-]?key["'\s:=]+)(["']?[A-Za-z0-9_\-]{16,}["']?)`),
		regexp.MustCompile(`(?i)(token["'\s:=]+)(["']?[A-Za-z0-9_\-]{20,}["']?)`),
		regexp.MustCompile(`(?i)(password["'\s:=]+)(["'][^"'\n\r]+["']|[^\s,;}{]+)`),
		regexp.MustCompile(`(sk_live_[0-9a-zA-Z]{24,})`),
		regexp.MustCompile(`(rk_live_[0-9a-zA-Z]{24,})`),
		regexp.MustCompile(`(pk_live_[0-9a-zA-Z]{24,})`),
		regexp.MustCompile(`(AKIA[0-9A-Z]{16})`),
		regexp.MustCompile(`(ghp_[0-9a-zA-Z]{36})`),
		regexp.MustCompile(`(lin_api_[0-9a-zA-Z]{32,})`),
		regexp.MustCompile(`(hvs\.[0-9a-zA-Z]{24,})`),
	}
	// regexURLCredentials matches username:password in URLs
	urlCredsRegex = regexp.MustCompile(`([a-zA-Z0-9+.-]+://)([^:]+):([^@]+)@`)
	// regexPrivateKeys matches RSA/EC/OpenSSH private key blocks
	privateKeyRegex = regexp.MustCompile(`-----BEGIN [A-Z ]+PRIVATE KEY-----[^-]+-----END [A-Z ]+PRIVATE KEY-----`)
)

// RedactSecrets scans a text string and redacts credentials, bearer tokens, API keys, and private keys.
func RedactSecrets(input string) string {
	if input == "" {
		return ""
	}

	result := input

	// Redact private keys
	result = privateKeyRegex.ReplaceAllString(result, "[REDACTED_PRIVATE_KEY]")

	// Redact URL credentials
	result = urlCredsRegex.ReplaceAllString(result, "${1}${2}:[REDACTED]@")

	// Redact Bearer tokens
	result = bearerRegex.ReplaceAllString(result, "${1}[REDACTED_TOKEN]")

	// Redact Basic auth
	result = basicAuthRegex.ReplaceAllString(result, "${1}[REDACTED_BASIC_AUTH]")

	// Redact known API key patterns
	for _, pat := range apiKeyPatterns {
		result = pat.ReplaceAllStringFunc(result, func(match string) string {
			// If it's a direct token match like sk_live_...
			if strings.HasPrefix(match, "sk_live_") || strings.HasPrefix(match, "rk_live_") ||
				strings.HasPrefix(match, "pk_live_") || strings.HasPrefix(match, "AKIA") ||
				strings.HasPrefix(match, "ghp_") || strings.HasPrefix(match, "lin_api_") ||
				strings.HasPrefix(match, "hvs.") {
				return "[REDACTED_API_KEY]"
			}

			// Key-value pair like apiKey: "..."
			parts := strings.SplitN(match, ":", 2)
			if len(parts) == 2 {
				return parts[0] + ": \"[REDACTED]\""
			}
			parts = strings.SplitN(match, "=", 2)
			if len(parts) == 2 {
				return parts[0] + "=\"[REDACTED]\""
			}
			return "[REDACTED]"
		})
	}

	return result
}
