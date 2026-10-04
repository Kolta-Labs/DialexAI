package tracker

import (
	"bytes"
	"context"
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
	ErrLinearAuthFailed = errors.New("linear: authentication failed (401/403)")
)

// LinearConfig contains connection settings for the Linear GraphQL API.
type LinearConfig struct {
	APIKey  string `json:"api_key"`
	TeamID  string `json:"team_id"`
	BaseURL string `json:"base_url,omitempty"` // Default: https://api.linear.app/graphql
}

// LinearTracker implements IssueTracker for Linear issues.
type LinearTracker struct {
	cfg        LinearConfig
	httpClient *http.Client
}

// NewLinearTracker creates a real Linear GraphQL API tracker client.
func NewLinearTracker(cfg LinearConfig, httpClient *http.Client) *LinearTracker {
	if httpClient == nil {
		httpClient = &http.Client{Timeout: 15 * time.Second}
	}
	if cfg.BaseURL == "" {
		cfg.BaseURL = "https://api.linear.app/graphql"
	}
	return &LinearTracker{
		cfg:        cfg,
		httpClient: httpClient,
	}
}

type graphQLRequest struct {
	Query     string                 `json:"query"`
	Variables map[string]interface{} `json:"variables,omitempty"`
}

func (l *LinearTracker) doGraphQL(ctx context.Context, reqBody graphQLRequest) ([]byte, error) {
	b, err := json.Marshal(reqBody)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, l.cfg.BaseURL, bytes.NewReader(b))
	if err != nil {
		return nil, err
	}

	req.Header.Set("Authorization", l.cfg.APIKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := l.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusUnauthorized || resp.StatusCode == http.StatusForbidden {
		return nil, ErrLinearAuthFailed
	}
	if resp.StatusCode != http.StatusOK {
		out, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("linear request failed (%d): %s", resp.StatusCode, string(out))
	}

	return io.ReadAll(resp.Body)
}

func (l *LinearTracker) CreateIssue(ctx context.Context, report triage.DefectReport) (*IssueResult, error) {
	if l.cfg.APIKey == "" || l.cfg.TeamID == "" {
		return nil, errors.New("linear: missing APIKey or TeamID")
	}

	fingerprint := ComputeDefectFingerprint(report)
	query := `mutation CreateDefect($input: IssueCreateInput!) {
		issueCreate(input: $input) {
			success
			issue {
				id
				identifier
				url
				title
			}
		}
	}`

	variables := map[string]interface{}{
		"input": map[string]interface{}{
			"teamId":      l.cfg.TeamID,
			"title":       fmt.Sprintf("[Kritix QA] %s", report.Title),
			"description": FormatMarkdownIssue(report) + fmt.Sprintf("\n\n<!-- fingerprint: %s -->", fingerprint),
		},
	}

	body, err := l.doGraphQL(ctx, graphQLRequest{Query: query, Variables: variables})
	if err != nil {
		return nil, err
	}

	var res struct {
		Data struct {
			IssueCreate struct {
				Success bool `json:"success"`
				Issue   struct {
					ID         string `json:"id"`
					Identifier string `json:"identifier"`
					URL        string `json:"url"`
					Title      string `json:"title"`
				} `json:"issue"`
			} `json:"issueCreate"`
		} `json:"data"`
		Errors []struct {
			Message string `json:"message"`
		} `json:"errors"`
	}

	if err := json.Unmarshal(body, &res); err != nil {
		return nil, err
	}
	if len(res.Errors) > 0 {
		return nil, fmt.Errorf("linear graphql error: %s", res.Errors[0].Message)
	}

	issue := res.Data.IssueCreate.Issue
	return &IssueResult{
		Tracker:   TrackerLinear,
		IssueID:   issue.Identifier,
		IssueURL:  issue.URL,
		Title:     report.Title,
		CreatedAt: time.Now(),
	}, nil
}

func (l *LinearTracker) IngestStory(ctx context.Context, ticketID string) (*spec.Story, error) {
	if l.cfg.APIKey == "" {
		return nil, errors.New("linear: missing APIKey")
	}

	query := `query GetIssue($id: String!) {
		issue(id: $id) {
			id
			identifier
			title
			description
			priority
		}
	}`

	variables := map[string]interface{}{
		"id": ticketID,
	}

	body, err := l.doGraphQL(ctx, graphQLRequest{Query: query, Variables: variables})
	if err != nil {
		return nil, err
	}

	var res struct {
		Data struct {
			Issue struct {
				ID          string  `json:"id"`
				Identifier  string  `json:"identifier"`
				Title       string  `json:"title"`
				Description string  `json:"description"`
				Priority    float64 `json:"priority"`
			} `json:"issue"`
		} `json:"data"`
		Errors []struct {
			Message string `json:"message"`
		} `json:"errors"`
	}

	if err := json.Unmarshal(body, &res); err != nil {
		return nil, err
	}
	if len(res.Errors) > 0 {
		return nil, fmt.Errorf("linear graphql error: %s", res.Errors[0].Message)
	}

	priority := spec.PriorityMedium
	if res.Data.Issue.Priority == 1 {
		priority = spec.PriorityCritical
	} else if res.Data.Issue.Priority == 2 {
		priority = spec.PriorityHigh
	} else if res.Data.Issue.Priority == 4 {
		priority = spec.PriorityLow
	}

	return &spec.Story{
		ID:          res.Data.Issue.Identifier,
		Title:       res.Data.Issue.Title,
		Description: strings.TrimSpace(res.Data.Issue.Description),
		Priority:    priority,
	}, nil
}
