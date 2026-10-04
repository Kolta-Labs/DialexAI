package triage

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"kritix/pkg/driver"
)

// StepSnapshot records the browser and network state around a single action.
type StepSnapshot struct {
	StepNumber     int                   `json:"step_number"`
	Action         driver.Action         `json:"action"`
	URL            string                `json:"url"`
	Title          string                `json:"title"`
	ScreenshotB64  string                `json:"screenshot_b64,omitempty"`
	ConsoleLogs    []string              `json:"console_logs,omitempty"`
	NetworkEvents  []driver.NetworkEvent `json:"network_events,omitempty"`
	Timestamp      time.Time             `json:"timestamp"`
}

// SessionTrace records the end-to-end audit log of an exploratory or test run.
type SessionTrace struct {
	SessionID     string                 `json:"session_id"`
	TargetURL     string                 `json:"target_url"`
	StartTime     time.Time              `json:"start_time"`
	EndTime       time.Time              `json:"end_time"`
	Duration      time.Duration          `json:"duration"`
	TotalSteps    int                    `json:"total_steps"`
	Snapshots     []StepSnapshot         `json:"snapshots"`
	ConsoleErrors []string               `json:"console_errors"`
	NetworkErrors []driver.NetworkEvent  `json:"network_errors"`
	DiscoveredBugs []DefectReport        `json:"discovered_bugs"`
	Success       bool                   `json:"success"`
}

// SessionRecorder records step-by-step artifacts during browser execution.
type SessionRecorder struct {
	mu            sync.RWMutex
	sessionID     string
	targetURL     string
	startTime     time.Time
	snapshots     []StepSnapshot
	consoleErrors []string
	networkErrors []driver.NetworkEvent
	bugs          []DefectReport
}

// NewSessionRecorder creates a new recorder instance.
func NewSessionRecorder(sessionID, targetURL string) *SessionRecorder {
	return &SessionRecorder{
		sessionID: sessionID,
		targetURL: targetURL,
		startTime: time.Now(),
		snapshots: make([]StepSnapshot, 0),
	}
}

// RecordStep records an action and the resulting browser state.
func (r *SessionRecorder) RecordStep(action driver.Action, state *driver.BrowserState) {
	r.mu.Lock()
	defer r.mu.Unlock()

	stepNum := len(r.snapshots) + 1

	snapshot := StepSnapshot{
		StepNumber:    stepNum,
		Action:        action,
		URL:           state.URL,
		Title:         state.Title,
		ScreenshotB64: state.ScreenshotB64,
		ConsoleLogs:   state.ConsoleLogs,
		NetworkEvents: state.NetworkActivity,
		Timestamp:     time.Now(),
	}

	r.snapshots = append(r.snapshots, snapshot)

	// Scan for console errors
	for _, log := range state.ConsoleLogs {
		if containsErrorKeyword(log) {
			r.consoleErrors = append(r.consoleErrors, log)
		}
	}

	// Scan for network errors (5xx status)
	for _, net := range state.NetworkActivity {
		if net.StatusCode >= 500 || net.Error != "" {
			r.networkErrors = append(r.networkErrors, net)
		}
	}
}

// RegisterDefect logs an identified defect during the session.
func (r *SessionRecorder) RegisterDefect(report DefectReport) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.bugs = append(r.bugs, report)
}

// Finalize produces the complete SessionTrace.
func (r *SessionRecorder) Finalize() *SessionTrace {
	r.mu.RLock()
	defer r.mu.RUnlock()

	now := time.Now()
	return &SessionTrace{
		SessionID:      r.sessionID,
		TargetURL:      r.targetURL,
		StartTime:      r.startTime,
		EndTime:        now,
		Duration:       now.Sub(r.startTime),
		TotalSteps:     len(r.snapshots),
		Snapshots:      r.snapshots,
		ConsoleErrors:  r.consoleErrors,
		NetworkErrors:  r.networkErrors,
		DiscoveredBugs: r.bugs,
		Success:        len(r.bugs) == 0 && len(r.networkErrors) == 0,
	}
}

// ExportJSON writes the session trace to a local JSON file.
func (r *SessionRecorder) ExportJSON(dirPath string) (string, error) {
	trace := r.Finalize()
	if err := os.MkdirAll(dirPath, 0755); err != nil {
		return "", err
	}

	filePath := filepath.Join(dirPath, fmt.Sprintf("trace_%s.json", r.sessionID))
	data, err := json.MarshalIndent(trace, "", "  ")
	if err != nil {
		return "", err
	}

	if err := os.WriteFile(filePath, data, 0644); err != nil {
		return "", err
	}
	return filePath, nil
}

func containsErrorKeyword(s string) bool {
	lower := s
	return (len(lower) > 0) && (
		// Check common JS / console error markers
		stringContains(lower, "Uncaught") ||
			stringContains(lower, "TypeError") ||
			stringContains(lower, "ReferenceError") ||
			stringContains(lower, "500 Internal") ||
			stringContains(lower, "Unhandled Promise Rejection"))
}

func stringContains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || findSubstr(s, substr))
}

func findSubstr(s, substr string) bool {
	for i := 0; i+len(substr) <= len(s); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
