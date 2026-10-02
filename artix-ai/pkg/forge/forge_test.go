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
			"title": "Implement caching",
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
			"default_branch":     "main",
		},
		"object_attributes": map[string]string{
			"title":       "Fix auth timeout",
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
	worker := NewRemoteWorker(workDir, registry)

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
