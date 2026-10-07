package forge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"artix/pkg/policy"
)

// GitLabClient interacts with GitLab's REST API.
type GitLabClient struct {
	auth       ForgeAuth
	httpClient *http.Client
	baseURL    string
}

// NewGitLabClient creates a GitLab API client.
func NewGitLabClient(auth ForgeAuth) *GitLabClient {
	baseURL := auth.BaseURL
	if baseURL == "" {
		baseURL = "https://gitlab.com/api/v4"
	}
	baseURL = strings.TrimSuffix(baseURL, "/")

	return &GitLabClient{
		auth: auth,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		baseURL: baseURL,
	}
}

// CreatePullRequest opens a new Merge Request on GitLab.
func (c *GitLabClient) CreatePullRequest(target *RemoteRepoTarget, req *PullRequestRequest) (*PullRequestResponse, error) {
	projectPath := fmt.Sprintf("%s/%s", target.Owner, target.Repo)
	escapedPath := url.PathEscape(projectPath)
	apiURL := fmt.Sprintf("%s/projects/%s/merge_requests", c.baseURL, escapedPath)

	payload := map[string]string{
		"title":         req.Title,
		"description":   req.Body,
		"source_branch": req.Head,
		"target_branch": req.Base,
	}

	bodyBytes, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal merge request payload: %w", err)
	}

	httpReq, err := http.NewRequest(http.MethodPost, apiURL, bytes.NewReader(bodyBytes))
	if err != nil {
		return nil, fmt.Errorf("failed to create http request: %w", err)
	}

	httpReq.Header.Set("PRIVATE-TOKEN", c.auth.Token)
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("gitlab api request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("gitlab api error (status %d): check token and project permissions", resp.StatusCode)
	}

	var glResp struct {
		ID     int64  `json:"id"`
		IID    int    `json:"iid"`
		Title  string `json:"title"`
		WebURL string `json:"web_url"`
		State  string `json:"state"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&glResp); err != nil {
		return nil, fmt.Errorf("failed to decode gitlab response: %w", err)
	}

	return &PullRequestResponse{
		ID:      glResp.ID,
		Number:  glResp.IID,
		Title:   glResp.Title,
		HTMLURL: glResp.WebURL,
		URL:     glResp.WebURL,
		State:   glResp.State,
	}, nil
}

// VerifyMRApproval fetches Merge Request and approval details server-side from GitLab API.
func (c *GitLabClient) VerifyMRApproval(ctx context.Context, target *RemoteRepoTarget, mrIID int, targetCommitSHA string) (*policy.PRApproval, error) {
	return nil, fmt.Errorf("gitlab verify mr approval not implemented")
}
