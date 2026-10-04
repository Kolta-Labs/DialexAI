package policy

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"
)

// DefaultPolicyPath is the build-time default location for enterprise policy.
var DefaultPolicyPath = "/etc/artix/policy.json"

// TrustedSigningKeys holds keys trusted for policy signature verification.
var (
	trustedKeysMu sync.RWMutex
	trustedKeys   = make(map[string]string) // keyID -> secret / pubKey
)

// SetTrustedKey registers a trusted key for verifying signed policy files.
func SetTrustedKey(keyID, secret string) {
	trustedKeysMu.Lock()
	defer trustedKeysMu.Unlock()
	trustedKeys[keyID] = secret
}

// RemoteSinkConfig defines configuration for an external audit sink (HTTP or syslog).
type RemoteSinkConfig struct {
	Type     string            `json:"type"` // "http" or "syslog"
	Endpoint string            `json:"endpoint"`
	AuthKey  string            `json:"authKey,omitempty"`
	Headers  map[string]string `json:"headers,omitempty"`
}

// BudgetConfig defines financial and token caps.
type BudgetConfig struct {
	MaxStoryTokens int     `json:"maxStoryTokens,omitempty"`
	MaxTeamTokens  int     `json:"maxTeamTokens,omitempty"`
	MaxDayTokens   int     `json:"maxDayTokens,omitempty"`
	MaxStoryCost   float64 `json:"maxStoryCost,omitempty"` // USD
	MaxTeamCost    float64 `json:"maxTeamCost,omitempty"`  // USD
	MaxDayCost     float64 `json:"maxDayCost,omitempty"`   // USD
}

// ReviewerPolicyConfig defines constraints for adversarial reviewer models.
type ReviewerPolicyConfig struct {
	EnforceDisjointModelFamilies bool     `json:"enforceDisjointModelFamilies"`
	AllowedCriticFamilies        []string `json:"allowedCriticFamilies,omitempty"`
	RestrictedPaths              []string `json:"restrictedPaths,omitempty"`
}

// Policy specifies security, autonomy, audit, and resource constraints for Artix.
type Policy struct {
	EnterpriseMode   bool                 `json:"enterpriseMode"`
	AllowAutonomous  bool                 `json:"allowAutonomous"`
	AuditLogPath     string               `json:"auditLogPath,omitempty"`
	AuditSigningKey  string               `json:"auditSigningKey,omitempty"`
	AuditRemoteSinks []RemoteSinkConfig   `json:"auditRemoteSinks,omitempty"`
	Budget           BudgetConfig         `json:"budget,omitempty"`
	Reviewer         ReviewerPolicyConfig `json:"reviewer,omitempty"`
	Source           string               `json:"-"`
	IsVerified       bool                 `json:"-"`
}

var (
	policyMu     sync.RWMutex
	cachedPolicy *Policy
)

// SetDefaultPolicyPath overrides the default policy path (used for testing or build injection).
func SetDefaultPolicyPath(p string) {
	policyMu.Lock()
	defer policyMu.Unlock()
	DefaultPolicyPath = p
	cachedPolicy = nil
}

// ResetCache clears the cached policy.
func ResetCache() {
	policyMu.Lock()
	defer policyMu.Unlock()
	cachedPolicy = nil
}

// LoadPolicy reads and verifies policy from path.
// It enforces that the policy file must be root-owned (UID 0) or cryptographically signed.
func LoadPolicy(path string) (*Policy, error) {
	if path == "" {
		path = DefaultPolicyPath
	}

	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("policy file not found at %s: %w", path, err)
	}

	// Verify root-owned or signature
	isRoot := isFileRootOwned(info)
	sigValid, errSig := verifyPolicySignature(path)

	if !isRoot && !sigValid {
		return nil, fmt.Errorf("policy file at %s is untrusted: must be root-owned (UID 0) or cryptographically signed (sig error: %v)", path, errSig)
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read policy file: %w", err)
	}

	var p Policy
	if err := json.Unmarshal(data, &p); err != nil {
		return nil, fmt.Errorf("failed to parse policy JSON: %w", err)
	}

	p.Source = path
	p.IsVerified = true
	return &p, nil
}

func isFileRootOwned(info os.FileInfo) bool {
	if stat, ok := info.Sys().(*syscall.Stat_t); ok {
		return stat.Uid == 0
	}
	return false
}

