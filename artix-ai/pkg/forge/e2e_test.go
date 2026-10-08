package forge

import (
	"context"
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"artix/pkg/coder"
	"artix/pkg/git"
	"artix/pkg/persona"
	"artix/pkg/policy"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
	"artix/pkg/steering"
)

func setupTestRepoForForge(t *testing.T) (string, *git.Driver) {
	tempDir, err := os.MkdirTemp("", "artix_forge_e2e_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	// Create test ed25519 audit signing key outside workspace under a protected .artix dir
	keysDir, err := os.MkdirTemp("", "artix_forge_test_keys")
	if err == nil {
		secDir := filepath.Join(keysDir, ".artix")
		_ = os.MkdirAll(secDir, 0700)
		pub, priv, _ := ed25519.GenerateKey(nil)
		keyPath := filepath.Join(secDir, "audit_ed25519.key")
		_ = os.WriteFile(keyPath, []byte(hex.EncodeToString(priv)), 0600)
		t.Setenv("ARTIX_AUDIT_PRIVATE_KEY_PATH", keyPath)
		t.Setenv("ARTIX_AUDIT_PUBLIC_KEY", hex.EncodeToString(pub))
	}

	_ = exec.Command("git", "init", tempDir).Run()
	_ = exec.Command("git", "-C", tempDir, "config", "user.email", "test@artix.ai").Run()
	_ = exec.Command("git", "-C", tempDir, "config", "user.name", "Artix Test").Run()

	_ = os.WriteFile(filepath.Join(tempDir, ".gitignore"), []byte(".artix/\n.kritix/\n"), 0644)
	file := filepath.Join(tempDir, "counter.txt")
	_ = os.WriteFile(file, []byte("0\n"), 0644)

	driver := git.NewDriver(tempDir)
	_, _ = driver.CommitAll("initial commit")
	return tempDir, driver
}

// TestR3_2_MockedGitHub_CoordinatorRun_FullE2E verifies that Coordinator.Run correctly integrates
// with a production ForgeVerifier calling a mocked GitHub server, succeeding on valid approvals
// and refusing on stale head, author self-approval, and 5xx errors.
func TestR3_2_MockedGitHub_CoordinatorRun_FullE2E(t *testing.T) {
	policy.ResetCache()
	defer policy.ResetCache()
	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "1")
	policy.SetActivePolicyForTest(&policy.Policy{
		EnterpriseMode:       true,
		AllowAutonomous:      true,
		RequireForgeApproval: true,
		AllowedTestCommands:  []string{"test -f counter.txt"},
		IsVerified:           true,
	})

	tempDir, driver := setupTestRepoForForge(t)
	defer os.RemoveAll(tempDir)

	headSHA, err := driver.HeadHash()
	if err != nil || headSHA == "" {
		t.Fatalf("failed to get head hash: %v", err)
	}

	repoCtx, err := repo.DetectContext(tempDir)
	if err != nil {
		t.Fatalf("failed to detect repo context: %v", err)
	}

	reg := persona.NewRegistry("")
	coderEngine, err := coder.NewDomainCoder("backend_engineer", reg)
	if err != nil {
		t.Fatalf("failed to create domain coder: %v", err)
	}
	advReviewer := reviewer.NewAdversarialReviewer(reg)
	advReviewer.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := coder.NewCoordinator(coderEngine, advReviewer, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "SPEC-GH-001",
		Title:        "Implement Feature",
		TestCommands: []string{"test -f counter.txt"},
	}

	// 1. Success case: Mocked GitHub returns APPROVED review from "alice-lead" matching candidate commit SHA
	ghServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/pulls/101") {
			// Find the current branch head dynamically
			curHead, _ := driver.HeadHash()
			_ = json.NewEncoder(w).Encode(map[string]any{
				"user": map[string]any{"login": "developer-bob", "type": "User"},
				"head": map[string]any{"sha": curHead},
			})
			return
		}
		if strings.HasSuffix(r.URL.Path, "/pulls/101/reviews") {
			curHead, _ := driver.HeadHash()
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{
					"id":        1,
					"user":      map[string]any{"login": "alice-lead", "type": "User"},
					"state":     "APPROVED",
					"commit_id": curHead,
				},
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer ghServer.Close()

	ghClient := NewGitHubClient(ForgeAuth{
		Type:    ForgeGitHub,
		Token:   "gh-secret-token",
		BaseURL: ghServer.URL,
	})

	target := &RemoteRepoTarget{Owner: "acme", Repo: "core"}
	verifier := NewGitHubVerifier(ghClient, target, 101)

	opts := &coder.LoopOptions{
		MaxRounds:     1,
		Autonomy:      coder.AutonomyAutonomous,
		ForgeVerifier: verifier,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, opts)
	if !res.Success {
		t.Fatalf("expected coordinator run with genuine GitHub forge approval to SUCCEED, got error: %s", res.Error)
	}
	if res.CommitHash == "" {
		t.Fatalf("expected commit hash to be non-empty upon autonomous convergence")
	}

	// 2. Stale Head SHA rejection
	staleServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/pulls/102") {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"user": map[string]any{"login": "developer-bob", "type": "User"},
				"head": map[string]any{"sha": "old-stale-commit-sha-00000000000000"},
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer staleServer.Close()

	staleClient := NewGitHubClient(ForgeAuth{Type: ForgeGitHub, Token: "tok", BaseURL: staleServer.URL})
	optsStale := &coder.LoopOptions{
		MaxRounds:     1,
		Autonomy:      coder.AutonomyAutonomous,
		ForgeVerifier: NewGitHubVerifier(staleClient, target, 102),
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-1\n+2\n"
		},
	}
	resStale := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, optsStale)
	if resStale.Success {
		t.Fatalf("expected stale commit to be REJECTED, but run succeeded")
	}
	if !strings.Contains(resStale.Error, "stale commit") && !strings.Contains(resStale.Error, "differs") {
		t.Fatalf("expected error mentioning stale commit, got: %s", resStale.Error)
	}

	// 3. Author Self-Approval rejection
	selfServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		currentHead, _ := driver.HeadHash()
		if strings.HasSuffix(r.URL.Path, "/pulls/103") {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"user": map[string]any{"login": "developer-bob", "type": "User"},
				"head": map[string]any{"sha": currentHead},
			})
			return
		}
		if strings.HasSuffix(r.URL.Path, "/pulls/103/reviews") {
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{
					"id":        1,
					"user":      map[string]any{"login": "developer-bob", "type": "User"}, // author approving self
					"state":     "APPROVED",
					"commit_id": currentHead,
				},
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer selfServer.Close()

	selfClient := NewGitHubClient(ForgeAuth{Type: ForgeGitHub, Token: "tok", BaseURL: selfServer.URL})
	optsSelf := &coder.LoopOptions{
		MaxRounds:     1,
		Autonomy:      coder.AutonomyAutonomous,
		ForgeVerifier: NewGitHubVerifier(selfClient, target, 103),
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-1\n+3\n"
		},
	}
	resSelf := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, optsSelf)
	if resSelf.Success {
		t.Fatalf("expected author self-approval to be REJECTED, but run succeeded")
	}
	if !strings.Contains(resSelf.Error, "author") && !strings.Contains(resSelf.Error, "cannot approve") {
		t.Fatalf("expected error mentioning author cannot approve, got: %s", resSelf.Error)
	}

	// 4. API 500 error rejection
	errServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "internal server error", http.StatusInternalServerError)
	}))
	defer errServer.Close()

	errClient := NewGitHubClient(ForgeAuth{Type: ForgeGitHub, Token: "tok", BaseURL: errServer.URL})
	optsErr := &coder.LoopOptions{
		MaxRounds:     1,
		Autonomy:      coder.AutonomyAutonomous,
		ForgeVerifier: NewGitHubVerifier(errClient, target, 104),
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-1\n+4\n"
		},
	}
	resErr := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, optsErr)
	if resErr.Success {
		t.Fatalf("expected API 500 failure to fail closed, but run succeeded")
	}
}

