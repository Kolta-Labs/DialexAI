package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"socratix/pkg/model"
)

func TestEvaluateConsensus(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, s.Store)

	// Create a test debate config with agents
	cfg := model.DebateConfig{
		Topic:     "test topic",
		Primary:   model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5"),
		RoundMode: model.RoundModeFixed,
		MaxRounds: 2,
	}
	secondary := model.NewAgent(model.ProviderOpenAI, "gpt-4")
	cfg.Secondary = &secondary

	// Create test transcript with agreement messages
	transcript := []model.DebateMessage{
		{
			SeatID:        cfg.Primary.ID,
			Round:         1,
			Content:       "This is a strong argument.",
			IsUserComment: false,
			IsError:       false,
			IsSystem:      false,
		},
		{
			SeatID:        cfg.Secondary.ID,
			Round:         1,
			Content:       "I agree with the primary point.",
			IsUserComment: false,
			IsError:       false,
			IsSystem:      false,
		},
		{
			SeatID:        cfg.Primary.ID,
			Round:         2,
			Content:       "AGREED: we have consensus",
			IsUserComment: false,
			IsError:       false,
			IsSystem:      false,
		},
		{
			SeatID:        cfg.Secondary.ID,
			Round:         2,
			Content:       "AGREED: I concur",
			IsUserComment: false,
			IsError:       false,
			IsSystem:      false,
		},
	}

	reqBody := evaluateConsensusRequest{
		Config:     cfg,
		Transcript: transcript,
		Round:      nil, // Will default to max round
	}

	bodyBytes, _ := json.Marshal(reqBody)
	req, _ := http.NewRequest("POST", srv.URL+"/api/v1/consensus/evaluate", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var result consensusResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	// The result should indicate some level of consensus
	if result.TotalCount != 2 {
		t.Errorf("expected TotalCount 2, got %d", result.TotalCount)
	}
}

// consensusResult is a helper struct for unmarshaling the response
type consensusResult struct {
	Achieved    bool     `json:"achieved"`
	Ongoing     bool     `json:"ongoing"`
	NotReady    string   `json:"notReady"`
	AgreedSeats []string `json:"agreedSeats"`
	AgreedCount int      `json:"agreedCount"`
	TotalCount  int      `json:"totalCount"`
	Ratio       float64  `json:"ratio"`
}

func TestEvaluateConsensusWithExplicitRound(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, s.Store)

	cfg := model.DebateConfig{
		Topic:     "test topic",
		Primary:   model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5"),
		RoundMode: model.RoundModeFixed,
		MaxRounds: 2,
	}

	transcript := []model.DebateMessage{
		{
			Round:         1,
			Content:       "First round",
			IsUserComment: false,
			IsError:       false,
			IsSystem:      false,
		},
	}

	round := 1
	reqBody := evaluateConsensusRequest{
		Config:     cfg,
		Transcript: transcript,
		Round:      &round,
	}

	bodyBytes, _ := json.Marshal(reqBody)
	req, _ := http.NewRequest("POST", srv.URL+"/api/v1/consensus/evaluate", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var result consensusResult
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if result.NotReady == "" {
		t.Errorf("expected NotReady message for round 1 with no agents, got empty")
	}
}
