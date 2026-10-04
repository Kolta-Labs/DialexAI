package auth

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRolePermissionMatrix(t *testing.T) {
	admin := UserIdentity{ID: "usr-admin", Role: RoleAdmin}
	architect := UserIdentity{ID: "usr-arch", Role: RoleTestArchitect}
	developer := UserIdentity{ID: "usr-dev", Role: RoleDeveloper}
	viewer := UserIdentity{ID: "usr-view", Role: RoleViewer}

	// 1. Workflow Management
	if !admin.HasPermission(PermManageWorkflows) {
		t.Errorf("admin should have PermManageWorkflows")
	}
	if !architect.HasPermission(PermManageWorkflows) {
		t.Errorf("architect should have PermManageWorkflows")
	}
	if developer.HasPermission(PermManageWorkflows) {
		t.Errorf("developer must NOT have PermManageWorkflows")
	}
	if viewer.HasPermission(PermManageWorkflows) {
		t.Errorf("viewer must NOT have PermManageWorkflows")
	}

	// 2. Model Management
	if !architect.HasPermission(PermManageModels) {
		t.Errorf("architect should have PermManageModels")
	}
	if developer.HasPermission(PermManageModels) {
		t.Errorf("developer must NOT have PermManageModels")
	}

	// 3. Execution
	if !developer.HasPermission(PermExecuteWorkflows) {
		t.Errorf("developer should have PermExecuteWorkflows")
	}
	if viewer.HasPermission(PermExecuteWorkflows) {
		t.Errorf("viewer must NOT have PermExecuteWorkflows")
	}
}

func TestEnterpriseAuthTokens(t *testing.T) {
	mgr := mustMgr(t, "enterprise-super-secret-key")
	devUser := UserIdentity{
		ID:    "dev-01",
		Email: "dev@company.com",
		Squad: "checkout",
		Role:  RoleDeveloper,
	}

	// 1. Generate valid token
	token, err := mgr.GenerateToken(devUser, 1*time.Hour)
	if err != nil {
		t.Fatalf("failed to generate token: %v", err)
	}

	// 2. Validate token
	validated, err := mgr.ValidateToken(token)
	if err != nil {
		t.Fatalf("failed to validate token: %v", err)
	}
	if validated.ID != "dev-01" || validated.Role != RoleDeveloper {
		t.Errorf("unexpected validated user data: %+v", validated)
	}

	// 3. Tampered token detection
	tamperedToken := token + "tamper"
	_, err = mgr.ValidateToken(tamperedToken)
	if err == nil {
		t.Errorf("expected error on tampered token, got nil")
	}

	// 4. Expired token
	expiredToken, _ := mgr.GenerateToken(devUser, -1*time.Second)
	_, err = mgr.ValidateToken(expiredToken)
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Errorf("expected expiration error, got %v", err)
	}
}

func TestAuthorizationAndAuditLogging(t *testing.T) {
	mgr := mustMgr(t, "audit-secret-key")
	dev := &UserIdentity{ID: "dev-42", Role: RoleDeveloper}
	architect := &UserIdentity{ID: "arch-07", Role: RoleTestArchitect}

	// Developer executing workflow -> ALLOWED
	err := mgr.Authorize(dev, PermExecuteWorkflows, "blueprint:pr-smoke-guard")
	if err != nil {
		t.Errorf("developer should be authorized to execute workflow: %v", err)
	}

	// Developer attempting to alter model routing -> FORBIDDEN
	err = mgr.Authorize(dev, PermManageModels, "router:matrix-config")
	if err == nil {
		t.Errorf("developer should be rejected when modifying model matrix")
	}

	// Architect altering model routing -> ALLOWED
	err = mgr.Authorize(architect, PermManageModels, "router:matrix-config")
	if err != nil {
		t.Errorf("architect should be authorized to manage models: %v", err)
	}

	// Check audit logs
	logs := mgr.GetAuditLogs()
	if len(logs) != 3 {
		t.Fatalf("expected 3 audit log entries, got %d", len(logs))
	}

	// Verify rejected log entry
	if logs[1].Allowed != false || logs[1].UserID != "dev-42" || logs[1].Action != PermManageModels {
		t.Errorf("incorrect rejection audit entry: %+v", logs[1])
	}
}

func TestOIDCClaimsMapping(t *testing.T) {
	// 1. Okta QA Architects group -> RoleTestArchitect
	archClaims := OIDCClaims{
		Subject: "okta-sub-101",
		Email:   "qa-lead@enterprise.com",
		Groups:  []string{"everyone", "okta:qa-architects"},
	}
	identity := MapOIDCClaimsToIdentity(archClaims)
	if identity.Role != RoleTestArchitect {
		t.Errorf("expected RoleTestArchitect for qa-architects group, got %s", identity.Role)
	}

	// 2. Default users -> RoleViewer
	guestClaims := OIDCClaims{
		Subject: "okta-sub-999",
		Email:   "auditor@compliance.org",
		Groups:  []string{"contractors"},
	}
	guest := MapOIDCClaimsToIdentity(guestClaims)
	if guest.Role != RoleViewer {
		t.Errorf("expected RoleViewer as safe default, got %s", guest.Role)
	}
}

