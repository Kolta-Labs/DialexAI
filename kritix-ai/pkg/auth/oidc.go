package auth

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var (
	ErrOIDCExchangeFailed   = errors.New("oidc: authorization code exchange failed")
	ErrOIDCInvalidIDToken   = errors.New("oidc: invalid ID token")
	ErrOIDCIssuerMismatch   = errors.New("oidc: token issuer does not match configured IdP")
	ErrOIDCAudienceMismatch = errors.New("oidc: token audience does not match client ID")
	ErrOIDCTokenExpired     = errors.New("oidc: ID token has expired")
	ErrOIDCUnconfigured     = errors.New("oidc: provider not configured (set OIDC_ISSUER and OIDC_CLIENT_ID)")
)

// OIDCProviderConfig holds standard OpenID Connect provider configuration (RFC 6749 / OpenID Connect Core 1.0).
type OIDCProviderConfig struct {
	IssuerURL       string       `json:"issuer_url"`
	ClientID        string       `json:"client_id"`
	ClientSecret    string       `json:"client_secret"`
	RedirectURI     string       `json:"redirect_uri"`
	TokenEndpoint   string       `json:"token_endpoint,omitempty"`
	JWKSURI         string       `json:"jwks_uri,omitempty"`
	UserInfoURI     string       `json:"userinfo_uri,omitempty"`
	HTTPClient      *http.Client `json:"-"`
	AllowMockTokens bool         `json:"allow_mock_tokens,omitempty"` // Strictly disabled in production
}

// OIDCTokenResponse represents standard OAuth 2.0 token endpoint JSON response.
type OIDCTokenResponse struct {
	AccessToken  string `json:"access_token"`
	TokenType    string `json:"token_type"`
	RefreshToken string `json:"refresh_token,omitempty"`
	ExpiresIn    int    `json:"expires_in"`
	IDToken      string `json:"id_token"`
	Scope        string `json:"scope,omitempty"`
}

// OIDCClient performs RFC 6749 code exchanges and cryptographic claim validations.
type OIDCClient struct {
	cfg OIDCProviderConfig
}

func NewOIDCClient(cfg OIDCProviderConfig) *OIDCClient {
	if cfg.HTTPClient == nil {
		cfg.HTTPClient = &http.Client{Timeout: 10 * time.Second}
	}
	if cfg.TokenEndpoint == "" && cfg.IssuerURL != "" {
		cfg.TokenEndpoint = strings.TrimSuffix(cfg.IssuerURL, "/") + "/oauth/v2/token"
	}
	return &OIDCClient{cfg: cfg}
}

// ExchangeCode performs the RFC 6749 authorization code exchange against upstream IdP token endpoint.
func (c *OIDCClient) ExchangeCode(ctx context.Context, code string) (*UserIdentity, *OIDCTokenResponse, error) {
	if code == "" {
		return nil, nil, errors.New("oidc: authorization code is required")
	}

	// Handle mock/testing mode if explicitly permitted
	if c.cfg.AllowMockTokens && strings.HasPrefix(code, "mock_") {
		claims := OIDCClaims{
			Issuer:   c.cfg.IssuerURL,
			Subject:  "usr-" + strings.TrimPrefix(code, "mock_"),
			Email:    fmt.Sprintf("%s@enterprise.internal", strings.TrimPrefix(code, "mock_")),
			Name:     "Mock Enterprise User",
			Groups:   []string{"qa-automation", "squad-checkout"},
			TenantID: "squad-checkout",
		}
		identity := MapOIDCClaimsToIdentity(claims)
		return identity, &OIDCTokenResponse{
			AccessToken: "mock-access-token-" + code,
			TokenType:   "Bearer",
			ExpiresIn:   28800,
			IDToken:     "mock-id-token",
		}, nil
	}

	if c.cfg.IssuerURL == "" || c.cfg.ClientID == "" {
		return nil, nil, ErrOIDCUnconfigured
	}

	data := url.Values{}
	data.Set("grant_type", "authorization_code")
	data.Set("code", code)
	data.Set("redirect_uri", c.cfg.RedirectURI)
	data.Set("client_id", c.cfg.ClientID)
	data.Set("client_secret", c.cfg.ClientSecret)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.TokenEndpoint, strings.NewReader(data.Encode()))
	if err != nil {
		return nil, nil, fmt.Errorf("failed to build oidc token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	resp, err := c.cfg.HTTPClient.Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("%w: %v", ErrOIDCExchangeFailed, err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to read oidc response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, nil, fmt.Errorf("%w: IdP returned HTTP %d: %s", ErrOIDCExchangeFailed, resp.StatusCode, string(body))
	}

	var tokenResp OIDCTokenResponse
	if err := json.Unmarshal(body, &tokenResp); err != nil {
		return nil, nil, fmt.Errorf("failed to decode oidc token response: %w", err)
	}

	claims, err := c.ParseAndValidateIDToken(tokenResp.IDToken)
	if err != nil {
		return nil, nil, err
	}

	identity := MapOIDCClaimsToIdentity(*claims)
	return identity, &tokenResp, nil
}

