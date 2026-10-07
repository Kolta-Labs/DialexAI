package forge

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"artix/pkg/persona"
)

func TestGitHubClient_CreatePullRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.Header.Get("Authorization") != "Bearer test-gh-token" {
			t.Errorf("missing or invalid authorization header")
		}
		if r.URL.Path != "/repos/myorg/myrepo/pulls" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{
			"id": 10101,
			"number": 42,
			"title": "feat: Payment Gateway",
			"html_url": "https://github.com/myorg/myrepo/pull/42",
			"state": "open"
		}`))
	}))
	defer server.Close()

	client := NewGitHubClient(ForgeAuth{
		Type:    ForgeGitHub,
		Token:   "test-gh-token",
		BaseURL: server.URL,
	})

	target := &RemoteRepoTarget{
		Owner:  "myorg",
		Repo:   "myrepo",
		Branch: "main",
	}

	req := &PullRequestRequest{
		Title: "feat: Payment Gateway",
		Body:  "Detailed PR spec body",
		Head:  "feat/payment-gateway",
		Base:  "main",
	}

	resp, err := client.CreatePullRequest(target, req)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if resp.ID != 10101 || resp.Number != 42 || resp.State != "open" {
		t.Errorf("unexpected response: %+v", resp)
	}
	if resp.HTMLURL != "https://github.com/myorg/myrepo/pull/42" {
		t.Errorf("unexpected html url: %s", resp.HTMLURL)
	}
}

func TestGitLabClient_CreatePullRequest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.Header.Get("PRIVATE-TOKEN") != "test-gl-token" {
			t.Errorf("missing or invalid private token header")
		}
		if r.URL.Path != "/projects/mygroup/myproject/merge_requests" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{
			"id": 20202,
			"iid": 13,
			"title": "feat: Checkout Flow",
			"web_url": "https://gitlab.com/mygroup/myproject/-/merge_requests/13",
			"state": "opened"
		}`))
	}))
	defer server.Close()

	client := NewGitLabClient(ForgeAuth{
		Type:    ForgeGitLab,
		Token:   "test-gl-token",
		BaseURL: server.URL,
	})

	target := &RemoteRepoTarget{
		Owner:  "mygroup",
		Repo:   "myproject",
		Branch: "master",
	}

	req := &PullRequestRequest{
		Title: "feat: Checkout Flow",
		Body:  "Detailed MR spec body",
		Head:  "feat/checkout-flow",
		Base:  "master",
	}

	resp, err := client.CreatePullRequest(target, req)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if resp.ID != 20202 || resp.Number != 13 || resp.State != "opened" {
		t.Errorf("unexpected response: %+v", resp)
	}
	if resp.HTMLURL != "https://gitlab.com/mygroup/myproject/-/merge_requests/13" {
		t.Errorf("unexpected html url: %s", resp.HTMLURL)
	}
}

func TestWebhookServer_GitHub_PingAndIssue(t *testing.T) {
	ws := NewWebhookServer(WebhookServerConfig{
		DefaultDomain: "backend_engineer",
	})
	handler := ws.Handler()

	// 1. Ping event
	pingReq := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader([]byte(`{}`)))
	pingReq.Header.Set("X-GitHub-Event", "ping")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, pingReq)

	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 for ping, got %d", rec.Code)
	}

	// 2. Issue opened event
	issuePayload := map[string]interface{}{
		"action": "opened",
		"issue": map[string]string{
			"title": "/artix: Implement caching",
			"body":  "Add Redis caching to hot query paths",
		},
		"repository": map[string]interface{}{
			"clone_url": "https://github.com/org/repo.git",
			"name":      "repo",
			"owner": map[string]string{
				"login": "org",
			},
			"default_branch": "main",
		},
	}
	body, _ := json.Marshal(issuePayload)

	req := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader(body))
	req.Header.Set("X-GitHub-Event", "issues")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d: %s", rec.Code, rec.Body.String())
	}

	var res map[string]string
	_ = json.Unmarshal(rec.Body.Bytes(), &res)
	jobID := res["jobId"]
	if jobID == "" {
		t.Fatalf("missing jobId in response")
	}

	status, ok := ws.GetJob(jobID)
	if !ok || status.Status != "queued" {
		t.Errorf("unexpected job status: %+v", status)
	}
}

