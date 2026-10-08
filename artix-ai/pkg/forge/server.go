package forge

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"artix/pkg/policy"
	"artix/pkg/steering"
)

// WebhookServerConfig defines configuration for the webhook daemon.
type WebhookServerConfig struct {
	ListenAddr         string
	GitHubSecret       string
	GitLabToken        string
	RequireWebhookAuth bool
	AllowedCloneHosts  []string
	JobsAuthToken      string
	TriggerLabel       string
	AllowedUsers       []string
	DefaultDomain      string
	Worker             *RemoteWorker
	MaxConcurrentJobs  int
	MaxQueuedJobs      int
	MaxBodyLength      int
	JobTimeout         time.Duration
	StoragePath        string
}

// JobStatus tracks the state of an asynchronous worker run.
type JobStatus struct {
	ID        string              `json:"id"`
	Source    string              `json:"source"`
	Tenant    string              `json:"tenant"`
	Status    string              `json:"status"` // "queued", "running", "completed", "failed"
	CreatedAt time.Time           `json:"createdAt"`
	Result    *RemoteWorkerResult `json:"result,omitempty"`
	Error     string              `json:"error,omitempty"`
}

type daemonPersistentState struct {
	Jobs        map[string]*JobStatus `json:"jobs"`
	Deliveries  map[string]time.Time  `json:"deliveries"`
}

// WebhookServer handles incoming VCS webhook events and executes remote jobs.
type WebhookServer struct {
	cfg                 WebhookServerConfig
	jobs                map[string]*JobStatus
	jobsMu              sync.RWMutex
	sem                 chan struct{}
	processedDeliveries map[string]time.Time
	deliveriesMu        sync.Mutex
	branchLocks         map[string]*sync.Mutex
	branchLocksMu       sync.Mutex
	storageMu           sync.Mutex
}

