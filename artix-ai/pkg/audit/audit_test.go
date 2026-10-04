package audit

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"artix/pkg/policy"
)

func TestAuditEventEmissionAndHashChaining(t *testing.T) {
	tempDir := t.TempDir()
	logger := NewLogger(tempDir)
	logger.SetSigningKey("test-signing-secret")

	// Emit 3 events
	events := []AuditEvent{
		{
			EventType:    EventSpecDeliberation,
			Status:       "SUCCESS",
			ModelID:      "claude-3-7-sonnet",
			PromptHash:   "abc123prompt",
			ResponseHash: "def456resp",
			StorySpecID:  "STORY-101",
			Cost:         0.042,
		},
		{
			EventType:   EventCodeConvergence,
			Status:      "SUCCESS",
			ModelID:     "claude-3-7-sonnet",
			StorySpecID: "STORY-101",
			DiffHash:    "diff789hash",
			Round:       1,
			Cost:        0.125,
		},
		{
			EventType:   EventReviewerVerdict,
			Status:      "SUCCESS",
			ModelID:     "gpt-4o",
			StorySpecID: "STORY-101",
			DiffHash:    "diff789hash",
			Cost:        0.015,
		},
	}

	for _, e := range events {
		if err := logger.Emit(e); err != nil {
			t.Fatalf("Emit failed: %v", err)
		}
	}

	logFile := logger.LogPath()
	res, err := VerifyLog(logFile, "test-signing-secret")
	if err != nil {
		t.Fatalf("VerifyLog failed on valid chain: %v", err)
	}

	if res.ValidRecords != 3 {
		t.Errorf("expected 3 valid records, got %d", res.ValidRecords)
	}
}

func TestTamperDetectionModifiedPayload(t *testing.T) {
	tempDir := t.TempDir()
	logger := NewLogger(tempDir)

	_ = logger.Emit(AuditEvent{EventType: EventCodeConvergence, Status: "SUCCESS", Cost: 0.10})
	_ = logger.Emit(AuditEvent{EventType: EventReviewerVerdict, Status: "SUCCESS", Cost: 0.05})

	logFile := logger.LogPath()
	data, err := os.ReadFile(logFile)
	if err != nil {
		t.Fatalf("failed to read log: %v", err)
	}

	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	var evt AuditEvent
	_ = json.Unmarshal([]byte(lines[0]), &evt)

	// Tamper with the cost
	evt.Cost = 0.00
	tamperedLine, _ := json.Marshal(evt)
	lines[0] = string(tamperedLine)

	_ = os.WriteFile(logFile, []byte(strings.Join(lines, "\n")+"\n"), 0644)

	_, err = VerifyLog(logFile)
	if err == nil {
		t.Fatalf("expected tamper detection on modified payload, but verification succeeded")
	}
	if !strings.Contains(err.Error(), "tampering detected") {
		t.Errorf("expected 'tampering detected' in error, got: %v", err)
	}
}

func TestTamperDetectionBrokenChain(t *testing.T) {
	tempDir := t.TempDir()
	logger := NewLogger(tempDir)

	_ = logger.Emit(AuditEvent{EventType: EventSpecDeliberation, Status: "SUCCESS"})
	_ = logger.Emit(AuditEvent{EventType: EventCodeConvergence, Status: "SUCCESS"})
	_ = logger.Emit(AuditEvent{EventType: EventReviewerVerdict, Status: "SUCCESS"})

	logFile := logger.LogPath()
	data, _ := os.ReadFile(logFile)
	lines := strings.Split(strings.TrimSpace(string(data)), "\n")

	// Delete the middle record to break the chain
	newLines := []string{lines[0], lines[2]}
	_ = os.WriteFile(logFile, []byte(strings.Join(newLines, "\n")+"\n"), 0644)

	_, err := VerifyLog(logFile)
	if err == nil {
		t.Fatalf("expected tamper detection on broken hash chain, but verification succeeded")
	}
	if !strings.Contains(err.Error(), "broken hash chain") {
		t.Errorf("expected 'broken hash chain' in error, got: %v", err)
	}
}

func TestAuditLogIgnoresEnvOverrideInEnterpriseMode(t *testing.T) {
	tempDir := t.TempDir()
	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("ARTIX_AUDIT_LOG", "/tmp/untrusted_audit.log")

	logger := NewLogger(tempDir)
	expected := filepath.Join(tempDir, ".artix", "audit.jsonl")
	if logger.LogPath() != expected {
		t.Errorf("expected audit log to ignore ARTIX_AUDIT_LOG in enterprise mode; expected %s, got %s", expected, logger.LogPath())
	}
}

func TestRemoteHTTPSink(t *testing.T) {
	received := make(chan AuditEvent, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var evt AuditEvent
		if err := json.NewDecoder(r.Body).Decode(&evt); err == nil {
			received <- evt
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	tempDir := t.TempDir()
	logger := NewLogger(tempDir)
	logger.AddRemoteSink(policy.RemoteSinkConfig{
		Type:     "http",
		Endpoint: server.URL,
	})

	err := logger.Emit(AuditEvent{
		EventType:   EventSpecDeliberation,
		Status:      "SUCCESS",
		StorySpecID: "STORY-REMOTE-1",
	})
	if err != nil {
		t.Fatalf("Emit failed: %v", err)
	}

	select {
	case evt := <-received:
		if evt.StorySpecID != "STORY-REMOTE-1" {
			t.Errorf("remote sink received unexpected payload: %+v", evt)
		}
	case <-time.After(2 * time.Second):
		t.Errorf("timeout waiting for remote HTTP sink delivery")
	}
}
