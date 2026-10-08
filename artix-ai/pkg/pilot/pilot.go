package pilot

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
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
	SpecID            string    `json:"specId"`
	CommitSHA         string    `json:"commitSHA"`
	AuditRecordHash   string    `json:"auditRecordHash"`
	LedgerRef         string    `json:"ledgerRef"`
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

// ValidateAndJoinRecord verifies the complete joinability and independence of a task record.
func ValidateAndJoinRecord(r TaskRecord) error {
	if r.TaskID == "" {
		return errors.New("pilot task record missing taskId")
	}
	if r.Repo == "" {
		return errors.New("pilot task record missing repo")
	}
	repoLower := strings.ToLower(strings.TrimSpace(r.Repo))
	if repoLower == "artix-ai" || repoLower == "kritix-ai" || repoLower == "socratix-engine" || strings.HasPrefix(repoLower, "vendor/") {
		return fmt.Errorf("vendor repo %q disallowed: pilot tasks must target independent repositories", r.Repo)
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
	if r.EvaluatedBy == "" || strings.HasPrefix(r.EvaluatedBy, "auditor-sec-") || strings.HasPrefix(r.EvaluatedBy, "user-") {
		return fmt.Errorf("evaluator %q is an unidentifiable alias; verifiable evaluator identity required", r.EvaluatedBy)
	}
	if r.SpecID == "" {
		return errors.New("pilot task record missing specId (cannot join to spec)")
	}
	if r.CommitSHA == "" || len(r.CommitSHA) < 7 {
		return fmt.Errorf("pilot task record missing valid commitSHA: %q (cannot join to commit)", r.CommitSHA)
	}
	if r.AuditRecordHash == "" || len(r.AuditRecordHash) != 64 {
		return fmt.Errorf("pilot task record missing valid auditRecordHash: %q (cannot join to audit log)", r.AuditRecordHash)
	}
	if r.LedgerRef == "" {
		return errors.New("pilot task record missing ledgerRef (cannot join to ledger entry)")
	}
	return nil
}

// RecordTask registers a verified task run into the harness, strictly rejecting unverified/self-attested entries.
func (h *Harness) RecordTask(r TaskRecord) error {
	if err := ValidateAndJoinRecord(r); err != nil {
		return err
	}
	if r.Timestamp.IsZero() {
		r.Timestamp = time.Now()
	}

	h.mu.Lock()
	defer h.mu.Unlock()
	h.records = append(h.records, r)
	return nil
}

// LoadRawResults parses newline-delimited JSON raw records from an external reader,
// strictly discarding rows that cannot be joined or fail validation.
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
		// Rows that cannot be joined or fail criteria are discarded
		if err := ValidateAndJoinRecord(rec); err != nil {
			continue
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