// TestR3_2_MockedGitLab_CoordinatorRun_FullE2E verifies that GitLab MR approvals work end-to-end with Coordinator.Run.
func TestR3_2_MockedGitLab_CoordinatorRun_FullE2E(t *testing.T) {
	policy.ResetCache()
	defer policy.ResetCache()
	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "1")
	policy.SetActivePolicyForTest(&policy.Policy{
		EnterpriseMode:       true,
		AllowAutonomous:      true,
		RequireForgeApproval: true,
		AllowedTestCommands:  []string{"test -f counter.txt"},
		IsVerified:           true,
	})

	tempDir, driver := setupTestRepoForForge(t)
	defer os.RemoveAll(tempDir)

	headSHA, err := driver.HeadHash()
	if err != nil || headSHA == "" {
		t.Fatalf("failed to get head hash: %v", err)
	}

	repoCtx, err := repo.DetectContext(tempDir)
	if err != nil {
		t.Fatalf("failed to detect repo context: %v", err)
	}

	reg := persona.NewRegistry("")
	coderEngine, _ := coder.NewDomainCoder("backend_engineer", reg)
	advReviewer := reviewer.NewAdversarialReviewer(reg)
	advReviewer.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := coder.NewCoordinator(coderEngine, advReviewer, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "SPEC-GL-001",
		Title:        "GitLab Integration",
		TestCommands: []string{"test -f counter.txt"},
	}

	// Mocked GitLab Server
	glServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/merge_requests/55") {
			curHead, _ := driver.HeadHash()
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id":     55,
				"iid":    55,
				"sha":    curHead,
				"state":  "opened",
				"author": map[string]any{"username": "dev-alice"},
			})
			return
		}
		if strings.HasSuffix(r.URL.Path, "/merge_requests/55/approvals") {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id":  55,
				"iid": 55,
				"approved_by": []map[string]any{
					{
						"user": map[string]any{"id": 99, "username": "sec-lead-charlie", "name": "Charlie"},
					},
				},
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer glServer.Close()

	glClient := NewGitLabClient(ForgeAuth{
		Type:    ForgeGitLab,
		Token:   "gl-pat-token",
		BaseURL: glServer.URL,
	})

	target := &RemoteRepoTarget{Owner: "acme-corp", Repo: "services"}
	verifier := NewGitLabVerifier(glClient, target, 55)

	opts := &coder.LoopOptions{
		MaxRounds:     1,
		Autonomy:      coder.AutonomyAutonomous,
		ForgeVerifier: verifier,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, opts)
	if !res.Success {
		t.Fatalf("expected coordinator run with genuine GitLab approval to SUCCEED, got error: %s", res.Error)
	}
	if res.CommitHash == "" {
		t.Fatalf("expected commit hash upon autonomous convergence")
	}
}

