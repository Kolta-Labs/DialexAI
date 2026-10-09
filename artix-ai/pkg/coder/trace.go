package coder

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"artix/pkg/policy"
)

// RoundTrace records diagnostic details for an iteration round of a convergence job.
type RoundTrace struct {
	TaskID          string    `json:"taskId"`
	Round           int       `json:"round"`
	PromptHash      string    `json:"promptHash"`
	PatchHash       string    `json:"patchHash"`
	Patch           string    `json:"patch,omitempty"`
	SandboxCommand  string    `json:"sandboxCommand"`
	ExitCode        int       `json:"exitCode"`
	ReviewerVerdict string    `json:"reviewerVerdict"`
	BlockingIssues  []string  `json:"blockingIssues,omitempty"`
	Tokens          int       `json:"tokens"`
	Cost            float64   `json:"cost"`
	Phase           string    `json:"phase,omitempty"`
	Timestamp       time.Time `json:"timestamp"`
}

var traceMu sync.Mutex

// HashContent returns the hex SHA256 hash of a string.
func HashContent(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

// AppendRoundTrace writes a RoundTrace entry to trace.jsonl next to the audit log.
func AppendRoundTrace(workspaceDir string, trace *RoundTrace) error {
	traceMu.Lock()
	defer traceMu.Unlock()

	auditLogPath := policy.EffectiveAuditLogPath(workspaceDir)
	tracePath := filepath.Join(filepath.Dir(auditLogPath), "trace.jsonl")
	_ = os.MkdirAll(filepath.Dir(tracePath), 0755)

	if trace.Timestamp.IsZero() {
		trace.Timestamp = time.Now().UTC()
	}

	data, err := json.Marshal(trace)
	if err != nil {
		return err
	}

	f, err := os.OpenFile(tracePath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return err
	}
	defer f.Close()

	_, err = f.Write(append(data, '\n'))
	return err
}

// ReadRoundTraces reads all RoundTrace entries for a task from trace.jsonl next to the audit log.
func ReadRoundTraces(workspaceDir string, taskID string) ([]RoundTrace, error) {
	traceMu.Lock()
	defer traceMu.Unlock()

	auditLogPath := policy.EffectiveAuditLogPath(workspaceDir)
	tracePath := filepath.Join(filepath.Dir(auditLogPath), "trace.jsonl")

	data, err := os.ReadFile(tracePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}

	var traces []RoundTrace
	for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		var tr RoundTrace
		if err := json.Unmarshal([]byte(line), &tr); err == nil {
			if taskID == "" || tr.TaskID == taskID {
				traces = append(traces, tr)
			}
		}
	}
	return traces, nil
}
