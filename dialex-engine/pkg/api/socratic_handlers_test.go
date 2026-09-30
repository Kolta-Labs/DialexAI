package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/socratic"
	"dialex/pkg/store"
)

func TestSocraticTurnHandler_And_ElevateFlow(t *testing.T) {
	tmpDir := t.TempDir()
	st, err := store.New(tmpDir)
	if err != nil {
		t.Fatalf("failed to create store: %v", err)
	}

	// Seed discussion
	state := model.NewAppState()
	discID := "disc_test_socratic"
	projID := "proj_1"
	state.Projects = append(state.Projects, model.Project{ID: projID, Name: "Core Architecture"})
	state.Discussions = append(state.Discussions, model.Discussion{
		ID:        discID,
		ProjectID: projID,
		Name:      "Socratic: Postgres vs Cassandra",
		Mode:      model.DiscussionModeSocraticInterview,
		Status:    model.DiscussionDraft,
		Config: model.DebateConfig{
			Topic: "Postgres vs Cassandra for Financial Transactions",
		},
	})
	if err := st.Save(state); err != nil {
		t.Fatalf("failed to save initial state: %v", err)
	}

	srv := NewServer(st)
	token, err := srv.issueToken("test_user")
	if err != nil {
		t.Fatalf("failed to generate test token: %v", err)
	}

	// 1. Test Socratic Turn
	turnPayload := socratic.SocraticTurnRequest{
		Message: "We propose using PostgreSQL with serializable transactions.",
		Topic:   "Postgres vs Cassandra for Financial Transactions",
		Stance:  model.StanceRuthlessElenchus,
		Stage:   model.StageHypothesisExtraction,
	}
	body, _ := json.Marshal(turnPayload)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/discussions/"+discID+"/socratic/turn", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	srv.Router().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 on turn, got %d: %s", rec.Code, rec.Body.String())
	}

	var turnResp socratic.SocraticTurnResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &turnResp); err != nil {
		t.Fatalf("failed to unmarshal turn response: %v", err)
	}

	if turnResp.ProbeQuestion == "" {
		t.Fatalf("expected probe question in turn response")
	}
	if len(turnResp.LedgerUpdates) == 0 {
		t.Fatalf("expected ledger updates in turn response")
	}

	// 2. Test Socratic Digest
	digestReq := httptest.NewRequest(http.MethodPost, "/api/v1/discussions/"+discID+"/socratic/digest", nil)
	digestReq.Header.Set("Authorization", "Bearer "+token)
	digestRec := httptest.NewRecorder()

	srv.Router().ServeHTTP(digestRec, digestReq)

	if digestRec.Code != http.StatusOK {
		t.Fatalf("expected status 200 on digest, got %d: %s", digestRec.Code, digestRec.Body.String())
	}

	var digestResp model.SocraticDigest
	if err := json.Unmarshal(digestRec.Body.Bytes(), &digestResp); err != nil {
		t.Fatalf("failed to unmarshal digest response: %v", err)
	}

	if digestResp.HardenedThesis == "" {
		t.Fatalf("expected hardened thesis in digest response")
	}
	if len(digestResp.DefendedInvariants) == 0 {
		t.Fatalf("expected defended invariants in digest response")
	}

	// 3. Test Elevate to Council
	elevateReq := httptest.NewRequest(http.MethodPost, "/api/v1/discussions/"+discID+"/socratic/elevate", nil)
	elevateReq.Header.Set("Authorization", "Bearer "+token)
	elevateRec := httptest.NewRecorder()

	srv.Router().ServeHTTP(elevateRec, elevateReq)

	if elevateRec.Code != http.StatusOK {
		t.Fatalf("expected status 200 on elevate, got %d: %s", elevateRec.Code, elevateRec.Body.String())
	}

	var elevateResp socratic.ElevateResult
	if err := json.Unmarshal(elevateRec.Body.Bytes(), &elevateResp); err != nil {
		t.Fatalf("failed to unmarshal elevate response: %v", err)
	}

	if elevateResp.NewDiscussionID == "" {
		t.Fatalf("expected new discussion ID on elevate")
	}

	// Verify new discussion exists in store
	loadedState, _ := st.Load()
	foundNew := false
	for _, d := range loadedState.Discussions {
		if d.ID == elevateResp.NewDiscussionID {
			foundNew = true
			if d.Mode != model.DiscussionModeCouncil {
				t.Errorf("expected elevated discussion mode to be COUNCIL, got %s", d.Mode)
			}
			if !strings.Contains(d.Config.CommonContext, "Residual Dialectical Tensions") {
				t.Errorf("expected common context to contain residual tensions")
			}
			break
		}
	}
	if !foundNew {
		t.Fatalf("elevated discussion %s not found in store", elevateResp.NewDiscussionID)
	}
}