func TestWebhookServer_GitLab_Issue(t *testing.T) {
	ws := NewWebhookServer(WebhookServerConfig{
		GitLabToken:   "secret-gitlab-token",
		DefaultDomain: "backend_engineer",
	})
	handler := ws.Handler()

	issuePayload := map[string]interface{}{
		"object_kind": "issue",
		"project": map[string]string{
			"git_http_url":        "https://gitlab.com/group/proj.git",
			"path_with_namespace": "group/proj",
			"default_branch":      "main",
		},
		"object_attributes": map[string]string{
			"title":       "/artix: Fix auth timeout",
			"description": "Increase timeout from 5s to 30s",
			"action":      "open",
		},
	}
	body, _ := json.Marshal(issuePayload)

	// Without token -> 401
	badReq := httptest.NewRequest(http.MethodPost, "/webhook/gitlab", bytes.NewReader(body))
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, badReq)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 Unauthorized, got %d", rec.Code)
	}

	// With token -> 202
	goodReq := httptest.NewRequest(http.MethodPost, "/webhook/gitlab", bytes.NewReader(body))
	goodReq.Header.Set("X-Gitlab-Token", "secret-gitlab-token")
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, goodReq)

	if rec.Code != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted, got %d", rec.Code)
	}
}

func TestRemoteWorker_Execute_LocalSimulated(t *testing.T) {
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "1")
	remoteDir, err := os.MkdirTemp("", "kritix-sim-remote-*")
	if err != nil {
		t.Fatalf("failed to create temp remote dir: %v", err)
	}
	defer os.RemoveAll(remoteDir)

	workDir, err := os.MkdirTemp("", "kritix-worker-root-*")
	if err != nil {
		t.Fatalf("failed to create temp worker dir: %v", err)
	}
	defer os.RemoveAll(workDir)

	runCmd := func(dir string, name string, args ...string) {
		cmd := exec.Command(name, args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("cmd %s %v failed: %v\nOutput: %s", name, args, err, string(out))
		}
	}

	runCmd(remoteDir, "git", "init", "-b", "main")
	runCmd(remoteDir, "git", "config", "user.name", "Test Admin")
	runCmd(remoteDir, "git", "config", "user.email", "admin@test.local")

	// Set up basic Go project so test execution passes
	_ = os.WriteFile(filepath.Join(remoteDir, "go.mod"), []byte("module simrepo\n\ngo 1.22\n"), 0644)
	_ = os.WriteFile(filepath.Join(remoteDir, "sim_test.go"), []byte("package simrepo\nimport \"testing\"\nfunc TestDummy(t *testing.T){}\n"), 0644)
	_ = os.WriteFile(filepath.Join(remoteDir, "README.md"), []byte("# Simulated Repo\n"), 0644)
	runCmd(remoteDir, "git", "add", ".")
	runCmd(remoteDir, "git", "commit", "-m", "initial commit")

	forgeMock := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{
			"id": 9999,
			"number": 1,
			"title": "feat: Simulated PR",
			"html_url": "https://github.com/sim/repo/pull/1",
			"state": "open"
		}`))
	}))
	defer forgeMock.Close()

	registry := persona.NewRegistry("")
	worker := NewRemoteWorker(workDir, registry, "", "localhost", "127.0.0.1")
	worker.SetAllowInsecureLocalCloneForTest(true)

	task := &RemoteWorkerTask{
		Target: RemoteRepoTarget{
			CloneURL: remoteDir,
			Owner:    "sim",
			Repo:     "repo",
			Branch:   "main",
		},
		Auth: ForgeAuth{
			Type:    ForgeGitHub,
			Token:   "dummy-token",
			BaseURL: forgeMock.URL,
		},
		Prompt: "Add an architecture section to README.md",
		Domain: "backend_engineer",
		MockPatchGen: func(round int, feedback string) string {
			return `diff --git a/README.md b/README.md
--- a/README.md
+++ b/README.md
@@ -1,1 +1,3 @@
 # Simulated Repo
