package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"dialex/pkg/decomposition"
	"dialex/pkg/store"
)

func TestDecomposeProblemHandler(t *testing.T) {
	tmpDir := t.TempDir()
	st, err := store.New(tmpDir)
	if err != nil {
		t.Fatalf("failed to create store: %v", err)
	}

	srv := NewServer(st)
	token, err := srv.issueToken("test_user")
	if err != nil {
		t.Fatalf("failed to generate test token: %v", err)
	}

	payload := decomposition.DecompositionRequest{
		Topic:   "Should we migrate our monolith to microservices?",
		Context: "High traffic e-commerce platform with 100k daily orders.",
	}
	body, _ := json.Marshal(payload)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/discussions/decompose", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()

	srv.Router().ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var res decomposition.DecompositionResult
	if err := json.Unmarshal(rec.Body.Bytes(), &res); err != nil {
		t.Fatalf("failed to unmarshal response json: %v", err)
	}

	if res.Topic != payload.Topic {
		t.Fatalf("expected topic %q, got %q", payload.Topic, res.Topic)
	}
	if len(res.PerspectiveA.Axes) == 0 || len(res.PerspectiveB.Axes) == 0 {
		t.Fatalf("expected axes in both perspectives, got A=%d, B=%d", len(res.PerspectiveA.Axes), len(res.PerspectiveB.Axes))
	}
}
