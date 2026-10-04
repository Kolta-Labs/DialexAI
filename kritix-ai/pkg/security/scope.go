package security

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"
)

var (
	ErrScopeExpired       = errors.New("signed DAST scope has expired")
	ErrInvalidSignature   = errors.New("invalid signature on DAST scope authorization")
	ErrTargetOutOfScope   = errors.New("target host is outside the cryptographically signed authorization scope")
	ErrInvalidTargetURL   = errors.New("invalid target URL for DAST scanning")
	ErrScopeMalformed     = errors.New("malformed signed scope token")
)

// SignedTargetScope represents a cryptographically verified security testing authorization scope.
type SignedTargetScope struct {
	ScopeID         string    `json:"scope_id"`
	AllowedPatterns []string  `json:"allowed_patterns"` // e.g. [".staging.example.com", "localhost", "127.0.0.1"]
	Issuer          string    `json:"issuer"`           // e.g. "secops-ci", "ciso-gate"
	IssuedAt        time.Time `json:"issued_at"`
	ExpiresAt       time.Time `json:"expires_at"`
}

func (s *SignedTargetScope) computeSignature(secretKey []byte) string {
	payload := fmt.Sprintf("%s|%s|%s|%d|%d",
		s.ScopeID,
		strings.Join(s.AllowedPatterns, ","),
		s.Issuer,
		s.IssuedAt.Unix(),
		s.ExpiresAt.Unix(),
	)
	mac := hmac.New(sha256.New, secretKey)
	mac.Write([]byte(payload))
	return hex.EncodeToString(mac.Sum(nil))
}

// IssueSignedScope creates a cryptographically signed scope token for DAST/fuzz operations.
func IssueSignedScope(secretKey string, scopeID string, allowedPatterns []string, issuer string, ttl time.Duration) (string, error) {
	if len(secretKey) < 16 {
		return "", errors.New("signing secret key must be at least 16 bytes")
	}
	if len(allowedPatterns) == 0 {
		return "", errors.New("at least one allowed pattern is required for a valid scope")
	}

	now := time.Now()
	scope := SignedTargetScope{
		ScopeID:         scopeID,
		AllowedPatterns: allowedPatterns,
		Issuer:          issuer,
		IssuedAt:        now,
		ExpiresAt:       now.Add(ttl),
	}

	sig := scope.computeSignature([]byte(secretKey))
	envelope := struct {
		Scope     SignedTargetScope `json:"scope"`
		Signature string            `json:"sig"`
	}{
		Scope:     scope,
		Signature: sig,
	}

	b, err := json.Marshal(envelope)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// VerifyAndValidateTarget validates whether rawURL is authorized under the signed token.
func VerifyAndValidateTarget(rawURL string, signedToken string, secretKey string) error {
	u, err := url.Parse(rawURL)
	if err != nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return fmt.Errorf("%w: %q", ErrInvalidTargetURL, rawURL)
	}

	if signedToken == "" {
		return errors.New("missing required signed scope authorization token")
	}

	data, err := base64.RawURLEncoding.DecodeString(signedToken)
	if err != nil {
		return fmt.Errorf("%w: base64 decode failed: %v", ErrScopeMalformed, err)
	}

	var envelope struct {
		Scope     SignedTargetScope `json:"scope"`
		Signature string            `json:"sig"`
	}
	if err := json.Unmarshal(data, &envelope); err != nil {
		return fmt.Errorf("%w: json unmarshal failed: %v", ErrScopeMalformed, err)
	}

	// Verify cryptographic signature
	expectedSig := envelope.Scope.computeSignature([]byte(secretKey))
	if !hmac.Equal([]byte(envelope.Signature), []byte(expectedSig)) {
		return ErrInvalidSignature
	}

	// Verify TTL expiration
	if time.Now().After(envelope.Scope.ExpiresAt) {
		return fmt.Errorf("%w: expired at %s", ErrScopeExpired, envelope.Scope.ExpiresAt.Format(time.RFC3339))
	}

	// Match target hostname against allowed patterns
	host := strings.ToLower(u.Hostname())
	for _, pat := range envelope.Scope.AllowedPatterns {
		pat = strings.ToLower(strings.TrimSpace(pat))
		if pat == "" {
			continue
		}
		// Exact match
		if host == strings.TrimPrefix(pat, ".") {
			return nil
		}
		// Subdomain suffix match (e.g. pat=".staging.example.com")
		if strings.HasPrefix(pat, ".") && strings.HasSuffix(host, pat) {
			return nil
		}
	}

	return fmt.Errorf("%w: host %q does not match signed scope patterns %v", ErrTargetOutOfScope, host, envelope.Scope.AllowedPatterns)
}
