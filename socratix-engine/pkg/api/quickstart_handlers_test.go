package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"socratix/pkg/model"
	"socratix/pkg/quickstart"
)

func TestListQuickstartModes(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, s.Store)

	req, _ := http.NewRequest("GET", srv.URL+"/api/v1/quickstart/modes", nil)
	req.Header.Set("Authorization", "Bearer "+token)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var modes []quickstartModeInfo
	if err := json.NewDecoder(resp.Body).Decode(&modes); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if len(modes) == 0 {
		t.Fatal("expected at least one mode")
	}

	// Check that the response matches expected modes
	expectedModes := quickstart.Modes()
	if len(modes) != len(expectedModes) {
		t.Fatalf("expected %d modes, got %d", len(expectedModes), len(modes))
	}

	for i, m := range modes {
		if m.ID != expectedModes[i].ID {
			t.Errorf("mode %d: expected ID %q, got %q", i, expectedModes[i].ID, m.ID)
		}
		if m.Rounds != expectedModes[i].Rounds {
			t.Errorf("mode %d: expected Rounds %d, got %d", i, expectedModes[i].Rounds, m.Rounds)
		}
		if m.KeepDissent != expectedModes[i].KeepDissent {
			t.Errorf("mode %d: expected KeepDissent %v, got %v", i, expectedModes[i].KeepDissent, m.KeepDissent)
		}
	}
}

func TestApplyQuickstartMode(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, s.Store)

	// Create a request to apply a mode to a config
	currentCfg := model.DebateConfig{
		Topic:         "test topic",
		CommonContext: "test context",
		Primary:       model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5"),
		RoundMode:     model.RoundModeUnlimited,
		MaxRounds:     10,
	}

	reqBody := quickstartApplyRequest{
		Mode:   "premortem",
		Config: currentCfg,
	}

	bodyBytes, _ := json.Marshal(reqBody)
	req, _ := http.NewRequest("POST", srv.URL+"/api/v1/quickstart/apply", bytes.NewReader(bodyBytes))
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

	var result model.DebateConfig
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	// Verify the result has the expected properties
	if result.RoundMode != model.RoundModeFixed {
		t.Errorf("expected RoundMode FIXED, got %s", result.RoundMode)
	}
	if result.MaxRounds != 2 {
		t.Errorf("expected MaxRounds 2 for premortem, got %d", result.MaxRounds)
	}
	if result.Primary.DisplayName != "Chair" {
		t.Errorf("expected primary DisplayName 'Chair', got %q", result.Primary.DisplayName)
	}
	if result.Independence == nil || !result.Independence.BlindFirstRound {
		t.Error("expected independence with blind first round")
	}
}

func TestApplyQuickstartModeUnknownMode(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, s.Store)

	reqBody := quickstartApplyRequest{
		Mode: "unknownmode",
		Config: model.DebateConfig{
			Primary: model.NewAgent(model.ProviderAnthropic, "claude-sonnet-5"),
		},
	}

	bodyBytes, _ := json.Marshal(reqBody)
	req, _ := http.NewRequest("POST", srv.URL+"/api/v1/quickstart/apply", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("expected 400 Bad Request, got %d", resp.StatusCode)
	}
}