// TestR4_2_ApprovePRAtPreCommitHead_BotAddsCommit_RejectedUntilReapproved verifies that
// if human approved PR at pre-commit SHA X, but the bot generates a new commit Y,
// enterprise autonomous merge is rejected because the new commit Y is unapproved,
// preventing unapproved code from bypassing the human approval gate.
func TestR4_2_ApprovePRAtPreCommitHead_BotAddsCommit_RejectedUntilReapproved(t *testing.T) {
	policy.ResetCache()
	defer policy.ResetCache()
	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "1")
	policy.SetActivePolicyForTest(&policy.Policy{
		EnterpriseMode:       true,
		AllowAutonomous:      true,
		RequireForgeApproval: true,
		AllowedTestCommands:  []string{"test -f counter.txt"},
		IsVerified:           true,
	})

	tempDir, driver := setupTestRepoForForge(t)
	defer os.RemoveAll(tempDir)

	headSHA, err := driver.HeadHash()
	if err != nil {
		t.Fatalf("failed to get head hash: %v", err)
	}

	reg := persona.NewRegistry("")
	coderObj, _ := coder.NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := coder.NewCoordinator(coderObj, rev, driver, box)

	repoCtx := &repo.RepositoryContext{RootDir: tempDir}
	storySpec := &spec.StorySpec{
		ID:           "SPEC-GH-R4-2",
		Title:        "Post-commit Head Approval Enforcement",
		TestCommands: []string{"test -f counter.txt"},
	}

	// Forge server has approval for the PRE-commit headSHA X, but not the new bot commit Y
	ghServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/pulls/201") {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"user": map[string]any{"login": "developer-bob", "type": "User"},
				"head": map[string]any{"sha": headSHA}, // pre-commit head SHA
			})
			return
		}
		if strings.HasSuffix(r.URL.Path, "/pulls/201/reviews") {
			_ = json.NewEncoder(w).Encode([]map[string]any{
				{
					"id":        1,
					"user":      map[string]any{"login": "alice-lead", "type": "User"},
					"state":     "APPROVED",
					"commit_id": headSHA, // approved old SHA X
				},
			})
			return
		}
		http.NotFound(w, r)
	}))
	defer ghServer.Close()

	ghClient := NewGitHubClient(ForgeAuth{
		Type:    ForgeGitHub,
		Token:   "gh-secret-token",
		BaseURL: ghServer.URL,
	})

	target := &RemoteRepoTarget{Owner: "acme", Repo: "core"}
	verifier := NewGitHubVerifier(ghClient, target, 201)

	opts := &coder.LoopOptions{
		MaxRounds:     1,
		Autonomy:      coder.AutonomyAutonomous,
		ForgeVerifier: verifier,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+99\n"
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, opts)
	if res.Success {
		t.Fatalf("SECURITY VIOLATION (R4-2): coordinator converged and committed bot patch even though human approval was only on pre-commit SHA %s!", headSHA)
	}
	if !strings.Contains(res.Error, "stale") && !strings.Contains(res.Error, "differs") && !strings.Contains(res.Error, "approval") {
		t.Fatalf("expected error mentioning stale commit or approval on old head, got: %s", res.Error)
	}
}

