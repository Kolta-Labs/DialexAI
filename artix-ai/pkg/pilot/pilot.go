package pilot

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"
)

// TaskRecord represents an independently evaluated task execution record.
type TaskRecord struct {
	TaskID            string    `json:"taskId"`
	Repo              string    `json:"repo"`
	Accepted          bool      `json:"accepted"`
	Rounds            int       `json:"rounds"`
	Tokens            int64     `json:"tokens"`
	USD               float64   `json:"usd"`
	HumanEditDistance int       `json:"humanEditDistance"`
	FalseRejection    bool      `json:"falseRejection"`
	HumanEvaluated    bool      `json:"humanEvaluated"`
	EvaluatedBy       string    `json:"evaluatedBy,omitempty"`
	Timestamp         time.Time `json:"timestamp"`
}

// Harness manages recording, validating, and streaming raw pilot task metrics.
type Harness struct {
	mu      sync.RWMutex
	records []TaskRecord
}

// NewHarness instantiates a pilot harness.
func NewHarness() *Harness {
	return &Harness{}
}

// RecordTask registers a verified task run into the harness, strictly rejecting unverified/self-attested entries.
func (h *Harness) RecordTask(r TaskRecord) error {
	if r.TaskID == "" {
		return errors.New("pilot task record missing taskId")
	}
	if r.Repo == "" {
		return errors.New("pilot task record missing repo")
	}
	if r.Rounds <= 0 {
		return errors.New("pilot task record rounds must be > 0")
	}
	if r.Tokens <= 0 {
		return errors.New("pilot task record tokens must be > 0")
	}
	if r.USD <= 0.0 {
		return errors.New("pilot task record USD must be > 0")
	}
	if !r.HumanEvaluated {
		return errors.New("pilot task record must be human evaluated (self-grading disallowed)")
	}
	if r.Timestamp.IsZero() {
		r.Timestamp = time.Now()
	}

	h.mu.Lock()
	defer h.mu.Unlock()
	h.records = append(h.records, r)
	return nil
}

// LoadRawResults parses newline-delimited JSON raw records from an external reader.
func (h *Harness) LoadRawResults(r io.Reader) ([]TaskRecord, error) {
	scanner := bufio.NewScanner(r)
	var records []TaskRecord
	for scanner.Scan() {
		line := scanner.Text()
		if len(line) == 0 || line[0] == '#' {
			continue
		}
		var rec TaskRecord
		if err := json.Unmarshal([]byte(line), &rec); err != nil {
			return nil, fmt.Errorf("invalid json in raw pilot results: %w", err)
		}
		records = append(records, rec)
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	return records, nil
}

// ExportRawResults writes the raw task records as JSON lines to the destination writer.
func (h *Harness) ExportRawResults(w io.Writer) error {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, rec := range h.records {
		data, err := json.Marshal(rec)
		if err != nil {
			return err
		}
		if _, err := fmt.Fprintf(w, "%s\n", data); err != nil {
			return err
		}
	}
	return nil
}
