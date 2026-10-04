package triage

import (
	"os"
	"testing"
	"time"

	"kritix/pkg/driver"
)

func TestSessionRecorderLifecycle(t *testing.T) {
	recorder := NewSessionRecorder("test-session-123", "https://example.com")

	action := driver.Action{
		Type:        driver.ActionClick,
		TargetRole:  "button",
		TargetText:  "Submit",
		Description: "Clicked Submit button",
		Timestamp:   time.Now(),
	}

	state := &driver.BrowserState{
		URL:   "https://example.com/checkout",
		Title: "Checkout Page",
		ConsoleLogs: []string{
			"Initialized checkout analytics",
			"Uncaught TypeError: Cannot read properties of undefined (reading 'process')",
		},
		NetworkActivity: []driver.NetworkEvent{
			{URL: "https://example.com/api/pay", StatusCode: 500, Method: "POST"},
		},
	}

	recorder.RecordStep(action, state)

	trace := recorder.Finalize()
	if trace.SessionID != "test-session-123" {
		t.Errorf("expected session-id test-session-123, got %s", trace.SessionID)
	}
	if trace.TotalSteps != 1 {
		t.Errorf("expected 1 step, got %d", trace.TotalSteps)
	}
	if len(trace.ConsoleErrors) != 1 {
		t.Errorf("expected 1 console error flagged, got %d", len(trace.ConsoleErrors))
	}
	if len(trace.NetworkErrors) != 1 {
		t.Errorf("expected 1 network error flagged, got %d", len(trace.NetworkErrors))
	}
	if trace.Success {
		t.Errorf("trace should be marked failed due to 500 error and JS crash")
	}

	// Test ExportJSON
	tmpDir, err := os.MkdirTemp("", "kritix_recorder_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	filePath, err := recorder.ExportJSON(tmpDir)
	if err != nil {
		t.Fatalf("failed to export trace JSON: %v", err)
	}

	info, err := os.Stat(filePath)
	if err != nil || info.Size() == 0 {
		t.Errorf("exported file does not exist or is empty: %s", filePath)
	}
}
