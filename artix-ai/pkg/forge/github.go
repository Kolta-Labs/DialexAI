package forge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"artix/pkg/policy"
)

// GitHubClient interacts with GitHub's REST API.
type GitHubClient struct {
	auth       ForgeAuth
	httpClient *http.Client
	baseURL    string
}

// NewGitHubClient creates a GitHub API client.
func NewGitHubClient(auth ForgeAuth) *GitHubClient {
	baseURL := auth.BaseURL
	if baseURL == "" {
		baseURL = "https://api.github.com"
	}
	baseURL = strings.TrimSuffix(baseURL, "/")

	return &GitHubClient{
		auth: auth,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		baseURL: baseURL,
	}
}

// CreatePullRequest opens a new Pull Request on GitHub.
func (c *GitHubClient) CreatePullRequest(target *RemoteRepoTarget, req *PullRequestRequest) (*PullRequestResponse, error) {
	apiURL := fmt.Sprintf("%s/repos/%s/%s/pulls", c.baseURL, target.Owner, target.Repo)

	payload := map[string]string{
		"title": req.Title,
		"body":  req.Body,
		"head":  req.Head,
		"base":  req.Base,
	}

	bodyBytes, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal pull request payload: %w", err)
	}

	httpReq, err := http.NewRequest(http.MethodPost, apiURL, bytes.NewReader(bodyBytes))
	if err != nil {
		return nil, fmt.Errorf("failed to create http request: %w", err)
	}

	httpReq.Header.Set("Authorization", "Bearer "+c.auth.Token)
	httpReq.Header.Set("Accept", "application/vnd.github.v3+json")
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("github api request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("github api error (status %d): check token and repo permissions", resp.StatusCode)
	}

	var ghResp struct {
		ID      int64  `json:"id"`
		Number  int    `json:"number"`
		Title   string `json:"title"`
		HTMLURL string `json:"html_url"`
		State   string `json:"state"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&ghResp); err != nil {
		return nil, fmt.Errorf("failed to decode github response: %w", err)
	}

	return &PullRequestResponse{
		ID:      ghResp.ID,
		Number:  ghResp.Number,
		Title:   ghResp.Title,
		HTMLURL: ghResp.HTMLURL,
		URL:     ghResp.HTMLURL,
		State:   ghResp.State,
	}, nil
}

// VerifyPRApproval fetches PR and review details server-side from GitHub API.
func (c *GitHubClient) VerifyPRApproval(ctx context.Context, target *RemoteRepoTarget, prNumber int, targetCommitSHA string) (*policy.PRApproval, error) {
	// STUB: returns nil, nil so adversarial tests fail red
	return nil, nil
}

