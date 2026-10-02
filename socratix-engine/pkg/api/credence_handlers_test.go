package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/store"
)

func TestGetAndRecalculateCredenceEndpoints(t *testing.T) {
	tmpDir := t.TempDir()
	st, err := store.New(tmpDir)
	if err != nil {
		t.Fatalf("failed to create store: %v", err)
	}

	state := model.NewAppState()
	discID := "disc_test_credence"
	projID := "proj_1"
	state.Projects = append(state.Projects, model.Project{ID: projID, Name: "Core Architecture"})
	state.Discussions = append(state.Discussions, model.Discussion{
		ID:        discID,
		ProjectID: projID,
		Name:      "Distributed Architecture",
		Config: model.DebateConfig{
			Topic: "Apache Kafka vs RabbitMQ vs gRPC",
			Primary: model.Agent{
				Provider: model.ProviderAnthropic,
				Model:    "claude-sonnet-5",
			},
			Secondary: &model.Agent{
				Provider: model.ProviderOpenAI,
				Model:    "gpt-5.6-sol",
			},
		},
		Transcript: []model.DebateMessage{
			{
				Round:             1,
				AgentID:           model.ProviderAnthropic,
				AuthorDisplayName: "Architect",
				Content:           "We must choose Kafka for high-throughput stream persistence.",
			},
			{
				Round:             1,
				AgentID:           model.ProviderOpenAI,
				AuthorDisplayName: "Pragmatist",
				Content:           "RabbitMQ has lower latency for point-to-point queues.",
			},
		},
	})
	_ = st.Save(state)

	s := NewServer(st)
	ts := httptest.NewServer(s.Router())
	defer ts.Close()

	token, err := s.issueToken("testuser")
	if err != nil {
		t.Fatalf("failed to issue token: %v", err)
	}

	// 1. GET /api/v1/debates/disc_test_credence/credence
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/debates/"+discID+"/credence", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET /credence failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("Expected GET /credence to return 200 OK, got %d", resp.StatusCode)
	}

	var ledger model.CredenceLedger
	if err := json.NewDecoder(resp.Body).Decode(&ledger); err != nil {
		t.Fatalf("Failed to decode CredenceLedger: %v", err)
	}

	if len(ledger.Hypotheses) < 2 {
		t.Errorf("Expected at least 2 hypotheses, got %d", len(ledger.Hypotheses))
	}
	if len(ledger.Snapshots) < 2 {
		t.Errorf("Expected at least 2 snapshots, got %d", len(ledger.Snapshots))
	}

	// 2. POST /api/v1/debates/disc_test_credence/credence/recalculate
	postReq, _ := http.NewRequest("POST", ts.URL+"/api/v1/debates/"+discID+"/credence/recalculate", nil)
	postReq.Header.Set("Authorization", "Bearer "+token)
	postResp, err := http.DefaultClient.Do(postReq)
	if err != nil {
		t.Fatalf("POST /credence/recalculate failed: %v", err)
	}
	defer postResp.Body.Close()

	if postResp.StatusCode != http.StatusOK {
		t.Fatalf("Expected POST /credence/recalculate to return 200 OK, got %d", postResp.StatusCode)
	}

	var recalcLedger model.CredenceLedger
	if err := json.NewDecoder(postResp.Body).Decode(&recalcLedger); err != nil {
		t.Fatalf("Failed to decode recalculated CredenceLedger: %v", err)
	}

	if recalcLedger.DiscussionID != discID {
		t.Errorf("Expected DiscussionID '%s', got '%s'", discID, recalcLedger.DiscussionID)
	}
}
