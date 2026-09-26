package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"dialex/pkg/model"
	"dialex/pkg/persona"
)

func TestPersonaDNAHandlers(t *testing.T) {
	s, _ := newTestServer(t)
	ts := httptest.NewServer(s.Router())
	defer ts.Close()

	token, err := s.issueToken("testuser")
	if err != nil {
		t.Fatalf("failed to issue token: %v", err)
	}

	// 1. GET /api/v1/personas/heuristics
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/personas/heuristics", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET /heuristics failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var heuristics []model.HeuristicRule
	if err := json.NewDecoder(resp.Body).Decode(&heuristics); err != nil {
		t.Fatalf("failed to decode heuristics: %v", err)
	}
	resp.Body.Close()
	if len(heuristics) < 5 {
		t.Errorf("expected at least 5 heuristics, got %d", len(heuristics))
	}

	// 2. POST /api/v1/personas/dna/compile
	sampleDNA := persona.GetBuiltinPersonaDNA("the-risk-analyst")
	compileBody, _ := json.Marshal(CompileDNARequest{DNA: *sampleDNA})
	req, _ = http.NewRequest("POST", ts.URL+"/api/v1/personas/dna/compile", bytes.NewReader(compileBody))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST /dna/compile failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var compileResp CompileDNAResponse
	if err := json.NewDecoder(resp.Body).Decode(&compileResp); err != nil {
		t.Fatalf("failed to decode compile response: %v", err)
	}
	resp.Body.Close()
	if len(compileResp.CompiledPrompt) == 0 {
		t.Error("compiled prompt should not be empty")
	}

	// 3. POST /api/v1/personas/{id}/dna (Save DNA)
	dnaPayload, _ := json.Marshal(sampleDNA)
	req, _ = http.NewRequest("POST", ts.URL+"/api/v1/personas/the-risk-analyst/dna", bytes.NewReader(dnaPayload))
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST /personas/{id}/dna failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	resp.Body.Close()

	// 4. GET /api/v1/personas/{id}/dna (Verify saved DNA)
	req, _ = http.NewRequest("GET", ts.URL+"/api/v1/personas/the-risk-analyst/dna", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET /personas/{id}/dna failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var retrievedDNA model.PersonaDNA
	if err := json.NewDecoder(resp.Body).Decode(&retrievedDNA); err != nil {
		t.Fatalf("failed to decode retrieved DNA: %v", err)
	}
	resp.Body.Close()
	if retrievedDNA.Name != sampleDNA.Name {
		t.Errorf("expected name %s, got %s", sampleDNA.Name, retrievedDNA.Name)
	}

	// 5. GET /api/v1/personas/{id}/dna/export?format=yaml
	req, _ = http.NewRequest("GET", ts.URL+"/api/v1/personas/the-risk-analyst/dna/export?format=yaml", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET /dna/export failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var exportBuf bytes.Buffer
	_, _ = exportBuf.ReadFrom(resp.Body)
	resp.Body.Close()
	if exportBuf.Len() == 0 {
		t.Error("exported YAML should not be empty")
	}

	// 6. POST /api/v1/personas/dna/import
	req, _ = http.NewRequest("POST", ts.URL+"/api/v1/personas/dna/import?format=yaml", bytes.NewReader(exportBuf.Bytes()))
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST /dna/import failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var importedDNA model.PersonaDNA
	if err := json.NewDecoder(resp.Body).Decode(&importedDNA); err != nil {
		t.Fatalf("failed to decode imported DNA: %v", err)
	}
	resp.Body.Close()
	if importedDNA.ID != sampleDNA.ID {
		t.Errorf("expected imported ID %s, got %s", sampleDNA.ID, importedDNA.ID)
	}
}