// NewWebhookServer creates a new webhook server.
func NewWebhookServer(cfg WebhookServerConfig) *WebhookServer {
	if cfg.DefaultDomain == "" {
		cfg.DefaultDomain = "backend_engineer"
	}
	if cfg.JobTimeout <= 0 {
		if envTimeout := os.Getenv("ARTIX_JOB_TIMEOUT"); envTimeout != "" {
			if d, err := time.ParseDuration(envTimeout); err == nil && d > 0 {
				cfg.JobTimeout = d
			}
		}
		if cfg.JobTimeout <= 0 {
			cfg.JobTimeout = 10 * time.Minute
		}
	}
	if cfg.StoragePath == "" {
		if envStorage := os.Getenv("ARTIX_DAEMON_STORAGE"); envStorage != "" {
			cfg.StoragePath = envStorage
		}
	}
	if cfg.MaxConcurrentJobs <= 0 {
		if envVal := os.Getenv("ARTIX_MAX_CONCURRENT_JOBS"); envVal != "" {
			if n, err := strconv.Atoi(envVal); err == nil && n > 0 {
				cfg.MaxConcurrentJobs = n
			}
		}
		if cfg.MaxConcurrentJobs <= 0 {
			cfg.MaxConcurrentJobs = 4
		}
	}
	if cfg.MaxQueuedJobs <= 0 {
		if envVal := os.Getenv("ARTIX_MAX_QUEUED_JOBS"); envVal != "" {
			if n, err := strconv.Atoi(envVal); err == nil && n > 0 {
				cfg.MaxQueuedJobs = n
			}
		}
		if cfg.MaxQueuedJobs <= 0 {
			cfg.MaxQueuedJobs = 16
		}
	}

	s := &WebhookServer{
		cfg:                 cfg,
		jobs:                make(map[string]*JobStatus),
		sem:                 make(chan struct{}, cfg.MaxConcurrentJobs),
		processedDeliveries: make(map[string]time.Time),
		branchLocks:         make(map[string]*sync.Mutex),
	}

	// Load persisted state if configured
	s.loadState()

	return s
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

	// Validate secret if configured or in enterprise/authenticated mode
	if s.cfg.RequireWebhookAuth || policy.IsEnterprise() {
		if s.cfg.GitHubSecret == "" {
			http.Error(w, "webhook secret configuration is required in enterprise / authenticated mode", http.StatusForbidden)
			return
		}
	}
	if s.cfg.GitHubSecret != "" {
		sig := r.Header.Get("X-Hub-Signature-256")
		if !verifyGitHubSignature(s.cfg.GitHubSecret, sig, body) {
			http.Error(w, "invalid signature", http.StatusUnauthorized)
			return
		}
	}

	// Delivery ID deduplication
	deliveryID := r.Header.Get("X-GitHub-Delivery")
	if deliveryID != "" && s.isDuplicateDelivery(deliveryID) {
		w.Header().Set("Content-Type", "application/json")
		status := http.StatusConflict
		if s.cfg.StoragePath != "" {
			status = http.StatusOK
		}
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"status": "ignored",
			"error":  "duplicate delivery ID",
			"msg":    "duplicate delivery ID",
		})
		return
	}

	eventType := r.Header.Get("X-GitHub-Event")
	if eventType == "ping" {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"msg":"pong"}`))
		return
	}

	// Parse issues event
	var event struct {
		Action string `json:"action"`
		Issue  struct {
			Title             string `json:"title"`
			Body              string `json:"body"`
			AuthorAssociation string `json:"author_association"`
			User              struct {
				Login string `json:"login"`
			} `json:"user"`
			Labels []struct {
				Name string `json:"name"`
			} `json:"labels"`
		} `json:"issue"`
		Sender struct {
			Login string `json:"login"`
		} `json:"sender"`
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

	// Cap issue body and title length
	maxBodyLen := s.cfg.MaxBodyLength
	if maxBodyLen <= 0 {
		maxBodyLen = 65536
	}
	if len(event.Issue.Body) > maxBodyLen || len(event.Issue.Title) > 1024 {
		http.Error(w, fmt.Sprintf("rejected: issue body length %d exceeds maximum cap of %d bytes", len(event.Issue.Body), maxBodyLen), http.StatusRequestEntityTooLarge)
		return
	}

	// Author & Sender authorization check:
	// 1. Issue author MUST be authorized (the author created the prompt/instructions).
	// 2. Sender MUST also be authorized (the sender triggered/labeled the event).
	issueAuthor := event.Issue.User.Login
	if !s.isUserAuthorized(issueAuthor, event.Issue.AuthorAssociation) {
		http.Error(w, "unauthorized issue author: prompt author is not an authorized member/owner or in allowedUsers", http.StatusForbidden)
		return
	}

	senderLogin := event.Sender.Login
	if senderLogin != "" && len(s.cfg.AllowedUsers) > 0 {
		if !s.isUserAuthorized(senderLogin, "") {
			http.Error(w, "unauthorized sender: event trigger sender is not in allowedUsers", http.StatusForbidden)
			return
		}
	}

	// Treat issue text as untrusted: run sanitizer and reject any policy/steering injection
	untrustedContent := event.Issue.Title + "\n" + event.Issue.Body
	if detections := steering.ScanPromptInjection(untrustedContent); len(detections) > 0 {
		http.Error(w, fmt.Sprintf("rejected: untrusted issue text contains prompt injection or policy/steering instructions: %s", strings.Join(detections, "; ")), http.StatusBadRequest)
		return
	}

	// Trigger requirement: require explicit label (artix, artix:run, or s.cfg.TriggerLabel) or command (/artix)
	triggerLabel := s.cfg.TriggerLabel
	if triggerLabel == "" {
		triggerLabel = "artix"
	}
	hasLabel := false
	for _, l := range event.Issue.Labels {
		if strings.EqualFold(l.Name, triggerLabel) || strings.EqualFold(l.Name, "artix:run") || strings.EqualFold(l.Name, "artix") || strings.EqualFold(l.Name, "kritix") {
			hasLabel = true
			break
		}
	}
	hasCommand := strings.Contains(event.Issue.Title, "/artix") || strings.Contains(event.Issue.Body, "/artix")

	if !hasLabel && !hasCommand {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"msg":"ignored event: issue requires trigger label or /artix command"}`))
		return
	}

	if !s.canAcceptJob() {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusTooManyRequests)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"error":  "concurrency cap or queue limit exceeded",
			"status": "rate_limited",
		})
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

	s.startJob(jobID, "github", target.Owner, task)

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

	if s.cfg.RequireWebhookAuth || policy.IsEnterprise() {
		if s.cfg.GitLabToken == "" {
			http.Error(w, "gitlab webhook token configuration is required in enterprise / authenticated mode", http.StatusForbidden)
			return
		}
	}

	if s.cfg.GitLabToken != "" {
		token := r.Header.Get("X-Gitlab-Token")
		if subtle.ConstantTimeCompare([]byte(token), []byte(s.cfg.GitLabToken)) != 1 {
			http.Error(w, "invalid gitlab token", http.StatusUnauthorized)
			return
		}
	}

	// Delivery ID deduplication for GitLab
	deliveryID := r.Header.Get("X-Gitlab-Event-UUID")
	if deliveryID != "" && s.isDuplicateDelivery(deliveryID) {
		w.Header().Set("Content-Type", "application/json")
		status := http.StatusConflict
		if s.cfg.StoragePath != "" {
			status = http.StatusOK
		}
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"status": "ignored",
			"error":  "duplicate delivery ID",
			"msg":    "duplicate delivery ID",
		})
		return
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
			Labels      []struct {
				Title string `json:"title"`
			} `json:"labels"`
		} `json:"object_attributes"`
		User struct {
			Username string `json:"username"`
		} `json:"user"`
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

	// Cap issue description and title length
	maxBodyLen := s.cfg.MaxBodyLength
	if maxBodyLen <= 0 {
		maxBodyLen = 65536
	}
	if len(event.ObjectAttributes.Description) > maxBodyLen || len(event.ObjectAttributes.Title) > 1024 {
		http.Error(w, fmt.Sprintf("rejected: issue description length %d exceeds maximum cap of %d bytes", len(event.ObjectAttributes.Description), maxBodyLen), http.StatusRequestEntityTooLarge)
		return
	}

	// User authorization check if AllowedUsers is configured or in enterprise mode
	if len(s.cfg.AllowedUsers) > 0 {
		allowed := false
		for _, u := range s.cfg.AllowedUsers {
			if strings.EqualFold(u, event.User.Username) {
				allowed = true
				break
			}
		}
		if !allowed {
			http.Error(w, "unauthorized sender: user not in allowedUsers", http.StatusForbidden)
			return
		}
	} else if policy.IsEnterprise() {
		http.Error(w, "unauthorized sender: unverifiable sender in enterprise mode", http.StatusForbidden)
		return
	}

	// Treat issue text as untrusted: run sanitizer and reject any policy/steering injection
	untrustedContent := event.ObjectAttributes.Title + "\n" + event.ObjectAttributes.Description
	if detections := steering.ScanPromptInjection(untrustedContent); len(detections) > 0 {
		http.Error(w, fmt.Sprintf("rejected: untrusted issue text contains prompt injection or policy/steering instructions: %s", strings.Join(detections, "; ")), http.StatusBadRequest)
		return
	}

	// Trigger requirement: label or command
	hasLabel := false
	triggerLabel := s.cfg.TriggerLabel
	if triggerLabel == "" {
		triggerLabel = "artix"
	}
	for _, l := range event.ObjectAttributes.Labels {
		if strings.EqualFold(l.Title, triggerLabel) || strings.EqualFold(l.Title, "artix:run") || strings.EqualFold(l.Title, "artix") || strings.EqualFold(l.Title, "kritix") {
			hasLabel = true
			break
		}
	}
	hasCommand := strings.Contains(event.ObjectAttributes.Title, "/artix") || strings.Contains(event.ObjectAttributes.Description, "/artix")

	if !hasLabel && !hasCommand {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"msg":"ignored event: issue requires trigger label or /artix command"}`))
		return
	}

	if !s.canAcceptJob() {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusTooManyRequests)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"error":  "concurrency cap or queue limit exceeded",
			"status": "rate_limited",
		})
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

	s.startJob(jobID, "gitlab", owner, task)

	w.WriteHeader(http.StatusAccepted)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"jobId":  jobID,
		"status": "queued",
	})
}

func (s *WebhookServer) canAcceptJob() bool {
	s.jobsMu.RLock()
	defer s.jobsMu.RUnlock()
	activeOrQueued := 0
	for _, j := range s.jobs {
		if j.Status == "queued" || j.Status == "running" {
			activeOrQueued++
		}
	}
	limit := s.cfg.MaxConcurrentJobs + s.cfg.MaxQueuedJobs
	return activeOrQueued < limit
}

func (s *WebhookServer) handleJobs(w http.ResponseWriter, r *http.Request) {
	// Authentication gate for /jobs endpoint
	if s.cfg.JobsAuthToken != "" || policy.IsEnterprise() {
		authHeader := r.Header.Get("Authorization")
		token := strings.TrimPrefix(authHeader, "Bearer ")
		if token == "" || (s.cfg.JobsAuthToken != "" && subtle.ConstantTimeCompare([]byte(token), []byte(s.cfg.JobsAuthToken)) != 1) {
			http.Error(w, "unauthorized: valid jobs authorization token required", http.StatusUnauthorized)
			return
		}
	}

	tenantQuery := r.URL.Query().Get("tenant")
	if tenantHeader := r.Header.Get("X-Artix-Tenant"); tenantHeader != "" && tenantQuery == "" {
		tenantQuery = tenantHeader
	}

	// Server-side tenant binding: enforce explicit tenant query in enterprise/authenticated mode
	if (s.cfg.JobsAuthToken != "" || policy.IsEnterprise()) && tenantQuery == "" {
		http.Error(w, "tenant parameter or X-Artix-Tenant header is required", http.StatusBadRequest)
		return
	}

	s.jobsMu.RLock()
	defer s.jobsMu.RUnlock()

	filtered := make(map[string]*JobStatus)
	for id, job := range s.jobs {
		if tenantQuery == "" || job.Tenant == tenantQuery {
			filtered[id] = job
		}
	}

	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(filtered)
}

func (s *WebhookServer) startJob(id, source, tenant string, task *RemoteWorkerTask) {
	status := &JobStatus{
		ID:        id,
		Source:    source,
		Tenant:    tenant,
		Status:    "queued",
		CreatedAt: time.Now(),
	}

	s.jobsMu.Lock()
	s.jobs[id] = status
	s.jobsMu.Unlock()
	s.saveState()

	if s.cfg.Worker == nil {
		return
	}

	go func() {
		// Acquire concurrency slot FIRST to avoid lock-hoarding goroutine buildup
		s.sem <- struct{}{}
		defer func() { <-s.sem }()

		// Acquire per-branch lock to prevent concurrent jobs racing on the same branch
		branchKey := fmt.Sprintf("%s/%s:%s", task.Target.Owner, task.Target.Repo, task.Target.Branch)
		branchLock := s.getBranchLock(branchKey)
		branchLock.Lock()
		defer branchLock.Unlock()

		s.jobsMu.Lock()
		status.Status = "running"
		s.jobsMu.Unlock()
		s.saveState()

		timeout := s.cfg.JobTimeout
		if timeout <= 0 {
			timeout = 10 * time.Minute
		}
		ctx, cancel := context.WithTimeout(context.Background(), timeout)
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
		s.saveState()
	}()
}

func (s *WebhookServer) isDuplicateDelivery(deliveryID string) bool {
	s.deliveriesMu.Lock()
	if _, exists := s.processedDeliveries[deliveryID]; exists {
		s.deliveriesMu.Unlock()
		return true
	}
	s.processedDeliveries[deliveryID] = time.Now()
	s.deliveriesMu.Unlock()
	s.saveState()
	return false
}

func (s *WebhookServer) saveState() {
	if s.cfg.StoragePath == "" {
		return
	}
	s.storageMu.Lock()
	defer s.storageMu.Unlock()

	s.jobsMu.RLock()
	jobsCopy := make(map[string]*JobStatus, len(s.jobs))
	for k, v := range s.jobs {
		jobsCopy[k] = v
	}
	s.jobsMu.RUnlock()

	s.deliveriesMu.Lock()
	delivCopy := make(map[string]time.Time, len(s.processedDeliveries))
	for k, v := range s.processedDeliveries {
		delivCopy[k] = v
	}
	s.deliveriesMu.Unlock()

	state := daemonPersistentState{
		Jobs:       jobsCopy,
		Deliveries: delivCopy,
	}

	data, err := json.MarshalIndent(state, "", "  ")
	if err == nil {
		_ = os.WriteFile(s.cfg.StoragePath, data, 0600)
	}
}

func (s *WebhookServer) loadState() {
	if s.cfg.StoragePath == "" {
		return
	}
	s.storageMu.Lock()
	defer s.storageMu.Unlock()

	data, err := os.ReadFile(s.cfg.StoragePath)
	if err != nil || len(data) == 0 {
		return
	}

	var state daemonPersistentState
	if err := json.Unmarshal(data, &state); err != nil {
		return
	}

	s.jobsMu.Lock()
	if state.Jobs != nil {
		for k, v := range state.Jobs {
			s.jobs[k] = v
		}
	}
	s.jobsMu.Unlock()

	s.deliveriesMu.Lock()
	if state.Deliveries != nil {
		for k, v := range state.Deliveries {
			s.processedDeliveries[k] = v
		}
	}
	s.deliveriesMu.Unlock()
}

func (s *WebhookServer) getBranchLock(key string) *sync.Mutex {
	s.branchLocksMu.Lock()
	defer s.branchLocksMu.Unlock()
	lock, ok := s.branchLocks[key]
	if !ok {
		lock = &sync.Mutex{}
		s.branchLocks[key] = lock
	}
	return lock
}

// GetJob returns status of a specific job.
func (s *WebhookServer) GetJob(id string) (*JobStatus, bool) {
	s.jobsMu.RLock()
	defer s.jobsMu.RUnlock()
	j, ok := s.jobs[id]
	return j, ok
}

func (s *WebhookServer) isUserAuthorized(username, authorAssociation string) bool {
	if len(s.cfg.AllowedUsers) > 0 {
		for _, u := range s.cfg.AllowedUsers {
			if strings.EqualFold(u, username) {
				return true
			}
		}
		return false
	}
	if authorAssociation != "" {
		assoc := strings.ToUpper(strings.TrimSpace(authorAssociation))
		return assoc == "OWNER" || assoc == "MEMBER" || assoc == "COLLABORATOR"
	}
	return !policy.IsEnterprise()
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