func TestCloudTrailJSONExport(t *testing.T) {
	mgr := mustMgr(t, "cloudtrail-test-key")
	dev := &UserIdentity{ID: "dev-99", Role: RoleDeveloper}

	_ = mgr.Authorize(dev, PermExecuteWorkflows, "blueprint:pr-smoke-guard")
	_ = mgr.Authorize(dev, PermManageGovernance, "sla:quarantine") // forbidden

	ctBytes, err := mgr.ExportCloudTrailJSON()
	if err != nil {
		t.Fatalf("failed to export CloudTrail JSON: %v", err)
	}

	ctStr := string(ctBytes)
	if !strings.Contains(ctStr, `"eventVersion": "1.08"`) {
		t.Errorf("missing CloudTrail eventVersion 1.08")
	}
	if !strings.Contains(ctStr, `"errorCode": "AccessDenied"`) {
		t.Errorf("missing AccessDenied in CloudTrail audit log")
	}
}

func TestEnvironmentQuotaEnforcer(t *testing.T) {
	enforcer := NewEnvironmentQuotaEnforcer()
	enforcer.RegisterPolicy(TeamExecutionPolicy{
		SquadID:           "checkout-team",
		MaxConcurrentRuns: 2,
	})

	// Run 1: success
	if err := enforcer.AcquireRun("checkout-team", "staging", RoleDeveloper); err != nil {
		t.Fatalf("failed run 1: %v", err)
	}

	// Run 2: success
	if err := enforcer.AcquireRun("checkout-team", "staging", RoleDeveloper); err != nil {
		t.Fatalf("failed run 2: %v", err)
	}

	// Run 3: QUOTA EXCEEDED
	if err := enforcer.AcquireRun("checkout-team", "staging", RoleDeveloper); err == nil {
		t.Errorf("expected quota exceeded error on 3rd concurrent run")
	}

	// Release run 1 -> now can acquire
	enforcer.ReleaseRun("checkout-team")
	if err := enforcer.AcquireRun("checkout-team", "staging", RoleDeveloper); err != nil {
		t.Errorf("failed to acquire run after releasing slot: %v", err)
	}

	// Production environment gate: Developer blocked from targeting production!
	if err := enforcer.AcquireRun("checkout-team", "https://prod.app.com", RoleDeveloper); err == nil {
		t.Errorf("developer should be BLOCKED from targeting production environment")
	}

	// Release a slot so we are under quota limit
	enforcer.ReleaseRun("checkout-team")

	// Admin allowed to target production
	if err := enforcer.AcquireRun("checkout-team", "https://prod.app.com", RoleAdmin); err != nil {
		t.Errorf("admin should be permitted to target production: %v", err)
	}
}

func TestTesterAndTriageRoles(t *testing.T) {
	tester := UserIdentity{ID: "usr-tester", Role: RoleTester}
	triageUser := UserIdentity{ID: "usr-triage", Role: RoleTriage}

	if !tester.HasPermission(PermExecuteWorkflows) {
		t.Errorf("tester should have PermExecuteWorkflows")
	}
	if tester.HasPermission(PermManageGovernance) {
		t.Errorf("tester should not have PermManageGovernance")
	}

	if !triageUser.HasPermission(PermManageQuarantine) {
		t.Errorf("triage role should have PermManageQuarantine")
	}
	if triageUser.HasPermission(PermExecuteWorkflows) {
		t.Errorf("triage role should not have PermExecuteWorkflows")
	}

	// OIDC mapping test
	idTester := MapOIDCClaimsToIdentity(OIDCClaims{
		Subject: "usr-oidc-1",
		Email:   "tester@company.com",
		Groups:  []string{"okta:qa-testers"},
	})
	if idTester.Role != RoleTester {
		t.Errorf("expected RoleTester from group, got %s", idTester.Role)
	}

	idTriage := MapOIDCClaimsToIdentity(OIDCClaims{
		Subject: "usr-oidc-2",
		Email:   "triage@company.com",
		Groups:  []string{"okta:defect-triage"},
	})
	if idTriage.Role != RoleTriage {
		t.Errorf("expected RoleTriage from group, got %s", idTriage.Role)
	}
}

func TestSIEMAuditExporters(t *testing.T) {
	mgr := mustMgr(t, "siem-test-key")
	user := &UserIdentity{ID: "dev-99", Role: RoleDeveloper}

	_ = mgr.Authorize(user, PermExecuteWorkflows, "blueprint:pr-smoke-guard")
	_ = mgr.Authorize(user, PermManageUsers, "security:iam") // Rejected

	// 1. Splunk HEC Export
	splunkBytes, err := mgr.ExportSplunkHEC()
	if err != nil {
		t.Fatalf("failed to export Splunk HEC: %v", err)
	}
	if !strings.Contains(string(splunkBytes), `"sourcetype": "_json"`) ||
		!strings.Contains(string(splunkBytes), `"source": "kritix:audit"`) {
		t.Errorf("invalid Splunk HEC format: %s", string(splunkBytes))
	}

	// 2. Datadog Logs API Export
	ddBytes, err := mgr.ExportDatadogLogs()
	if err != nil {
		t.Fatalf("failed to export Datadog logs: %v", err)
	}
	if !strings.Contains(string(ddBytes), `"ddsource": "kritix"`) ||
		!strings.Contains(string(ddBytes), `"service": "kritix-enterprise"`) {
		t.Errorf("invalid Datadog logs format: %s", string(ddBytes))
	}

	// 3. Elastic Common Schema (ECS) Export
	ecsBytes, err := mgr.ExportElasticECS()
	if err != nil {
		t.Fatalf("failed to export Elastic ECS: %v", err)
	}
	if !strings.Contains(string(ecsBytes), `"@timestamp"`) ||
		!strings.Contains(string(ecsBytes), `"category": [`) {
		t.Errorf("invalid Elastic ECS format: %s", string(ecsBytes))
	}
}

