package spec

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	"kritix/pkg/model"
)

// QACouncilInterrogation represents the multi-perspective inquiry into a user story.
type QACouncilInterrogation struct {
	StoryTitle        string   `json:"story_title"`
	UncoveredGaps     []string `json:"uncovered_gaps"`
	AdversarialCases  []string `json:"adversarial_cases"`
	ConcurrencyRisks  []string `json:"concurrency_risks"`
	RefinedCriteria   []AcceptanceCriterion `json:"refined_criteria"`
}

// CouncilClient connects to socratix-engine or falls back to local router deliberation.
type CouncilClient struct {
	router         *model.Router
	socratixURL    string
	httpClient     *http.Client
}

// NewCouncilClient constructs a Socratic QA council consumer.
func NewCouncilClient(router *model.Router, socratixEndpoint string) *CouncilClient {
	if socratixEndpoint == "" {
		socratixEndpoint = "http://localhost:8080"
	}
	return &CouncilClient{
		router:      router,
		socratixURL: strings.TrimSuffix(socratixEndpoint, "/"),
		httpClient:  &http.Client{Timeout: 2 * time.Second},
	}
}

// InterrogateStory convenes a Socratic deliberation on the story to discover hidden edge cases.
func (c *CouncilClient) InterrogateStory(ctx context.Context, story Story) (*QACouncilInterrogation, error) {
	// Attempt Socratix Engine daemon first
	interrogation, err := c.callSocratixDaemon(ctx, story)
	if err == nil {
		return interrogation, nil
	}

	// Fallback to local Router StagePO model
	return c.deliberateViaRouter(ctx, story)
}

func (c *CouncilClient) callSocratixDaemon(ctx context.Context, story Story) (*QACouncilInterrogation, error) {
	reqBody, _ := json.Marshal(map[string]interface{}{
		"topic": fmt.Sprintf("Adversarial QA audit of user story: %s", story.Title),
		"context": story.Description,
		"rounds": 2,
	})

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.socratixURL+"/api/v1/deliberate", bytes.NewReader(reqBody))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("socratix returned status %d", resp.StatusCode)
	}

	// Successfully deliberated via Socratix
	var result struct {
		Consensus string   `json:"consensus"`
		Dissent   []string `json:"dissent"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}

	return &QACouncilInterrogation{
		StoryTitle:       story.Title,
		UncoveredGaps:    result.Dissent,
		AdversarialCases: []string{result.Consensus},
		RefinedCriteria:  story.AcceptanceCriteria,
	}, nil
}

func (c *CouncilClient) deliberateViaRouter(ctx context.Context, story Story) (*QACouncilInterrogation, error) {
	prompt := fmt.Sprintf(`Perform a rigorous Socratic QA interrogation of the following user story:
TITLE: %s
DESCRIPTION: %s
EXISTING ACCEPTANCE CRITERIA: %d

Identify:
1. Critical unhandled edge cases
2. Security & authorization boundary gaps
3. Concurrency/race condition risks
4. Concrete Given-When-Then criteria to add

Respond in JSON format:
{
  "uncovered_gaps": ["gap 1", "gap 2"],
  "adversarial_cases": ["adversarial case 1"],
  "concurrency_risks": ["risk 1"],
  "refined_criteria": [
    {"id": "AC-EXT-1", "given": "...", "when": "...", "then": "...", "edge_cases": ["..."]}
  ]
}`, story.Title, story.Description, len(story.AcceptanceCriteria))

	req := model.Request{
		Stage: model.StagePO,
		Messages: []model.Message{
			{
				Role:    "system",
				Content: "You are an adversarial Lead QA Architect running a Socratic requirements interrogation.",
			},
			{Role: "user", Content: prompt},
		},
		Temperature: 0.2,
		MaxTokens:   1024,
	}

	resp, err := c.router.InvokeStage(ctx, model.StagePO, req)
	if err != nil {
		// Heuristic fallback if model offline
		return c.heuristicInterrogation(story), nil
	}

	var interrogation QACouncilInterrogation
	interrogation.StoryTitle = story.Title
	cleaned := extractJSONBlock(resp.Content)

	if err := json.Unmarshal([]byte(cleaned), &interrogation); err != nil {
		return c.heuristicInterrogation(story), nil
	}

	// Merge with existing criteria
	interrogation.RefinedCriteria = append(story.AcceptanceCriteria, interrogation.RefinedCriteria...)
	return &interrogation, nil
}

func (c *CouncilClient) heuristicInterrogation(story Story) *QACouncilInterrogation {
	return &QACouncilInterrogation{
		StoryTitle: story.Title,
		UncoveredGaps: []string{
			"Missing boundary verification on numeric and empty inputs",
			"No definition of behavior when downstream service is offline",
		},
		AdversarialCases: []string{
			"Double-click submit CTA causing duplicate record creation",
			"User session revoked mid-transaction",
		},
		ConcurrencyRisks: []string{
			"Simultaneous edits by two administrators to the same entity",
		},
		RefinedCriteria: append(story.AcceptanceCriteria, AcceptanceCriterion{
			ID:        "AC-HEURISTIC-1",
			Given:     "a user double clicks the submit button",
			When:      "the request is sent concurrently",
			Then:      "the server idempotency key prevents duplicate execution",
			EdgeCases: []string{"Network lag spike of 2000ms"},
		}),
	}
}

func extractJSONBlock(s string) string {
	start := strings.Index(s, "{")
	end := strings.LastIndex(s, "}")
	if start != -1 && end != -1 && end > start {
		return s[start : end+1]
	}
	return s
}