// TestR5_2_AutonomousCandidatePushAndForgeApproval_E2E verifies that Coordinator.Run correctly
// exercises the push -> approve -> verify flow against a mock forge whose state is independent of local HEAD.
func TestR5_2_AutonomousCandidatePushAndForgeApproval_E2E(t *testing.T) {
	policy.ResetCache()
	defer policy.ResetCache()
	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "1")
	policy.SetActivePolicyForTest(&policy.Policy{
		EnterpriseMode:       true,
		AllowAutonomous:      true,
		RequireForgeApproval: true,
		AllowedTestCommands:  []string{"test -f counter.txt"},
		IsVerified:           true,
	})

	tempDir, driver := setupTestRepoForForge(t)
	defer os.RemoveAll(tempDir)

	headBefore, err := driver.HeadHash()
	if err != nil || headBefore == "" {
		t.Fatalf("failed to get head hash: %v", err)
	}

	repoCtx, err := repo.DetectContext(tempDir)
	if err != nil {
		t.Fatalf("failed to detect repo context: %v", err)
	}

	reg := persona.NewRegistry("")
	coderObj, _ := coder.NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := coder.NewCoordinator(coderObj, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "SPEC-GH-R5-2",
		Title:        "Push and Approve Candidate Commit",
		TestCommands: []string{"test -f counter.txt"},
	}

	// Mock forge holds independent PR state (independent of local HEAD)
	var remotePRHead string = "initial-unapproved-sha-111111"
	var remoteReviews []map[string]any

	ghServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.HasSuffix(r.URL.Path, "/pulls/301") {
			_ = json.NewEncoder(w).Encode(map[string]any{
				"user": map[string]any{"login": "developer-bob", "type": "User"},
				"head": map[string]any{"sha": remotePRHead},
			})
			return
		}
		if strings.HasSuffix(r.URL.Path, "/pulls/301/reviews") {
			_ = json.NewEncoder(w).Encode(remoteReviews)
			return
		}
		http.NotFound(w, r)
	}))
	defer ghServer.Close()

	ghClient := NewGitHubClient(ForgeAuth{
		Type:    ForgeGitHub,
		Token:   "gh-secret-token",
		BaseURL: ghServer.URL,
	})

	target := &RemoteRepoTarget{Owner: "acme", Repo: "core"}
	verifier := NewGitHubVerifier(ghClient, target, 301)

	// Subtest 1: Success when ForgePusher pushes candidate to PR branch and human approves that exact SHA
	pushedSHA := ""
	opts := &coder.LoopOptions{
		MaxRounds: 1,
		Autonomy:  coder.AutonomyAutonomous,
		ForgePusher: func(ctx context.Context, commitSHA string) error {
			pushedSHA = commitSHA
			// Update mock forge remote PR head to the pushed candidate SHA
			remotePRHead = commitSHA
			// Human approves the pushed commit on the forge
			remoteReviews = []map[string]any{
				{
					"id":        1,
					"user":      map[string]any{"login": "lead-alice", "type": "User"},
					"state":     "APPROVED",
					"commit_id": commitSHA,
				},
			}
			return nil
		},
		ForgeVerifier: verifier,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+42\n"
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, opts)
	if !res.Success {
		t.Fatalf("expected push->approve->verify flow to SUCCEED, got error: %s", res.Error)
	}
	if pushedSHA == "" || res.CommitHash != pushedSHA {
		t.Fatalf("expected candidate commit SHA %s to match pushed SHA %s", res.CommitHash, pushedSHA)
	}

	// Subtest 2: Refusal when ForgePusher fails or approval is on a different SHA
	remotePRHead = "unrelated-stale-sha-999999"
	remoteReviews = []map[string]any{
		{
			"id":        2,
			"user":      map[string]any{"login": "lead-alice", "type": "User"},
			"state":     "APPROVED",
			"commit_id": "unrelated-stale-sha-999999",
		},
	}
	optsRefusal := &coder.LoopOptions{
		MaxRounds:     1,
		Autonomy:      coder.AutonomyAutonomous,
		ForgeVerifier: verifier,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-42\n+43\n"
		},
	}
	resRefusal := coord.Run(context.Background(), storySpec, repoCtx, &steering.PersonaSteeringContext{}, &steering.PersonaSteeringContext{}, optsRefusal)
	if resRefusal.Success {
		t.Fatalf("expected unpushed / stale PR head to be REJECTED, but run succeeded")
	}
}

