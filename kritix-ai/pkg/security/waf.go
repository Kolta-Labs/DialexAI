package security

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"
)

// WAFSuppressionWindow coordinates with enterprise WAF/SIEM (Cloudflare, AWS WAF, Splunk, Datadog)
// to prevent automated fuzzing payloads from triggering high-severity SecOps security incidents.
type WAFSuppressionWindow struct {
	secretKey      []byte
	siemWebhookURL string
}

// NewWAFSuppressionWindow constructs a WAF coordinator.
func NewWAFSuppressionWindow(sharedSecret string, siemWebhookURL string) *WAFSuppressionWindow {
	if sharedSecret == "" {
		b := make([]byte, 32)
		if _, err := rand.Read(b); err != nil {
			panic(err)
		}
		sharedSecret = hex.EncodeToString(b) // random per process: tokens only verify in-process
	}
	return &WAFSuppressionWindow{
		secretKey:      []byte(sharedSecret),
		siemWebhookURL: siemWebhookURL,
	}
}

// SIEMNotice represents an audit event emitted to enterprise SIEM before/after fuzzing.
type SIEMNotice struct {
	EventTime       string `json:"event_time"`
	EventType       string `json:"event_type"` // "SCAN_START" or "SCAN_COMPLETE"
	TestExecutionID string `json:"test_execution_id"`
	TargetURL       string `json:"target_url"`
	RunnerIP        string `json:"runner_ip"`
	SuppressionTTL  int    `json:"suppression_ttl_minutes"`
	SignedToken     string `json:"signed_token"`
}

// GenerateScanToken creates a short-lived HMAC token injected into HTTP headers.
// Enterprise WAFs (e.g. AWS WAF custom header rule) allowlist matching tokens during the window.
func (w *WAFSuppressionWindow) GenerateScanToken(testExecutionID string, duration time.Duration) string {
	expiry := time.Now().Add(duration).Unix()
	payload := fmt.Sprintf("%s:%d", testExecutionID, expiry)

	mac := hmac.New(sha256.New, w.secretKey)
	mac.Write([]byte(payload))
	sig := hex.EncodeToString(mac.Sum(nil))

	return fmt.Sprintf("%s:%s", payload, sig)
}

// VerifyScanToken validates a scan token on the receiving boundary.
func (w *WAFSuppressionWindow) VerifyScanToken(tokenStr string) bool {
	segments := strings.Split(tokenStr, ":") // <testID>:<expiry>:<sig>
	if len(segments) != 3 {
		return false
	}
	var expiry int64
	if _, err := fmt.Sscanf(segments[1], "%d", &expiry); err != nil || time.Now().Unix() > expiry {
		return false
	}
	mac := hmac.New(sha256.New, w.secretKey)
	mac.Write([]byte(fmt.Sprintf("%s:%d", segments[0], expiry)))
	return hmac.Equal([]byte(segments[2]), []byte(hex.EncodeToString(mac.Sum(nil))))
}

// ValidateFuzzTarget refuses fuzzing against any host not on the allowlist (suffix match, e.g. ".staging.example.com").
// Default-deny: an empty allowlist rejects everything, so production domains can never be fuzzed by accident.
func ValidateFuzzTarget(rawURL string, allowedHostSuffixes []string) error {
	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return fmt.Errorf("invalid fuzz target %q", rawURL)
	}
	host := strings.ToLower(u.Hostname())
	for _, suf := range allowedHostSuffixes {
		suf = strings.ToLower(suf)
		if host == strings.TrimPrefix(suf, ".") || strings.HasSuffix(host, suf) && strings.HasPrefix(suf, ".") {
			return nil
		}
	}
	return fmt.Errorf("fuzz target %q is not on the allowed non-production host list", host)
}

// InjectWAFHeaders adds the signed bypass header to outbound HTTP fuzzing requests.
func (w *WAFSuppressionWindow) InjectWAFHeaders(headers map[string]string, testExecutionID string) map[string]string {
	if headers == nil {
		headers = make(map[string]string)
	}
	token := w.GenerateScanToken(testExecutionID, 30*time.Minute)
	headers["X-Kritix-Security-Scan-Token"] = token
	headers["X-Kritix-Scan-Notice"] = "AUTHORIZED-DAST-TESTING"
	return headers
}

// CreateSIEMNotice formats the SIEM start/complete event.
func (w *WAFSuppressionWindow) CreateSIEMNotice(eventType, testID, targetURL, runnerIP string, ttlMinutes int) SIEMNotice {
	return SIEMNotice{
		EventTime:       time.Now().UTC().Format(time.RFC3339),
		EventType:       eventType,
		TestExecutionID: testID,
		TargetURL:       targetURL,
		RunnerIP:        runnerIP,
		SuppressionTTL:  ttlMinutes,
		SignedToken:     w.GenerateScanToken(testID, time.Duration(ttlMinutes)*time.Minute),
	}
}

// AllowedTargetsFromEnv reads KRITIX_ALLOWED_TARGETS (comma-separated host suffixes). Unset means deny all.
func AllowedTargetsFromEnv() []string {
	var out []string
	for _, p := range strings.Split(os.Getenv("KRITIX_ALLOWED_TARGETS"), ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}