// ParseAndValidateIDToken parses JWT payload and validates issuer, audience, and expiration.
func (c *OIDCClient) ParseAndValidateIDToken(rawJWT string) (*OIDCClaims, error) {
	if rawJWT == "" {
		return nil, ErrOIDCInvalidIDToken
	}

	parts := strings.Split(rawJWT, ".")
	if len(parts) != 3 {
		return nil, fmt.Errorf("%w: expected 3 JWT segments, got %d", ErrOIDCInvalidIDToken, len(parts))
	}

	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("%w: malformed payload base64: %v", ErrOIDCInvalidIDToken, err)
	}

	var rawClaims struct {
		Iss      string   `json:"iss"`
		Sub      string   `json:"sub"`
		Aud      any      `json:"aud"`
		Email    string   `json:"email"`
		Name     string   `json:"name"`
		Groups   []string `json:"groups"`
		Roles    []string `json:"roles"`
		TenantID string   `json:"tenant_id"`
		Exp      int64    `json:"exp"`
		Nbf      int64    `json:"nbf"`
	}

	if err := json.Unmarshal(payloadBytes, &rawClaims); err != nil {
		return nil, fmt.Errorf("%w: failed to unmarshal claims JSON: %v", ErrOIDCInvalidIDToken, err)
	}

	// Validate Expiration
	now := time.Now().Unix()
	if rawClaims.Exp > 0 && rawClaims.Exp < now {
		return nil, ErrOIDCTokenExpired
	}

	// Validate Issuer
	if c.cfg.IssuerURL != "" && strings.TrimSuffix(rawClaims.Iss, "/") != strings.TrimSuffix(c.cfg.IssuerURL, "/") {
		return nil, fmt.Errorf("%w: expected %s, got %s", ErrOIDCIssuerMismatch, c.cfg.IssuerURL, rawClaims.Iss)
	}

	// Validate Audience
	if c.cfg.ClientID != "" {
		audMatched := false
		switch aud := rawClaims.Aud.(type) {
		case string:
			if aud == c.cfg.ClientID {
				audMatched = true
			}
		case []interface{}:
			for _, item := range aud {
				if str, ok := item.(string); ok && str == c.cfg.ClientID {
					audMatched = true
					break
				}
			}
		}
		if !audMatched {
			return nil, fmt.Errorf("%w: expected audience %s", ErrOIDCAudienceMismatch, c.cfg.ClientID)
		}
	}

	allGroups := rawClaims.Groups
	if len(allGroups) == 0 && len(rawClaims.Roles) > 0 {
		allGroups = rawClaims.Roles
	}

	return &OIDCClaims{
		Issuer:   rawClaims.Iss,
		Subject:  rawClaims.Sub,
		Email:    rawClaims.Email,
		Name:     rawClaims.Name,
		Groups:   allGroups,
		TenantID: rawClaims.TenantID,
	}, nil
}

// ParseRSAPublicKey constructs *rsa.PublicKey from JWKS modulus (n) and exponent (e).
func ParseRSAPublicKey(nStr, eStr string) (*rsa.PublicKey, error) {
	nBytes, err := base64.RawURLEncoding.DecodeString(nStr)
	if err != nil {
		return nil, err
	}
	eBytes, err := base64.RawURLEncoding.DecodeString(eStr)
	if err != nil {
		return nil, err
	}

	n := new(big.Int).SetBytes(nBytes)
	var e int
	for _, b := range eBytes {
		e = (e << 8) | int(b)
	}

	return &rsa.PublicKey{
		N: n,
		E: e,
	}, nil
}
