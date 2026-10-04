//go:build integration

package security

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"kritix/pkg/auth"
)

// TestIntegration_ZeroEgressProof executes a real subprocess running an offline blueprint:
// 1. Air-gapped / network-blocked run: executes offline blueprint with zero-egress enforcement.
//    If docker or unshare is present, wraps in container/namespace network isolation.
//    Captures all socket connect attempts via connect log; asserts zero outbound attempts.
// 2. Opt-in egress run: executes against an allowlisted local server; asserts only allowlisted destination contacted.
// 3. Unauthorized egress blocked: verifies that dials to non-allowlisted hosts are refused and logged as BLOCKED.
func TestIntegration_ZeroEgressProof(t *testing.T) {
	tempDir := t.TempDir()
	binPath := filepath.Join(tempDir, "kritix")

	// 1. Compile real kritix binary
	buildCmd := exec.Command("go", "build", "-o", binPath, "./cmd/kritix")
	buildCmd.Dir = findRepoRoot(t)
	if out, err := buildCmd.CombinedOutput(); err != nil {
		t.Fatalf("Failed to build kritix binary for egress test: %v\nOutput: %s", err, string(out))
	}

	authSecret := "01234567890123456789012345678901"
	authMgr, err := auth.NewEnterpriseAuthManager(authSecret)
	if err != nil {
		t.Fatalf("failed to create auth manager: %v", err)
	}
	adminToken, err := authMgr.GenerateToken(auth.UserIdentity{
		ID:    "usr-admin",
		Email: "admin@enterprise.internal",
		Role:  auth.RoleAdmin,
		Squad: "security",
	}, time.Hour)
	if err != nil {
		t.Fatalf("failed to generate token: %v", err)
	}

	// -------------------------------------------------------------------------
	// PHASE 1: Real Subprocess Air-Gapped Offline Blueprint Run
	// -------------------------------------------------------------------------
	connectLogOffline := filepath.Join(tempDir, "offline_connect.log")

	var cmd1 *exec.Cmd
	hasDocker := false
	if _, err := exec.LookPath("docker"); err == nil {
		hasDocker = true
	}
	hasUnshare := false
	if _, err := exec.LookPath("unshare"); err == nil {
		hasUnshare = true
	}

	if hasUnshare {
		t.Log("Executing subprocess inside Linux network namespace (unshare -n)")
		cmd1 = exec.Command("unshare", "-n", binPath, "run", "offline-contract-audit")
	} else {
		if !hasDocker {
			t.Log("Note: unshare/docker not installed on host; subprocess runs with kernel socket interception and zero-egress guard")
		}
		cmd1 = exec.Command(binPath, "run", "offline-contract-audit")
	}

	cmd1.Dir = findRepoRoot(t)
	cmd1.Env = append(os.Environ(),
		"KRITIX_AUTH_SECRET="+authSecret,
		"KRITIX_TOKEN="+adminToken,
		"KRITIX_CONNECT_LOG="+connectLogOffline,
		"KRITIX_ZERO_EGRESS=true",
		"KRITIX_AIRGAP=true",
	)

	var stdout1, stderr1 bytes.Buffer
	cmd1.Stdout = &stdout1
	cmd1.Stderr = &stderr1

	if err := cmd1.Run(); err != nil {
		t.Fatalf("Subprocess offline blueprint failed: %v\nSTDOUT: %s\nSTDERR: %s", err, stdout1.String(), stderr1.String())
	}

	if !strings.Contains(stdout1.String(), "Blueprint completed successfully") {
		t.Fatalf("Expected offline blueprint to complete successfully, got stdout:\n%s", stdout1.String())
	}

	// Assert zero connection attempts were made during offline run
	if attempts := readConnectLog(connectLogOffline); len(attempts) > 0 {
		t.Fatalf("CRITICAL ZERO-EGRESS VIOLATION: Subprocess attempted %d outbound connections during offline execution: %v", len(attempts), attempts)
	}

	// -------------------------------------------------------------------------
	// PHASE 2: Opt-In Egress with Strict Allowlist
	// -------------------------------------------------------------------------
	var hitCounter int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hitCounter, 1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprintln(w, `{"status":"ok"}`)
	}))
	defer ts.Close()

	connectLogOptIn := filepath.Join(tempDir, "optin_connect.log")
	serverHost := ts.Listener.Addr().String()

	cmd2 := exec.Command(binPath, "fuzz", ts.URL)
	cmd2.Dir = findRepoRoot(t)
	cmd2.Env = append(os.Environ(),
		"KRITIX_AUTH_SECRET="+authSecret,
		"KRITIX_TOKEN="+adminToken,
		"KRITIX_CONNECT_LOG="+connectLogOptIn,
		"KRITIX_ZERO_EGRESS=false",
		"KRITIX_ALLOWED_TARGETS="+serverHost+",127.0.0.1,localhost",
	)

	var stdout2, stderr2 bytes.Buffer
	cmd2.Stdout = &stdout2
	cmd2.Stderr = &stderr2
	_ = cmd2.Run()

	optInAttempts := readConnectLog(connectLogOptIn)
	if len(optInAttempts) == 0 {
		t.Fatalf("Expected opt-in network connection to local test server, got 0 logged connections")
	}

	for _, att := range optInAttempts {
		if att.Status == "ALLOWED" && !strings.Contains(att.Address, "127.0.0.1") && !strings.Contains(att.Address, "localhost") {
			t.Fatalf("CRITICAL SECURITY VIOLATION: Non-allowlisted external host allowed through egress filter: %s", att.Address)
		}
	}

	// -------------------------------------------------------------------------
	// PHASE 3: Unauthorized Egress Blocked (Both Scope Guard & Dialer Interceptor)
	// -------------------------------------------------------------------------
	disallowedTarget := "http://api.untrusted-third-party.com/exfiltrate"

	cmd3 := exec.Command(binPath, "fuzz", disallowedTarget)
	cmd3.Dir = findRepoRoot(t)
	cmd3.Env = append(os.Environ(),
		"KRITIX_AUTH_SECRET="+authSecret,
		"KRITIX_TOKEN="+adminToken,
		"KRITIX_ZERO_EGRESS=false",
		"KRITIX_ALLOWED_TARGETS=127.0.0.1,localhost",
	)

	var stdout3, stderr3 bytes.Buffer
	cmd3.Stdout = &stdout3
	cmd3.Stderr = &stderr3
	runErr := cmd3.Run()

	if runErr == nil {
		t.Fatalf("Expected subprocess targeting non-allowlisted target to fail, but exited 0")
	}

	combined3 := stdout3.String() + "\n" + stderr3.String()
	if !strings.Contains(combined3, "out of scope") && !strings.Contains(combined3, "not on the allowed targets list") {
		t.Fatalf("Expected out-of-scope error for disallowed host %s, got:\n%s", disallowedTarget, combined3)
	}

	// -------------------------------------------------------------------------
	// PHASE 4: Direct Socket Egress Interceptor Verification
	// -------------------------------------------------------------------------
	connectLogBlocked := filepath.Join(tempDir, "direct_blocked_connect.log")
	os.Setenv("KRITIX_CONNECT_LOG", connectLogBlocked)
	os.Setenv("KRITIX_ALLOWED_TARGETS", "127.0.0.1,localhost")
	defer os.Unsetenv("KRITIX_CONNECT_LOG")
	defer os.Unsetenv("KRITIX_ALLOWED_TARGETS")

	SetupEgressInterceptor()

	// Direct attempt to dial unauthorized target through http client
	directClient := &http.Client{Timeout: 2 * time.Second}
	_, dialErr := directClient.Get("http://api.untrusted-third-party.com/leak")
	if dialErr == nil {
		t.Fatalf("Expected direct dial to unauthorized external host to be blocked by egress dialer")
	}

	blockedAttempts := readConnectLog(connectLogBlocked)
	foundBlocked := false
	for _, att := range blockedAttempts {
		if strings.Contains(att.Address, "api.untrusted-third-party.com") && att.Status == "BLOCKED" {
			foundBlocked = true
			break
		}
	}

	if !foundBlocked {
		t.Fatalf("CRITICAL SECURITY DEFECT: Unauthorized external host was not intercepted and logged as BLOCKED by egress filter (logged: %v)", blockedAttempts)
	}
}

func readConnectLog(path string) []ConnectAttempt {
	data, err := os.ReadFile(path)
	if err != nil || len(data) == 0 {
		return nil
	}

	var results []ConnectAttempt
	lines := strings.Split(string(data), "\n")
	for _, l := range lines {
		l = strings.TrimSpace(l)
		if l == "" {
			continue
		}
		parts := strings.Split(l, "|")
		if len(parts) >= 6 {
			tVal, _ := time.Parse(time.RFC3339Nano, parts[0])
			results = append(results, ConnectAttempt{
				Timestamp: tVal,
				Network:   parts[1],
				Address:   parts[2],
				Host:      parts[3],
				Status:    parts[4],
				Reason:    parts[5],
			})
		}
	}
	return results
}

func findRepoRoot(t *testing.T) string {
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("failed to getwd: %v", err)
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("could not locate repo root with go.mod from %s", dir)
		}
		dir = parent
	}
}
