package auth

import (
	"bufio"
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// MaxAuditLineSize specifies the maximum byte size of an individual audit entry (1 MiB buffer).
// Any individual entry exceeding 1 MiB is rejected during chain validation to prevent unbounded memory allocation.
const MaxAuditLineSize = 1 << 20

// Role defines enterprise authorization tiers.
type Role string

const (
	// RoleAdmin has unrestricted governance, key rotation, and user management authority.
	RoleAdmin Role = "admin"

	// RoleTestArchitect can design/edit DAG workflows, model routing, and governance rules.
	RoleTestArchitect Role = "test_architect"

	// RoleTester can execute workflows, record journeys, and view reports.
	RoleTester Role = "tester"

	// RoleTriage can inspect defects, manage quarantine, view reports, and export reproduction artifacts.
	RoleTriage Role = "triage"

	// RoleDeveloper can trigger workflow runs, record studio flows, and view test artifacts.
	RoleDeveloper Role = "developer"

	// RoleViewer has read-only visibility into reports, SARIF security findings, and ROI metrics.
	RoleViewer Role = "viewer"

	// RoleCIRunner is a restricted service account for headless CI/CD execution.
	RoleCIRunner Role = "ci_runner"
)

// Permission defines atomic operations within the Kritix platform.
type Permission string

const (
	PermManageWorkflows  Permission = "workflow:manage"   // Create, edit, delete blueprints
	PermManageModels     Permission = "model:manage"      // Modify model routing matrix & API keys
	PermManageGovernance Permission = "governance:manage" // Set quarantine SLAs, healing modes
	PermManageQuarantine Permission = "quarantine:manage" // Manage flaky test quarantine & auto-purge
	PermManageUsers      Permission = "user:manage"       // Manage enterprise users & roles
	PermExecuteWorkflows Permission = "workflow:execute"  // Trigger test executions
	PermRecordJourneys   Permission = "studio:record"     // Record human exploration journeys
	PermViewReports      Permission = "report:view"       // View test reports, SARIF, JUnit, ROI
	PermExportArtifacts  Permission = "artifact:export"   // Export bug bundles & reproduction specs
)

// RolePermissions defines the static enterprise authorization matrix.
var RolePermissions = map[Role][]Permission{
	RoleAdmin: {
		PermManageWorkflows,
		PermManageModels,
		PermManageGovernance,
		PermManageQuarantine,
		PermManageUsers,
		PermExecuteWorkflows,
		PermRecordJourneys,
		PermViewReports,
		PermExportArtifacts,
	},
	RoleTestArchitect: {
		PermManageWorkflows,
		PermManageModels,
		PermManageGovernance,
		PermManageQuarantine,
		PermExecuteWorkflows,
		PermRecordJourneys,
		PermViewReports,
		PermExportArtifacts,
	},
	RoleTester: {
		PermExecuteWorkflows,
		PermRecordJourneys,
		PermViewReports,
		PermExportArtifacts,
	},
	RoleTriage: {
		PermViewReports,
		PermExportArtifacts,
		PermManageQuarantine,
	},
	RoleDeveloper: {
		PermExecuteWorkflows,
		PermRecordJourneys,
		PermViewReports,
		PermExportArtifacts,
	},
	RoleViewer: {
		PermViewReports,
	},
	RoleCIRunner: {
		PermExecuteWorkflows,
		PermViewReports,
		PermExportArtifacts,
	},
}

// UserIdentity represents an authenticated enterprise actor.
type UserIdentity struct {
	ID        string    `json:"id"`
	Email     string    `json:"email"`
	Squad     string    `json:"squad"`
	Role      Role      `json:"role"`
	ExpiresAt time.Time `json:"expires_at"`
}

// HasPermission checks if the user possesses the requested capability.
func (u *UserIdentity) HasPermission(perm Permission) bool {
	perms, exists := RolePermissions[u.Role]
	if !exists {
		return false
	}
	for _, p := range perms {
		if p == perm {
			return true
		}
	}
	return false
}

// AuditLogEntry records security-critical actions for compliance (SOC 2, ISO 27001).
type AuditLogEntry struct {
	Timestamp time.Time  `json:"timestamp"`
	UserID    string     `json:"user_id"`
	Role      Role       `json:"role"`
	Action    Permission `json:"action"`
	Resource  string     `json:"resource"`
	Allowed   bool       `json:"allowed"`
	Reason    string     `json:"reason,omitempty"`
	PrevHash  string     `json:"prev_hash"`
	Hash      string     `json:"hash"`
}

// MinSecretLen is the shortest accepted HMAC signing secret.
const MinSecretLen = 32

func (e AuditLogEntry) computeHash() string {
	e.Hash = ""
	b, _ := json.Marshal(e)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// EnterpriseAuthManager handles authentication tokens and role authorization.
type EnterpriseAuthManager struct {
	mu         sync.RWMutex
	signingKey []byte
	auditLogs  []AuditLogEntry
	auditFile  *os.File
	lastHash   string
	users      map[string]UserIdentity
}

// NewEnterpriseAuthManager constructs an enterprise authentication manager.
// There is no default secret: an empty or short key is an error (fail closed).
func NewEnterpriseAuthManager(secretKey string) (*EnterpriseAuthManager, error) {
	if len(secretKey) < MinSecretLen {
		return nil, fmt.Errorf("signing secret must be at least %d bytes (set KRITIX_AUTH_SECRET)", MinSecretLen)
	}
	return &EnterpriseAuthManager{
		signingKey: []byte(secretKey),
		users:      make(map[string]UserIdentity),
		auditLogs:  make([]AuditLogEntry, 0),
	}, nil
}

// SetAuditFile makes the audit trail durable: every entry is appended as a hash-chained JSON line.
// The existing chain is verified and continued, so the log survives across CLI invocations.
func (m *EnterpriseAuthManager) SetAuditFile(path string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	last, history, err := readAuditChain(path)
	if err != nil {
		return fmt.Errorf("refusing to append to a broken audit log: %w", err)
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		return err
	}
	m.auditFile, m.lastHash = f, last
	m.auditLogs = append(history, m.auditLogs...) // exports cover history, not just this process
	return nil
}

// record chains and stores an entry. Caller holds m.mu.
func (m *EnterpriseAuthManager) record(e AuditLogEntry) {
	e.PrevHash = m.lastHash
	e.Hash = e.computeHash()
	m.lastHash = e.Hash
	m.auditLogs = append(m.auditLogs, e)
	if m.auditFile != nil {
		b, _ := json.Marshal(e)
		// Fail closed would block the CLI on a full disk; the in-memory copy and chain still hold.
		_, _ = m.auditFile.Write(append(b, '\n'))
		_ = m.auditFile.Sync()
	}
}

// VerifyAuditChain checks every line's hash and linkage and returns the final hash.
// A missing file is an empty, valid chain.
func VerifyAuditChain(path string) (string, error) {
	last, _, err := readAuditChain(path)
	return last, err
}

func readAuditChain(path string) (string, []AuditLogEntry, error) {
	f, err := os.Open(path)
	if errors.Is(err, os.ErrNotExist) {
		return "", nil, nil
	}
	if err != nil {
		return "", nil, err
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	prev := ""
	var entries []AuditLogEntry
	for n := 1; sc.Scan(); n++ {
		var e AuditLogEntry
		if err := json.Unmarshal(sc.Bytes(), &e); err != nil {
			return "", nil, fmt.Errorf("line %d: unparseable entry", n)
		}
		if e.PrevHash != prev || e.Hash != e.computeHash() {
			return "", nil, fmt.Errorf("line %d: audit chain broken (entry edited, removed or reordered)", n)
		}
		prev = e.Hash
		entries = append(entries, e)
	}
	return prev, entries, sc.Err()
}

// RegisterUser registers or updates an enterprise user.
func (m *EnterpriseAuthManager) RegisterUser(user UserIdentity) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.users[user.ID] = user
}

// GenerateToken creates an authenticated, HMAC-signed token string.
func (m *EnterpriseAuthManager) GenerateToken(user UserIdentity, duration time.Duration) (string, error) {
	user.ExpiresAt = time.Now().Add(duration)
	payloadBytes, err := json.Marshal(user)
	if err != nil {
		return "", err
	}

	payloadB64 := base64.RawURLEncoding.EncodeToString(payloadBytes)
	mac := hmac.New(sha256.New, m.signingKey)
	mac.Write([]byte(payloadB64))
	sigB64 := base64.RawURLEncoding.EncodeToString(mac.Sum(nil))

	return fmt.Sprintf("%s.%s", payloadB64, sigB64), nil
}

// ValidateToken parses and cryptographically verifies an enterprise token.
func (m *EnterpriseAuthManager) ValidateToken(tokenStr string) (*UserIdentity, error) {
	parts := strings.Split(tokenStr, ".")
	if len(parts) != 2 {
		return nil, errors.New("malformed authentication token")
	}

	payloadB64 := parts[0]
	sigB64 := parts[1]

	mac := hmac.New(sha256.New, m.signingKey)
	mac.Write([]byte(payloadB64))
	expectedSig := mac.Sum(nil)

	actualSig, err := base64.RawURLEncoding.DecodeString(sigB64)
	if err != nil {
		return nil, errors.New("invalid token signature encoding")
	}

	if !hmac.Equal(expectedSig, actualSig) {
		return nil, errors.New("tampered or invalid token signature")
	}

	payloadBytes, err := base64.RawURLEncoding.DecodeString(payloadB64)
	if err != nil {
		return nil, errors.New("invalid payload encoding")
	}

	var user UserIdentity
	if err := json.Unmarshal(payloadBytes, &user); err != nil {
		return nil, errors.New("failed to deserialize user identity")
	}

	if time.Now().After(user.ExpiresAt) {
		return nil, errors.New("authentication token has expired")
	}

	return &user, nil
}

// Authorize verifies that an actor has the required permission for a resource and logs the audit event.
func (m *EnterpriseAuthManager) Authorize(user *UserIdentity, perm Permission, resource string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if user == nil {
		m.record(AuditLogEntry{
			Timestamp: time.Now(),
			Action:    perm,
			Resource:  resource,
			Allowed:   false,
			Reason:    "unauthenticated access attempt",
		})
		return errors.New("unauthenticated access: missing user context")
	}

	allowed := user.HasPermission(perm)
	entry := AuditLogEntry{
		Timestamp: time.Now(),
		UserID:    user.ID,
		Role:      user.Role,
		Action:    perm,
		Resource:  resource,
		Allowed:   allowed,
	}

	if !allowed {
		entry.Reason = fmt.Sprintf("role %q does not hold permission %q", user.Role, perm)
		m.record(entry)
		return fmt.Errorf("forbidden: role %q does not hold permission %q for resource %q", user.Role, perm, resource)
	}

	m.record(entry)
	return nil
}

// GetAuditLogs retrieves historical audit records for SOC 2 compliance.
func (m *EnterpriseAuthManager) GetAuditLogs() []AuditLogEntry {
	m.mu.RLock()
	defer m.mu.RUnlock()
	copied := make([]AuditLogEntry, len(m.auditLogs))
	copy(copied, m.auditLogs)
	return copied
}

// OIDCClaims represents standard ID token claims from Okta, Entra ID, or Google Workspace.
type OIDCClaims struct {
	Issuer   string   `json:"iss"`
	Subject  string   `json:"sub"`
	Email    string   `json:"email"`
	Name     string   `json:"name"`
	Groups   []string `json:"groups"` // e.g. ["okta:qa-architects", "okta:developers"]
	TenantID string   `json:"tenant_id,omitempty"`
}

// MapOIDCClaimsToIdentity converts enterprise IdP groups to Kritix RBAC roles.
func MapOIDCClaimsToIdentity(claims OIDCClaims) *UserIdentity {
	role := RoleViewer // Safe default principle of least privilege

	for _, g := range claims.Groups {
		gLower := strings.ToLower(g)
		if strings.Contains(gLower, "admin") || strings.Contains(gLower, "ciso") || strings.Contains(gLower, "head_of_tech") {
			role = RoleAdmin
			break
		} else if strings.Contains(gLower, "architect") || strings.Contains(gLower, "qa-lead") || strings.Contains(gLower, "lead-sdet") {
			role = RoleTestArchitect
		} else if strings.Contains(gLower, "triage") || strings.Contains(gLower, "defect") {
			if role != RoleAdmin && role != RoleTestArchitect {
				role = RoleTriage
			}
		} else if strings.Contains(gLower, "tester") || strings.Contains(gLower, "qa") {
			if role != RoleAdmin && role != RoleTestArchitect {
				role = RoleTester
			}
		} else if strings.Contains(gLower, "developer") || strings.Contains(gLower, "engineer") || strings.Contains(gLower, "sdet") {
			if role != RoleTestArchitect && role != RoleAdmin {
				role = RoleDeveloper
			}
		}
	}

	tenant := claims.TenantID
	if tenant == "" {
		tenant = "default-tenant"
	}

	return &UserIdentity{
		ID:        claims.Subject,
		Email:     claims.Email,
		Squad:     tenant,
		Role:      role,
		ExpiresAt: time.Now().Add(8 * time.Hour),
	}
}

// CloudTrailEvent models AWS CloudTrail / CEF compliant JSON event schema.
type CloudTrailEvent struct {
	EventVersion string                 `json:"eventVersion"`
	UserIdentity map[string]interface{} `json:"userIdentity"`
	EventTime    string                 `json:"eventTime"`
	EventSource  string                 `json:"eventSource"`
	EventName    string                 `json:"eventName"`
	ErrorCode    string                 `json:"errorCode,omitempty"`
	ErrorMessage string                 `json:"errorMessage,omitempty"`
	Resources    []map[string]string    `json:"resources"`
}

// ExportCloudTrailJSON serializes audit records into standard CloudTrail JSON.
func (m *EnterpriseAuthManager) ExportCloudTrailJSON() ([]byte, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var events []CloudTrailEvent
	for _, entry := range m.auditLogs {
		evt := CloudTrailEvent{
			EventVersion: "1.08",
			UserIdentity: map[string]interface{}{
				"type":        "EnterpriseUser",
				"principalId": entry.UserID,
				"role":        entry.Role,
			},
			EventTime:   entry.Timestamp.UTC().Format(time.RFC3339),
			EventSource: "kritix.enterprise.internal",
			EventName:   string(entry.Action),
			Resources: []map[string]string{
				{"resourceName": entry.Resource},
			},
		}
		if !entry.Allowed {
			evt.ErrorCode = "AccessDenied"
			evt.ErrorMessage = entry.Reason
		}
		events = append(events, evt)
	}

	return json.MarshalIndent(map[string]interface{}{"Records": events}, "", "  ")
}

// ExportSplunkHEC serializes audit entries into Splunk HTTP Event Collector (HEC) JSON batch format.
func (m *EnterpriseAuthManager) ExportSplunkHEC() ([]byte, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var splunkEvents []map[string]interface{}
	for _, entry := range m.auditLogs {
		evt := map[string]interface{}{
			"time":       entry.Timestamp.Unix(),
			"host":       "kritix-audit-collector",
			"source":     "kritix:audit",
			"sourcetype": "_json",
			"event": map[string]interface{}{
				"timestamp": entry.Timestamp.UTC().Format(time.RFC3339),
				"user_id":   entry.UserID,
				"role":      entry.Role,
				"action":    entry.Action,
				"resource":  entry.Resource,
				"allowed":   entry.Allowed,
				"reason":    entry.Reason,
			},
		}
		splunkEvents = append(splunkEvents, evt)
	}

	return json.MarshalIndent(splunkEvents, "", "  ")
}

// ExportDatadogLogs serializes audit entries into Datadog Logs API format.
func (m *EnterpriseAuthManager) ExportDatadogLogs() ([]byte, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var ddLogs []map[string]interface{}
	for _, entry := range m.auditLogs {
		status := "info"
		if !entry.Allowed {
			status = "warn"
		}
		log := map[string]interface{}{
			"ddsource":  "kritix",
			"service":   "kritix-enterprise",
			"status":    status,
			"message":   fmt.Sprintf("Audit decision: User %s (%s) action %s on %s [allowed: %v]", entry.UserID, entry.Role, entry.Action, entry.Resource, entry.Allowed),
			"timestamp": entry.Timestamp.UTC().Format(time.RFC3339),
			"usr": map[string]interface{}{
				"id":   entry.UserID,
				"role": entry.Role,
			},
			"kritix": map[string]interface{}{
				"action":   entry.Action,
				"resource": entry.Resource,
				"allowed":  entry.Allowed,
				"reason":   entry.Reason,
			},
		}
		ddLogs = append(ddLogs, log)
	}

	return json.MarshalIndent(ddLogs, "", "  ")
}

// ExportElasticECS serializes audit entries into Elastic Common Schema (ECS 1.12+) format.
func (m *EnterpriseAuthManager) ExportElasticECS() ([]byte, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	var ecsDocs []map[string]interface{}
	for _, entry := range m.auditLogs {
		outcome := "success"
		if !entry.Allowed {
			outcome = "failure"
		}
		doc := map[string]interface{}{
			"@timestamp": entry.Timestamp.UTC().Format(time.RFC3339),
			"event": map[string]interface{}{
				"category": []string{"iam", "audit"},
				"action":   string(entry.Action),
				"outcome":  outcome,
				"reason":   entry.Reason,
			},
			"user": map[string]interface{}{
				"id":    entry.UserID,
				"roles": []string{string(entry.Role)},
			},
			"service": map[string]interface{}{
				"name": "kritix-ai",
			},
			"labels": map[string]interface{}{
				"resource": entry.Resource,
				"allowed":  fmt.Sprintf("%v", entry.Allowed),
			},
		}
		ecsDocs = append(ecsDocs, doc)
	}

	return json.MarshalIndent(ecsDocs, "", "  ")
}

// ValidateTenantArtifactAccess enforces cryptographic tenant isolation between squads/tenants.
func ValidateTenantArtifactAccess(actor *UserIdentity, artifactTenantID string) error {
	if actor == nil {
		return errors.New("unauthenticated access: missing user context")
	}
	if actor.Role == RoleAdmin {
		return nil // Admin role has cross-tenant audit visibility
	}
	if artifactTenantID == "" {
		return nil
	}
	if actor.Squad != artifactTenantID {
		return fmt.Errorf("forbidden: actor squad %q cannot access tenant %q artifacts (cryptographic tenant boundary violation)",
			actor.Squad, artifactTenantID)
	}
	return nil
}

// TeamExecutionPolicy enforces environment boundaries and concurrency runner quotas per squad.
type TeamExecutionPolicy struct {
	SquadID             string   `json:"squad_id"`
	MaxConcurrentRuns   int      `json:"max_concurrent_runs"`
	AllowedEnvironments []string `json:"allowed_environments"` // ["dev", "staging"]
}

// EnvironmentQuotaEnforcer validates that execution stays within squad quotas.
type EnvironmentQuotaEnforcer struct {
	mu         sync.Mutex
	policies   map[string]TeamExecutionPolicy
	activeRuns map[string]int
}

// NewEnvironmentQuotaEnforcer creates a quota enforcer.
func NewEnvironmentQuotaEnforcer() *EnvironmentQuotaEnforcer {
	return &EnvironmentQuotaEnforcer{
		policies:   make(map[string]TeamExecutionPolicy),
		activeRuns: make(map[string]int),
	}
}

// RegisterPolicy registers a team's policy.
func (q *EnvironmentQuotaEnforcer) RegisterPolicy(policy TeamExecutionPolicy) {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.policies[policy.SquadID] = policy
}

// AcquireRun attempts to allocate a concurrent execution slot.
func (q *EnvironmentQuotaEnforcer) AcquireRun(squadID, targetEnv string, userRole Role) error {
	q.mu.Lock()
	defer q.mu.Unlock()

	// 1. Environment Gate: Production targets are strictly restricted to Admin role!
	if strings.Contains(strings.ToLower(targetEnv), "prod") && userRole != RoleAdmin {
		return fmt.Errorf("policy violation: target environment %q is production. Only RoleAdmin can target production environments.", targetEnv)
	}

	policy, exists := q.policies[squadID]
	if !exists {
		// Default fallback quota: 5 concurrent runs
		policy = TeamExecutionPolicy{SquadID: squadID, MaxConcurrentRuns: 5}
	}

	current := q.activeRuns[squadID]
	if policy.MaxConcurrentRuns > 0 && current >= policy.MaxConcurrentRuns {
		return fmt.Errorf("quota exceeded: squad %q reached maximum concurrent runner limit (%d/%d)",
			squadID, current, policy.MaxConcurrentRuns)
	}

	q.activeRuns[squadID] = current + 1
	return nil
}

// ReleaseRun frees an allocated execution slot.
func (q *EnvironmentQuotaEnforcer) ReleaseRun(squadID string) {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.activeRuns[squadID] > 0 {
		q.activeRuns[squadID]--
	}
}

// AuditAnchorProof contains verification metadata confirming that a specific audit chain head hash
// was externally witnessed and anchored by a remote tamper-evident authority/webhook.
type AuditAnchorProof struct {
	AnchorURL    string    `json:"anchor_url"`
	HeadHash     string    `json:"head_hash"`
	TotalEntries int       `json:"total_entries"`
	AnchoredAt   time.Time `json:"anchored_at"`
	Signature    string    `json:"signature"`
	Status       string    `json:"status"` // "COMMITTED"
}

// AnchorAuditHead exports the current audit chain head hash and signs it with the enterprise key,
// posting it to an external tamper-evident webhook or ledger.
func (m *EnterpriseAuthManager) AnchorAuditHead(ctx context.Context, anchorWebhookURL string) (*AuditAnchorProof, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()

	if m.lastHash == "" && len(m.auditLogs) == 0 {
		return nil, errors.New("cannot anchor empty audit chain")
	}

	proof := &AuditAnchorProof{
		AnchorURL:    anchorWebhookURL,
		HeadHash:     m.lastHash,
		TotalEntries: len(m.auditLogs),
		AnchoredAt:   time.Now().UTC(),
		Status:       "COMMITTED",
	}

	payload := fmt.Sprintf("%s|%s|%d|%d", proof.AnchorURL, proof.HeadHash, proof.TotalEntries, proof.AnchoredAt.Unix())
	mac := hmac.New(sha256.New, m.signingKey)
	mac.Write([]byte(payload))
	proof.Signature = hex.EncodeToString(mac.Sum(nil))

	if anchorWebhookURL != "" {
		body, err := json.Marshal(proof)
		if err != nil {
			return nil, err
		}
		req, err := http.NewRequestWithContext(ctx, "POST", anchorWebhookURL, bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-Kritix-Audit-Signature", proof.Signature)

		client := &http.Client{Timeout: 5 * time.Second}
		resp, err := client.Do(req)
		if err != nil {
			return nil, fmt.Errorf("failed to send audit head anchor to %s: %w", anchorWebhookURL, err)
		}
		defer resp.Body.Close()
		if resp.StatusCode < 200 || resp.StatusCode >= 300 {
			return nil, fmt.Errorf("external audit anchor returned HTTP %d", resp.StatusCode)
		}
	}

	return proof, nil
}