+
+## Architecture
`
		},
	}

	ctx := context.Background()
	result := worker.Execute(ctx, task)

	if !result.Success {
		t.Fatalf("remote worker failed: %s", result.Error)
	}
	if result.PullRequest == nil || result.PullRequest.Number != 1 {
		t.Errorf("expected PR number 1, got %+v", result.PullRequest)
	}
	if result.Spec == nil {
		t.Errorf("expected generated StorySpec, got nil")
	}
	if result.Branch == "" {
		t.Errorf("expected branch name to be set")
	}
}

func TestWebhookServer_ConcurrencyAndQueueCap(t *testing.T) {
	cfg := WebhookServerConfig{
		DefaultDomain:     "backend_engineer",
		MaxConcurrentJobs: 1,
		MaxQueuedJobs:     1,
		// Worker is nil so jobs stay in queued status
	}
	server := NewWebhookServer(cfg)
	handler := server.Handler()

	sendIssueWebhook := func() *httptest.ResponseRecorder {
		payload := []byte(`{
			"action": "opened",
			"issue": {
				"title": "/artix: Fix memory leak",
				"body": "Profile traces show unbounded slice growth"
			},
			"repository": {
				"clone_url": "https://github.com/myorg/myrepo.git",
				"name": "myrepo",
				"owner": {"login": "myorg"},
				"default_branch": "main"
			}
		}`)
		req := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader(payload))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-GitHub-Event", "issues")
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		return rec
	}

	// 1st request: capacity 2 -> 1 accepted
	rec1 := sendIssueWebhook()
	if rec1.Code != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted on job 1, got %d: %s", rec1.Code, rec1.Body.String())
	}

	// 2nd request: capacity 2 -> 2 accepted
	rec2 := sendIssueWebhook()
	if rec2.Code != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted on job 2, got %d: %s", rec2.Code, rec2.Body.String())
	}

	// 3rd request: capacity 2 exceeded -> 429 Too Many Requests
	rec3 := sendIssueWebhook()
	if rec3.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 Too Many Requests on job 3 exceeding limit, got %d: %s", rec3.Code, rec3.Body.String())
	}

	var errBody map[string]string
	if err := json.Unmarshal(rec3.Body.Bytes(), &errBody); err != nil || errBody["status"] != "rate_limited" {
		t.Errorf("expected rate_limited status in 429 response, got: %s", rec3.Body.String())
	}
}

func TestWebhookServer_DeliveryDeduplication(t *testing.T) {
	ws := NewWebhookServer(WebhookServerConfig{})
	handler := ws.Handler()

	payload := []byte(`{
		"action": "opened",
		"issue": {"title": "/artix: Fix bug", "body": "description"},
		"repository": {
			"clone_url": "https://github.com/myorg/myrepo.git",
			"name": "myrepo",
			"owner": {"login": "myorg"},
			"default_branch": "main"
		}
	}`)

	// First delivery
	req1 := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader(payload))
	req1.Header.Set("Content-Type", "application/json")
	req1.Header.Set("X-GitHub-Event", "issues")
	req1.Header.Set("X-GitHub-Delivery", "delivery-uuid-12345")
	rec1 := httptest.NewRecorder()
	handler.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusAccepted {
		t.Fatalf("expected 202 Accepted on first delivery, got %d", rec1.Code)
	}

	// Replay / redelivery with same X-GitHub-Delivery
	req2 := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader(payload))
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("X-GitHub-Event", "issues")
	req2.Header.Set("X-GitHub-Delivery", "delivery-uuid-12345")
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)

	if !strings.Contains(rec2.Body.String(), "duplicate delivery ID") {
		t.Errorf("expected 'duplicate delivery ID' message, got: %s", rec2.Body.String())
	}
}

func TestWebhookServer_StatePersistenceAndRecovery(t *testing.T) {
	storagePath := filepath.Join(t.TempDir(), "daemon_state.json")

	// Server 1: creates a job and processes a delivery
	ws1 := NewWebhookServer(WebhookServerConfig{
		StoragePath: storagePath,
	})
	handler1 := ws1.Handler()

	payload := []byte(`{
		"action": "opened",
		"issue": {"title": "/artix: Fix memory leak", "body": "description"},
		"repository": {
			"clone_url": "https://github.com/myorg/myrepo.git",
			"name": "myrepo",
			"owner": {"login": "myorg"},
			"default_branch": "main"
		}
	}`)

	req1 := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader(payload))
	req1.Header.Set("Content-Type", "application/json")
	req1.Header.Set("X-GitHub-Event", "issues")
	req1.Header.Set("X-GitHub-Delivery", "delivery-persist-abc")
	rec1 := httptest.NewRecorder()
	handler1.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusAccepted {
		t.Fatalf("expected 202 on server 1, got %d", rec1.Code)
	}

	var respBody map[string]string
	_ = json.Unmarshal(rec1.Body.Bytes(), &respBody)
	jobID := respBody["jobId"]

	// Ensure state was saved to disk
	if _, err := os.Stat(storagePath); err != nil {
		t.Fatalf("expected state file to exist at %s: %v", storagePath, err)
	}

	// Server 2: restarts using the same StoragePath
	ws2 := NewWebhookServer(WebhookServerConfig{
		StoragePath: storagePath,
	})
	handler2 := ws2.Handler()

	// 1. Verify job was restored
	restoredJob, ok := ws2.GetJob(jobID)
	if !ok || restoredJob == nil {
		t.Fatalf("expected job %s to be restored on server 2 restart", jobID)
	}
	if restoredJob.Source != "github" {
		t.Errorf("expected source 'github', got %s", restoredJob.Source)
	}

	// 2. Verify delivery deduplication was restored
	reqDup := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader(payload))
	reqDup.Header.Set("Content-Type", "application/json")
	reqDup.Header.Set("X-GitHub-Event", "issues")
	reqDup.Header.Set("X-GitHub-Delivery", "delivery-persist-abc")
	recDup := httptest.NewRecorder()
	handler2.ServeHTTP(recDup, reqDup)

	if recDup.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for persisted duplicate delivery, got %d", recDup.Code)
	}
	if !strings.Contains(recDup.Body.String(), "duplicate delivery ID") {
		t.Errorf("expected duplicate delivery rejection, got: %s", recDup.Body.String())
	}
}

func TestWebhookServer_RequiresSecretInEnterpriseMode(t *testing.T) {
	t.Setenv("ARTIX_ENTERPRISE", "1")
	ws := NewWebhookServer(WebhookServerConfig{})
	handler := ws.Handler()

	payload := []byte(`{"action":"opened","issue":{"title":"/artix: test","body":"test"}}`)
	req := httptest.NewRequest(http.MethodPost, "/webhook/github", bytes.NewReader(payload))
	req.Header.Set("X-GitHub-Event", "issues")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden when secret is missing in enterprise mode, got %d", rec.Code)
	}

	glReq := httptest.NewRequest(http.MethodPost, "/webhook/gitlab", bytes.NewReader(payload))
	glRec := httptest.NewRecorder()
	handler.ServeHTTP(glRec, glReq)
	if glRec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden for GitLab when token is missing in enterprise mode, got %d", glRec.Code)
	}
}

func TestRemoteWorker_RejectsUntrustedCloneHost(t *testing.T) {
	worker := NewRemoteWorker(t.TempDir(), nil, "github.com", "gitlab.com")
	task := &RemoteWorkerTask{
		Target: RemoteRepoTarget{
			CloneURL: "https://attacker.evil.com/malicious/repo.git",
			Owner:    "attacker",
			Repo:     "repo",
			Branch:   "main",
		},
		Auth: ForgeAuth{
			Type:  ForgeGitHub,
			Token: "secret-token-must-not-leak",
		},
		Prompt: "exfiltrate",
	}

	res := worker.Execute(context.Background(), task)
	if res.Success {
		t.Fatalf("expected clone to untrusted host to fail, but succeeded")
	}
	if !strings.Contains(res.Error, "clone refused: host \"attacker.evil.com\" is not in allowed clone hosts") {
		t.Fatalf("expected clone refused error, got: %s", res.Error)
	}
}

func TestWebhookServer_JobsEndpointAuthenticationAndTenantBinding(t *testing.T) {
	ws := NewWebhookServer(WebhookServerConfig{
		JobsAuthToken: "super-secret-admin-token",
	})
	handler := ws.Handler()

	// 1. Unauthenticated request -> 401
	req1 := httptest.NewRequest(http.MethodGet, "/jobs", nil)
	rec1 := httptest.NewRecorder()
	handler.ServeHTTP(rec1, req1)
	if rec1.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 Unauthorized for unauthenticated /jobs, got %d", rec1.Code)
	}

	// 2. Authenticated but missing tenant query -> 400
	req2 := httptest.NewRequest(http.MethodGet, "/jobs", nil)
	req2.Header.Set("Authorization", "Bearer super-secret-admin-token")
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusBadRequest {
		t.Errorf("expected 400 Bad Request when tenant is omitted in authenticated mode, got %d", rec2.Code)
	}

	// 3. Authenticated with tenant query -> 200
	req3 := httptest.NewRequest(http.MethodGet, "/jobs?tenant=myteam", nil)
	req3.Header.Set("Authorization", "Bearer super-secret-admin-token")
	rec3 := httptest.NewRecorder()
	handler.ServeHTTP(rec3, req3)
	if rec3.Code != http.StatusOK {
		t.Errorf("expected 200 OK for authenticated /jobs with tenant binding, got %d", rec3.Code)
	}
}

func TestRemoteWorker_BlocksNonHttpsAndMalformedURLs(t *testing.T) {
	workDir := t.TempDir()
	worker := NewRemoteWorker(workDir, nil, "", "github.com", "gitlab.com")

	forbiddenURLs := []string{
		"file:///etc/passwd",
		"/abs/local/path",
		"git@github.com:org/repo.git",
		"ext::sh%20-c%20touch%20/tmp/pwn",
		"http://github.com/org/repo.git",
		"ftp://github.com/org/repo.git",
	}

	for _, rawURL := range forbiddenURLs {
		task := &RemoteWorkerTask{
			Target: RemoteRepoTarget{
				CloneURL: rawURL,
				Owner:    "test",
				Repo:     "repo",
			},
		}
		res := worker.Execute(context.Background(), task)
		if res.Success {
			t.Errorf("expected URL %q to be blocked, but clone succeeded", rawURL)
		}
		if !strings.Contains(res.Error, "clone refused") {
			t.Errorf("expected 'clone refused' error for URL %q, got: %s", rawURL, res.Error)
		}
	}
}