// TestR5_2_ResetHard_NeverResetsPreExistingUserCommit proves that ResetHard can never reset away
// a pre-existing user commit when the commit step produced nothing or failed, and never touches a tree
// the user is working in.
func TestR5_2_ResetHard_NeverResetsPreExistingUserCommit(t *testing.T) {
	tempDir, driver := setupTestRepoForForge(t)
	defer os.RemoveAll(tempDir)

	initialHead, err := driver.HeadHash()
	if err != nil || initialHead == "" {
		t.Fatalf("failed to get initial head: %v", err)
	}

	repoCtx := &repo.RepositoryContext{RootDir: tempDir}
	reg := persona.NewRegistry("")
	coderObj, _ := coder.NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := coder.NewCoordinator(coderObj, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "SPEC-GH-RESET-SAFETY",
		Title:        "Reset Safety Verification",
		TestCommands: []string{"test -f counter.txt"},
	}

	// 1. When patch application / test verification produces no commit
	optsNoCommit := &coder.LoopOptions{
		MaxRounds: 1,
		Autonomy:  coder.AutonomyAutonomous,
		MockPatchGen: func(round int, feedback string) string {
			return "invalid-patch-that-fails-to-apply"
		},
	}
	resNoCommit := coord.Run(context.Background(), storySpec, repoCtx, nil, nil, optsNoCommit)
	if resNoCommit.Success {
		t.Fatalf("expected invalid patch to fail")
	}

	curHead, err := driver.HeadHash()
	if err != nil {
		t.Fatalf("failed to get head hash after failed run: %v", err)
	}
	if curHead != initialHead {
		t.Fatalf("CRITICAL REGRESSION: pre-existing user commit was reset away! Expected HEAD=%s, got HEAD=%s", initialHead, curHead)
	}

	// 2. When candidate commit is made but forge approval is rejected
	optsForgeFail := &coder.LoopOptions{
		MaxRounds: 1,
		Autonomy:  coder.AutonomyAutonomous,
		ForgeVerifier: func(ctx context.Context, commitSHA string) (*policy.PRApproval, error) {
			return nil, fmt.Errorf("forge approval rejected by security team")
		},
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+777\n"
		},
	}
	resForgeFail := coord.Run(context.Background(), storySpec, repoCtx, nil, nil, optsForgeFail)
	if resForgeFail.Success {
		t.Fatalf("expected rejected forge approval to fail")
	}

	curHeadAfterForgeFail, err := driver.HeadHash()
	if err != nil {
		t.Fatalf("failed to get head hash after forge failure: %v", err)
	}
	if curHeadAfterForgeFail != initialHead {
		t.Fatalf("CRITICAL REGRESSION: candidate rollback destroyed pre-existing user commit! Expected HEAD=%s, got HEAD=%s", initialHead, curHeadAfterForgeFail)
	}
}


