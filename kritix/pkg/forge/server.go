package forge

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// WebhookServerConfig defines configuration for the webhook daemon.
type WebhookServerConfig struct {
	ListenAddr    string
	GitHubSecret  string
	GitLabToken   string
	DefaultDomain string
	Worker        *RemoteWorker
}

// JobStatus tracks the state of an asynchronous worker run.
type JobStatus struct {
	ID        string              `json:"id"`
	Source    string              `json:"source"`
	Status    string              `json:"status"` // "queued", "running", "completed", "failed"
	CreatedAt time.Time           `json:"createdAt"`
	Result    *RemoteWorkerResult `json:"result,omitempty"`
	Error     string              `json:"error,omitempty"`
}

// WebhookServer handles incoming VCS webhook events and executes remote jobs.
type WebhookServer struct {
	cfg    WebhookServerConfig
	jobs   map[string]*JobStatus
	jobsMu sync.RWMutex
}

// NewWebhookServer creates a new webhook server.
func NewWebhookServer(cfg WebhookServerConfig) *WebhookServer {
	if cfg.DefaultDomain == "" {
		cfg.DefaultDomain = "backend_engineer"
	}
	return &WebhookServer{
		cfg:  cfg,
		jobs: make(map[string]*JobStatus),
	}
}

// Handler returns the http.Handler for routing webhook requests.
func (s *WebhookServer) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", s.handleHealthz)
	mux.HandleFunc("/webhook/github", s.handleGitHubWebhook)
	mux.HandleFunc("/webhook/gitlab", s.handleGitLabWebhook)
	mux.HandleFunc("/jobs", s.handleJobs)
	return mux
}

func (s *WebhookServer) handleHealthz(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"status":"ok"}`))
}

func (s *WebhookServer) handleGitHubWebhook(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "failed to read body", http.StatusBadRequest)
		return
	}

	// Validate secret if configured
	if s.cfg.GitHubSecret != "" {
		sig := r.Header.Get("X-Hub-Signature-256")
		if !verifyGitHubSignature(s.cfg.GitHubSecret, sig, body) {
			http.Error(w, "invalid signature", http.StatusUnauthorized)
			return
		}
	}

	eventType := r.Header.Get("X-GitHub-Event")
	if eventType == "ping" {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"msg":"pong"}`))
		return
	}

	// Parse issues event: opened or labeled with "kritix"
	var event struct {
		Action string `json:"action"`
		Issue  struct {
			Title string `json:"title"`
			Body  string `json:"body"`
		} `json:"issue"`
		Repository struct {
			CloneURL string `json:"clone_url"`
			Name     string `json:"name"`
			Owner    struct {
				Login string `json:"login"`
			} `json:"owner"`
			DefaultBranch string `json:"default_branch"`
		} `json:"repository"`
	}

	if err := json.Unmarshal(body, &event); err != nil {
		http.Error(w, "invalid json payload", http.StatusBadRequest)
		return
	}

	if event.Action != "opened" && event.Action != "labeled" {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"msg":"ignored event action"}`))
		return
	}

	jobID := fmt.Sprintf("gh-%d", time.Now().UnixNano())
	target := RemoteRepoTarget{
		CloneURL: event.Repository.CloneURL,
		Owner:    event.Repository.Owner.Login,
		Repo:     event.Repository.Name,
		Branch:   event.Repository.DefaultBranch,
	}

	task := &RemoteWorkerTask{
		Target: target,
		Auth: ForgeAuth{
			Type: ForgeGitHub,
		},
		Prompt: fmt.Sprintf("%s\n\n%s", event.Issue.Title, event.Issue.Body),
		Domain: s.cfg.DefaultDomain,
	}

	s.startJob(jobID, "github", task)

	w.WriteHeader(http.StatusAccepted)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"jobId":  jobID,
		"status": "queued",
	})
}

func (s *WebhookServer) handleGitLabWebhook(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	if s.cfg.GitLabToken != "" {
		token := r.Header.Get("X-Gitlab-Token")
		if token != s.cfg.GitLabToken {
			http.Error(w, "invalid gitlab token", http.StatusUnauthorized)
			return
		}
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "failed to read body", http.StatusBadRequest)
		return
	}

	var event struct {
		ObjectKind string `json:"object_kind"`
		Project    struct {
			GitHTTPURL        string `json:"git_http_url"`
			PathWithNamespace string `json:"path_with_namespace"`
			DefaultBranch     string `json:"default_branch"`
		} `json:"project"`
		ObjectAttributes struct {
			Title       string `json:"title"`
			Description string `json:"description"`
			Action      string `json:"action"`
		} `json:"object_attributes"`
	}

	if err := json.Unmarshal(body, &event); err != nil {
		http.Error(w, "invalid json payload", http.StatusBadRequest)
		return
	}

	if event.ObjectKind != "issue" || (event.ObjectAttributes.Action != "open" && event.ObjectAttributes.Action != "reopen") {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"msg":"ignored event"}`))
		return
	}

	parts := strings.Split(event.Project.PathWithNamespace, "/")
	owner := ""
	repo := ""
	if len(parts) >= 2 {
		owner = parts[0]
		repo = parts[1]
	}

	jobID := fmt.Sprintf("gl-%d", time.Now().UnixNano())
	target := RemoteRepoTarget{
		CloneURL: event.Project.GitHTTPURL,
		Owner:    owner,
		Repo:     repo,
		Branch:   event.Project.DefaultBranch,
	}

	task := &RemoteWorkerTask{
		Target: target,
		Auth: ForgeAuth{
			Type: ForgeGitLab,
		},
		Prompt: fmt.Sprintf("%s\n\n%s", event.ObjectAttributes.Title, event.ObjectAttributes.Description),
		Domain: s.cfg.DefaultDomain,
	}

	s.startJob(jobID, "gitlab", task)

	w.WriteHeader(http.StatusAccepted)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"jobId":  jobID,
		"status": "queued",
	})
}

func (s *WebhookServer) handleJobs(w http.ResponseWriter, r *http.Request) {
	s.jobsMu.RLock()
	defer s.jobsMu.RUnlock()

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s.jobs)
}

func (s *WebhookServer) startJob(id, source string, task *RemoteWorkerTask) {
	status := &JobStatus{
		ID:        id,
		Source:    source,
		Status:    "queued",
		CreatedAt: time.Now(),
	}

	s.jobsMu.Lock()
	s.jobs[id] = status
	s.jobsMu.Unlock()

	if s.cfg.Worker == nil {
		return
	}

	go func() {
		s.jobsMu.Lock()
		status.Status = "running"
		s.jobsMu.Unlock()

		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer cancel()

		res := s.cfg.Worker.Execute(ctx, task)

		s.jobsMu.Lock()
		status.Result = res
		if res != nil && res.Success {
			status.Status = "completed"
		} else {
			status.Status = "failed"
			if res != nil {
				status.Error = res.Error
			}
		}
		s.jobsMu.Unlock()
	}()
}

// GetJob returns status of a specific job.
func (s *WebhookServer) GetJob(id string) (*JobStatus, bool) {
	s.jobsMu.RLock()
	defer s.jobsMu.RUnlock()
	j, ok := s.jobs[id]
	return j, ok
}

func verifyGitHubSignature(secret, signatureHeader string, body []byte) bool {
	if !strings.HasPrefix(signatureHeader, "sha256=") {
		return false
	}
	expectedMAC := strings.TrimPrefix(signatureHeader, "sha256=")

	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	actualMAC := hex.EncodeToString(mac.Sum(nil))

	return hmac.Equal([]byte(expectedMAC), []byte(actualMAC))
}
