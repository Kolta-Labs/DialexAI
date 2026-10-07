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
	// 1. Fetch PR details (Author and Head commit SHA)
	prURL := fmt.Sprintf("%s/repos/%s/%s/pulls/%d", c.baseURL, target.Owner, target.Repo, prNumber)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodGet, prURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create http request: %w", err)
	}
	httpReq.Header.Set("Authorization", "Bearer "+c.auth.Token)
	httpReq.Header.Set("Accept", "application/vnd.github.v3+json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("forge api error fetching PR details: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("forge api error fetching PR details: status %d", resp.StatusCode)
	}

	var prDetails struct {
		User struct {
			Login string `json:"login"`
			Type  string `json:"type"`
		} `json:"user"`
		Head struct {
			SHA string `json:"sha"`
		} `json:"head"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&prDetails); err != nil {
		return nil, fmt.Errorf("failed to decode PR response: %w", err)
	}

	headSHA := prDetails.Head.SHA
	if targetCommitSHA != "" && headSHA != targetCommitSHA {
		return nil, fmt.Errorf("forge approval verification failed: PR head commit %s differs from target commit %s (stale commit)", headSHA, targetCommitSHA)
	}

	// 2. Fetch all PR reviews
	reviewsURL := fmt.Sprintf("%s/repos/%s/%s/pulls/%d/reviews", c.baseURL, target.Owner, target.Repo, prNumber)
	rReq, err := http.NewRequestWithContext(ctx, http.MethodGet, reviewsURL, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create http request: %w", err)
	}
	rReq.Header.Set("Authorization", "Bearer "+c.auth.Token)
	rReq.Header.Set("Accept", "application/vnd.github.v3+json")

	rResp, err := c.httpClient.Do(rReq)
	if err != nil {
		return nil, fmt.Errorf("forge api error fetching reviews: %w", err)
	}
	defer rResp.Body.Close()

	if rResp.StatusCode < 200 || rResp.StatusCode >= 300 {
		return nil, fmt.Errorf("forge api error fetching reviews: status %d", rResp.StatusCode)
	}

	type GHReview struct {
		ID   int64 `json:"id"`
		User struct {
			Login string `json:"login"`
			Type  string `json:"type"`
		} `json:"user"`
		State    string `json:"state"`
		CommitID string `json:"commit_id"`
	}

	var reviews []GHReview
	if err := json.NewDecoder(rResp.Body).Decode(&reviews); err != nil {
		return nil, fmt.Errorf("failed to decode reviews response: %w", err)
	}

	if len(reviews) == 0 {
		return nil, fmt.Errorf("forge approval verification failed: no reviews found on PR #%d", prNumber)
	}

	// Order matters: reviews are chronological. Track latest review state per reviewer.
	latestByUser := make(map[string]GHReview)
	for _, rev := range reviews {
		latestByUser[strings.ToLower(rev.User.Login)] = rev
	}

	// Find an approved review
	for _, rev := range latestByUser {
		if strings.EqualFold(rev.State, "APPROVED") {
			// Check rejections:
			// 1. Approver == PR author
			if strings.EqualFold(rev.User.Login, prDetails.User.Login) {
				return nil, fmt.Errorf("forge approval verification failed: PR author %q cannot approve their own PR", rev.User.Login)
			}
			// 2. Bot / App account
			if strings.EqualFold(rev.User.Type, "Bot") ||
				strings.Contains(strings.ToLower(rev.User.Login), "[bot]") ||
				strings.HasSuffix(strings.ToLower(rev.User.Login), "-bot") ||
				strings.EqualFold(rev.User.Login, "artix-agent") ||
				strings.EqualFold(rev.User.Login, "artix-bot") {
				return nil, fmt.Errorf("forge approval verification failed: bot or App account %q cannot approve PR", rev.User.Login)
			}
			// 3. Stale commit on review
			if headSHA != "" && rev.CommitID != "" && rev.CommitID != headSHA {
				return nil, fmt.Errorf("forge approval verification failed: approval on stale commit %s (current head is %s)", rev.CommitID, headSHA)
			}
			// 4. Allowed approvers in policy
			pol := policy.Active()
			if len(pol.AllowedApprovers) > 0 {
				allowed := false
				for _, a := range pol.AllowedApprovers {
					if strings.EqualFold(a, rev.User.Login) {
						allowed = true
						break
					}
				}
				if !allowed {
					return nil, fmt.Errorf("forge approval verification failed: approver %q is not authorized in allowedApprovers list %v", rev.User.Login, pol.AllowedApprovers)
				}
			}

			return &policy.PRApproval{
				ApproverUsername: rev.User.Login,
				AuthorUsername:   prDetails.User.Login,
				State:            "APPROVED",
				CommitSHA:        rev.CommitID,
				Source:           "github_api_server_verified",
				VerifiedByForge:  true,
			}, nil
		}
	}

	return nil, fmt.Errorf("forge approval verification failed: no valid human APPROVED review found")
}


