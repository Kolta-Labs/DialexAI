package tracker

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"kritix/pkg/spec"
	"kritix/pkg/triage"
)

var (
	ErrJiraAuthFailed     = errors.New("jira: authentication failed (401/403)")
	ErrJiraProjectNotFound = errors.New("jira: project not found (404)")
	ErrJiraRateLimited    = errors.New("jira: rate limit exceeded (429)")
)

// JiraConfig contains connection parameters for the Jira REST API v2/v3.
type JiraConfig struct {
	BaseURL    string `json:"base_url"`    // e.g. "https://company.atlassian.net"
	UserEmail  string `json:"user_email"`  // e.g. "sdet@company.com"
	APIToken   string `json:"api_token"`   // Atlassian API Token
	ProjectKey string `json:"project_key"` // e.g. "QA", "ENG"
	IssueType  string `json:"issue_type"`  // e.g. "Bug", "Defect"
}

// JiraTracker implements IssueTracker for Jira Cloud and Data Center.
type JiraTracker struct {
	cfg        JiraConfig
	httpClient *http.Client
	maxRetries int
	backoff    time.Duration
}

// NewJiraTracker creates a real Jira REST API tracker client.
func NewJiraTracker(cfg JiraConfig, httpClient *http.Client) *JiraTracker {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 15 * time.Second}
	}
	if cfg.IssueType == "" {
		cfg.IssueType = "Bug"
	}
	cfg.BaseURL = strings.TrimRight(cfg.BaseURL, "/")
	return &JiraTracker{
		cfg:        cfg,
		httpClient: httpClient,
		maxRetries: 3,
		backoff:    50 * time.Millisecond,
	}
}

// ComputeDefectFingerprint generates a deterministic SHA256 fingerprint for defect deduplication.
func ComputeDefectFingerprint(report triage.DefectReport) string {
	h := sha256.New()
	h.Write([]byte(report.TargetURL))
	h.Write([]byte(string(report.Category)))
	h.Write([]byte(report.ActualBehavior))
	for _, step := range report.StepsToReproduce {
		h.Write([]byte(step))
	}
	return hex.EncodeToString(h.Sum(nil))[:16]
}

func (j *JiraTracker) authHeader() string {
	raw := fmt.Sprintf("%s:%s", j.cfg.UserEmail, j.cfg.APIToken)
	return "Basic " + base64.StdEncoding.EncodeToString([]byte(raw))
}

func (j *JiraTracker) doRequest(ctx context.Context, method, path string, body []byte) (*http.Response, error) {
	url := fmt.Sprintf("%s%s", j.cfg.BaseURL, path)
	var resp *http.Response
	var err error

	for attempt := 0; attempt < j.maxRetries; attempt++ {
		var bodyReader io.Reader
		if body != nil {
			bodyReader = bytes.NewReader(body)
		}
		req, reqErr := http.NewRequestWithContext(ctx, method, url, bodyReader)
		if reqErr != nil {
			return nil, reqErr
		}
		req.Header.Set("Authorization", j.authHeader())
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json")

		resp, err = j.httpClient.Do(req)
		if err == nil {
			if resp.StatusCode == http.StatusTooManyRequests || resp.StatusCode >= 500 {
				resp.Body.Close()
				time.Sleep(j.backoff * time.Duration(1<<attempt))
				continue
			}
			return resp, nil
		}
		time.Sleep(j.backoff * time.Duration(1<<attempt))
	}
	return resp, err
}