func verifyPolicySignature(policyPath string) (bool, error) {
	sigPath := policyPath + ".sig"
	sigData, err := os.ReadFile(sigPath)
	if err != nil {
		return false, fmt.Errorf("signature file %s not found: %w", sigPath, err)
	}

	content, err := os.ReadFile(policyPath)
	if err != nil {
		return false, err
	}

	trustedKeysMu.RLock()
	defer trustedKeysMu.RUnlock()

	if len(trustedKeys) == 0 {
		// Check ARTIX_POLICY_SIGNING_KEY in environment for verification if available
		if k := os.Getenv("ARTIX_POLICY_SIGNING_KEY"); k != "" {
			mac := hmac.New(sha256.New, []byte(k))
			mac.Write(content)
			expected := hex.EncodeToString(mac.Sum(nil))
			if hmac.Equal([]byte(expected), []byte(string(sigData))) {
				return true, nil
			}
		}
		return false, errors.New("no trusted signing keys registered to verify policy signature")
	}

	for _, secret := range trustedKeys {
		mac := hmac.New(sha256.New, []byte(secret))
		mac.Write(content)
		expected := hex.EncodeToString(mac.Sum(nil))
		if hmac.Equal([]byte(expected), []byte(string(sigData))) {
			return true, nil
		}
	}

	return false, errors.New("policy signature does not match any trusted key")
}

// SignPolicyFile signs a policy file at policyPath using the given secret and writes policyPath.sig.
func SignPolicyFile(policyPath string, secret string) error {
	content, err := os.ReadFile(policyPath)
	if err != nil {
		return err
	}
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(content)
	sig := hex.EncodeToString(mac.Sum(nil))
	return os.WriteFile(policyPath+".sig", []byte(sig), 0644)
}

// Active returns the currently active policy. If no verified system policy file exists,
// it constructs a baseline policy from environment defaults.
func Active() *Policy {
	policyMu.RLock()
	if cachedPolicy != nil {
		p := *cachedPolicy
		policyMu.RUnlock()
		return &p
	}
	policyMu.RUnlock()

	policyMu.Lock()
	defer policyMu.Unlock()

	if cachedPolicy != nil {
		p := *cachedPolicy
		return &p
	}

	p, err := LoadPolicy(DefaultPolicyPath)
	if err != nil {
		// Fallback to environment variables when no system policy file is present
		isEnvEnterprise := os.Getenv("ARTIX_ENTERPRISE") == "1" || os.Getenv("KRITIX_ENTERPRISE") == "1"
		allowAuto := os.Getenv("ARTIX_ALLOW_AUTONOMOUS") == "1"
		cachedPolicy = &Policy{
			EnterpriseMode:  isEnvEnterprise,
			AllowAutonomous: allowAuto,
			Source:          "environment",
			IsVerified:      false,
		}
		res := *cachedPolicy
		return &res
	}

	cachedPolicy = p
	res := *cachedPolicy
	return &res
}

// IsEnterprise returns true if enterprise mode is enforced by verified policy or environment.
// If a verified enterprise policy is loaded with EnterpriseMode=true, environment variables CANNOT loosen it.
func IsEnterprise() bool {
	pol := Active()
	if pol.IsVerified && pol.EnterpriseMode {
		return true
	}
	return os.Getenv("ARTIX_ENTERPRISE") == "1" || os.Getenv("KRITIX_ENTERPRISE") == "1"
}

// IsAutonomousAllowed returns whether autonomous commits are permitted.
// If Enterprise Mode is active, autonomous mode is blocked unless explicitly enabled by policy.
// If verified policy explicitly forbids autonomy (AllowAutonomous=false), ARTIX_ALLOW_AUTONOMOUS cannot loosen it.
func IsAutonomousAllowed() bool {
	pol := Active()
	if pol.IsVerified {
		if !pol.AllowAutonomous {
			// Policy strictly forbids autonomy; environment variables CANNOT loosen this.
			return false
		}
		return true
	}

	// Unverified/Environment mode:
	isEnterprise := IsEnterprise() || os.Getenv("CI") != ""
	if isEnterprise {
		return os.Getenv("ARTIX_ALLOW_AUTONOMOUS") == "1"
	}
	return true
}

// EffectiveAuditLogPath returns the audit log destination.
// When Enterprise Mode is on, ARTIX_AUDIT_LOG env var is strictly ignored to prevent tampering.
func EffectiveAuditLogPath(workspaceDir string) string {
	pol := Active()
	if IsEnterprise() {
		if pol.IsVerified && pol.AuditLogPath != "" {
			return pol.AuditLogPath
		}
		if workspaceDir != "" {
			return filepath.Join(workspaceDir, ".artix", "audit.jsonl")
		}
		return ".artix/audit.jsonl"
	}

	if env := os.Getenv("ARTIX_AUDIT_LOG"); env != "" {
		return env
	}

	if workspaceDir != "" {
		return filepath.Join(workspaceDir, ".artix", "audit.jsonl")
	}
	return ".artix/audit.jsonl"
}