func TestTenantArtifactIsolationAccess(t *testing.T) {
	squadAUser := &UserIdentity{ID: "dev-a", Squad: "squad-checkout", Role: RoleDeveloper}
	adminUser := &UserIdentity{ID: "admin-root", Squad: "security", Role: RoleAdmin}

	// Same squad -> allowed
	if err := ValidateTenantArtifactAccess(squadAUser, "squad-checkout"); err != nil {
		t.Errorf("expected access permitted for same squad: %v", err)
	}

	// Cross squad -> forbidden
	if err := ValidateTenantArtifactAccess(squadAUser, "squad-billing"); err == nil {
		t.Errorf("expected forbidden for cross-squad artifact access")
	}

	// Admin -> cross-squad allowed for auditing
	if err := ValidateTenantArtifactAccess(adminUser, "squad-billing"); err != nil {
		t.Errorf("expected admin permitted cross-squad: %v", err)
	}
}

func mustMgr(t *testing.T, name string) *EnterpriseAuthManager {
	t.Helper()
	m, err := NewEnterpriseAuthManager(name + "-0123456789-0123456789-0123456789")
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func TestEmptyOrShortSecretRejected(t *testing.T) {
	for _, s := range []string{"", "short"} {
		if _, err := NewEnterpriseAuthManager(s); err == nil {
			t.Fatalf("secret %q must be rejected", s)
		}
	}
}

func TestForgedTokenRejected(t *testing.T) {
	a, b := mustMgr(t, "a"), mustMgr(t, "b")
	tok, _ := a.GenerateToken(UserIdentity{ID: "x", Role: RoleAdmin}, time.Hour)
	if _, err := b.ValidateToken(tok); err == nil {
		t.Fatal("token signed with another key must fail")
	}
	if _, err := a.ValidateToken(tok); err != nil {
		t.Fatal(err)
	}
}

func TestAuditChainDurableAndTamperEvident(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "audit.log")
	m := mustMgr(t, "audit")
	if err := m.SetAuditFile(path); err != nil {
		t.Fatal(err)
	}
	u := &UserIdentity{ID: "u1", Role: RoleViewer}
	_ = m.Authorize(u, PermViewReports, "r1")
	_ = m.Authorize(u, PermManageModels, "r2")
	// A second process continues the same chain.
	m2 := mustMgr(t, "audit")
	if err := m2.SetAuditFile(path); err != nil {
		t.Fatal(err)
	}
	_ = m2.Authorize(nil, PermViewReports, "r3")
	if _, err := VerifyAuditChain(path); err != nil {
		t.Fatalf("chain should verify: %v", err)
	}
	raw, _ := os.ReadFile(path)
	tampered := strings.Replace(string(raw), `"allowed":false`, `"allowed":true`, 1)
	_ = os.WriteFile(path, []byte(tampered), 0o600)
	if _, err := VerifyAuditChain(path); err == nil {
		t.Fatal("edited entry must break the chain")
	}
	if err := mustMgr(t, "audit").SetAuditFile(path); err == nil {
		t.Fatal("must refuse to append to a broken log")
	}
}

func TestAnchorAuditHead(t *testing.T) {
	mgr := mustMgr(t, "audit-anchor-test-secret-32bytes!")
	u := UserIdentity{ID: "usr-admin-anchor", Role: RoleAdmin, Squad: "secops"}
	_ = mgr.Authorize(&u, PermManageWorkflows, "dag-1")
	_ = mgr.Authorize(&u, PermExecuteWorkflows, "dag-1")

	var receivedSignature string
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedSignature = r.Header.Get("X-Kritix-Audit-Signature")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"status":"anchored"}`))
	}))
	defer ts.Close()

	ctx := context.Background()
	proof, err := mgr.AnchorAuditHead(ctx, ts.URL)
	if err != nil {
		t.Fatalf("unexpected error anchoring audit head: %v", err)
	}

	if proof.HeadHash == "" {
		t.Error("expected non-empty HeadHash in proof")
	}
	if proof.TotalEntries != 2 {
		t.Errorf("expected 2 entries, got %d", proof.TotalEntries)
	}
	if receivedSignature == "" || receivedSignature != proof.Signature {
		t.Errorf("expected signature %s to match header %s", proof.Signature, receivedSignature)
	}
}