// FindExistingDefect searches for an existing open defect matching the idempotent fingerprint label.
func (j *JiraTracker) FindExistingDefect(ctx context.Context, fingerprint string) (string, error) {
	jql := fmt.Sprintf("project = %q AND labels = %q AND statusCategory != Done", j.cfg.ProjectKey, "fingerprint-"+fingerprint)
	path := fmt.Sprintf("/rest/api/2/search?jql=%s&maxResults=1&fields=key,summary", strings.ReplaceAll(jql, " ", "+"))
	resp, err := j.doRequest(ctx, http.MethodGet, path, nil)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("search failed with status %d", resp.StatusCode)
	}

	var searchRes struct {
		Issues []struct {
			Key string `json:"key"`
		} `json:"issues"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&searchRes); err != nil {
		return "", err
	}
	if len(searchRes.Issues) > 0 {
		return searchRes.Issues[0].Key, nil
	}
	return "", nil
}

// CreateIssue creates a new issue in Jira or appends a comment to an existing defect with the same fingerprint.
func (j *JiraTracker) CreateIssue(ctx context.Context, report triage.DefectReport) (*IssueResult, error) {
	if j.cfg.BaseURL == "" || j.cfg.APIToken == "" {
		return nil, errors.New("jira: missing BaseURL or APIToken")
	}

	fingerprint := ComputeDefectFingerprint(report)
	existingKey, _ := j.FindExistingDefect(ctx, fingerprint)
	if existingKey != "" {
		// Append comment to existing issue for idempotency
		commentBody := map[string]string{
			"body": fmt.Sprintf("Kritix QA detected regression recurrence at %s:\n%s",
				time.Now().UTC().Format(time.RFC3339), report.ActualBehavior),
		}
		cBytes, _ := json.Marshal(commentBody)
		_, _ = j.doRequest(ctx, http.MethodPost, fmt.Sprintf("/rest/api/2/issue/%s/comment", existingKey), cBytes)

		return &IssueResult{
			Tracker:   TrackerJira,
			IssueID:   existingKey,
			IssueURL:  fmt.Sprintf("%s/browse/%s", j.cfg.BaseURL, existingKey),
			Title:     report.Title,
			CreatedAt: time.Now(),
		}, nil
	}

	payload := map[string]interface{}{
		"fields": map[string]interface{}{
			"project": map[string]string{
				"key": j.cfg.ProjectKey,
			},
			"summary":     fmt.Sprintf("[Kritix Defect] %s", report.Title),
			"description": FormatMarkdownIssue(report),
			"issuetype": map[string]string{
				"name": j.cfg.IssueType,
			},
			"labels": []string{
				"kritix-ai",
				"qa-defect",
				"fingerprint-" + fingerprint,
			},
		},
	}

	pBytes, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}

	resp, err := j.doRequest(ctx, http.MethodPost, "/rest/api/2/issue", pBytes)
	if err != nil {
		return nil, fmt.Errorf("jira create issue request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return nil, ErrJiraAuthFailed
	}
	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrJiraProjectNotFound
	}
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("jira issue creation failed (%d): %s", resp.StatusCode, string(body))
	}

	var createResp struct {
		ID   string `json:"id"`
		Key  string `json:"key"`
		Self string `json:"self"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&createResp); err != nil {
		return nil, err
	}

	return &IssueResult{
		Tracker:   TrackerJira,
		IssueID:   createResp.Key,
		IssueURL:  fmt.Sprintf("%s/browse/%s", j.cfg.BaseURL, createResp.Key),
		Title:     report.Title,
		CreatedAt: time.Now(),
	}, nil
}

// IngestStory retrieves a user story/ticket from Jira.
func (j *JiraTracker) IngestStory(ctx context.Context, ticketID string) (*spec.Story, error) {
	if j.cfg.BaseURL == "" || j.cfg.APIToken == "" {
		return nil, errors.New("jira: missing BaseURL or APIToken")
	}

	resp, err := j.doRequest(ctx, http.MethodGet, fmt.Sprintf("/rest/api/2/issue/%s", ticketID), nil)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("jira get issue failed with status %d", resp.StatusCode)
	}

	var issue struct {
		Key    string `json:"key"`
		Fields struct {
			Summary     string `json:"summary"`
			Description string `json:"description"`
			Priority    struct {
				Name string `json:"name"`
			} `json:"priority"`
		} `json:"fields"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&issue); err != nil {
		return nil, err
	}

	priority := spec.PriorityMedium
	switch strings.ToLower(issue.Fields.Priority.Name) {
	case "highest", "blocker", "critical":
		priority = spec.PriorityCritical
	case "high":
		priority = spec.PriorityHigh
	case "low", "lowest":
		priority = spec.PriorityLow
	}

	return &spec.Story{
		ID:          issue.Key,
		Title:       issue.Fields.Summary,
		Description: issue.Fields.Description,
		Priority:    priority,
	}, nil
}
