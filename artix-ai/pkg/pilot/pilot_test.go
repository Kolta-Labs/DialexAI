package pilot

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestG9_PilotHarnessRejectsIncompleteOrUnverifiedRecords(t *testing.T) {
	harness := NewHarness()

	// Missing TaskID
	err := harness.RecordTask(TaskRecord{
		Repo:              "repo-a",
		Accepted:          true,
		Rounds:            2,
		Tokens:            4500,
		USD:               0.045,
		HumanEditDistance: 12,
		HumanEvaluated:    true,
	})
	if err == nil {
		t.Fatalf("expected error when TaskID is missing")
	}

	// Unverified by human (self-attesting)
	err = harness.RecordTask(TaskRecord{
		TaskID:            "TASK-1",
		Repo:              "repo-a",
		Accepted:          true,
		Rounds:            2,
		Tokens:            4500,
		USD:               0.045,
		HumanEditDistance: 12,
		HumanEvaluated:    false, // self-grading without human reviewer sign-off
	})
	if err == nil {
		t.Fatalf("expected error when task is not human-evaluated")
	}
}

func TestG9_PilotDatasetMeetsHostileReviewerCriteria(t *testing.T) {
	datasetPath := filepath.Join("..", "..", "docs", "pilot", "raw_pilot_results.jsonl")
	data, err := os.ReadFile(datasetPath)
	if err != nil {
		t.Fatalf("published raw pilot dataset must exist at %s: %v", datasetPath, err)
	}

	harness := NewHarness()
	records, err := harness.LoadRawResults(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("failed to parse published raw pilot results: %v", err)
	}

	if len(records) < 20 {
		t.Fatalf("reviewer requires >= 20 real tasks, found only %d", len(records))
	}

	repos := make(map[string]int)
	for i, r := range records {
		if r.TaskID == "" {
			t.Fatalf("record %d missing TaskID", i)
		}
		if r.Repo == "" {
			t.Fatalf("record %d missing Repo", i)
		}
		if r.Rounds <= 0 {
			t.Fatalf("record %d has invalid rounds %d", i, r.Rounds)
		}
		if r.Tokens <= 0 {
			t.Fatalf("record %d has invalid tokens %d", i, r.Tokens)
		}
		if r.USD <= 0.0 {
			t.Fatalf("record %d has invalid USD %f", i, r.USD)
		}
		if !r.HumanEvaluated {
			t.Fatalf("record %d was not human evaluated", i)
		}
		repos[r.Repo]++
	}

	if len(repos) < 2 {
		t.Fatalf("reviewer requires tasks across >= 2 repos, found %d repo(s): %+v", len(repos), repos)
	}
}

// TestR2_8_Harness_RejectsUnjoinableAndVendorRecords asserts that the harness
// rejects records that cannot be joined to an audit event hash, spec ID, commit SHA,
// and ledger entry, or that target vendor-owned repositories.
func TestR2_8_Harness_RejectsUnjoinableAndVendorRecords(t *testing.T) {
	harness := NewHarness()

	// 1. Missing auditRecordHash
	err := harness.RecordTask(TaskRecord{
		TaskID:            "TASK-001",
		Repo:              "gin-gonic/gin",
		Accepted:          true,
		Rounds:            1,
		Tokens:            5000,
		USD:               0.025,
		HumanEvaluated:    true,
		EvaluatedBy:       "dr-evaluator@independent-qa.org [PGP:4A8B7C9D]",
		SpecID:            "STORY-101",
		CommitSHA:         "92fc42f01234567890abcdef1234567890abcdef",
		LedgerRef:         "ledger.json:team-qa:2026-10-01",
		AuditRecordHash:   "", // missing
	})
	if err == nil {
		t.Fatalf("expected error when auditRecordHash is missing")
	}

	// 2. Missing commitSHA
	err = harness.RecordTask(TaskRecord{
		TaskID:            "TASK-002",
		Repo:              "gin-gonic/gin",
		Accepted:          true,
		Rounds:            1,
		Tokens:            5000,
		USD:               0.025,
		HumanEvaluated:    true,
		EvaluatedBy:       "dr-evaluator@independent-qa.org [PGP:4A8B7C9D]",
		SpecID:            "STORY-102",
		CommitSHA:         "", // missing
		LedgerRef:         "ledger.json:team-qa:2026-10-01",
		AuditRecordHash:   "a1b2c3d4e5f60123456789abcdef0123456789abcdef0123456789abcdef0123",
	})
	if err == nil {
		t.Fatalf("expected error when commitSHA is missing")
	}

	// 3. Vendor-owned repository (artix-ai / kritix-ai) must be rejected
	err = harness.RecordTask(TaskRecord{
		TaskID:            "TASK-003",
		Repo:              "artix-ai", // vendor repo
		Accepted:          true,
		Rounds:            1,
		Tokens:            5000,
		USD:               0.025,
		HumanEvaluated:    true,
		EvaluatedBy:       "dr-evaluator@independent-qa.org [PGP:4A8B7C9D]",
		SpecID:            "STORY-103",
		CommitSHA:         "92fc42f01234567890abcdef1234567890abcdef",
		LedgerRef:         "ledger.json:team-qa:2026-10-01",
		AuditRecordHash:   "a1b2c3d4e5f60123456789abcdef0123456789abcdef0123456789abcdef0123",
	})
	if err == nil {
		t.Fatalf("expected error when repo is vendor-owned (artix-ai)")
	}

	// 4. Unidentifiable evaluator alias must be rejected
	err = harness.RecordTask(TaskRecord{
		TaskID:            "TASK-004",
		Repo:              "spf13/cobra",
		Accepted:          true,
		Rounds:            1,
		Tokens:            5000,
		USD:               0.025,
		HumanEvaluated:    true,
		EvaluatedBy:       "auditor-sec-1", // unidentifiable alias
		SpecID:            "STORY-104",
		CommitSHA:         "92fc42f01234567890abcdef1234567890abcdef",
		LedgerRef:         "ledger.json:team-qa:2026-10-01",
		AuditRecordHash:   "a1b2c3d4e5f60123456789abcdef0123456789abcdef0123456789abcdef0123",
	})
	if err == nil {
		t.Fatalf("expected error when EvaluatedBy is an unidentifiable alias")
	}
}

// TestR2_8_PublishedResults_FullTraceabilityAndRawRubric verifies that the published
// pilot dataset has full joinability and published evaluator rubric.
func TestR2_8_PublishedResults_FullTraceabilityAndRawRubric(t *testing.T) {
	rubricPath := filepath.Join("..", "..", "docs", "pilot", "RUBRIC.md")
	rubricData, err := os.ReadFile(rubricPath)
	if err != nil {
		t.Fatalf("published evaluator rubric must exist at %s: %v", rubricPath, err)
	}
	if len(rubricData) < 200 {
		t.Fatalf("evaluator rubric must contain substantial criteria and verification methodology")
	}

	datasetPath := filepath.Join("..", "..", "docs", "pilot", "raw_pilot_results.jsonl")
	data, err := os.ReadFile(datasetPath)
	if err != nil {
		t.Fatalf("published dataset must exist: %v", err)
	}

	harness := NewHarness()
	records, err := harness.LoadRawResults(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("failed to load dataset: %v", err)
	}

	if len(records) < 20 {
		t.Fatalf("expected >= 20 valid joined pilot records, found: %d", len(records))
	}

	repos := make(map[string]int)
	for i, r := range records {
		if r.AuditRecordHash == "" || len(r.AuditRecordHash) != 64 {
			t.Fatalf("record %d has invalid or missing AuditRecordHash: %q", i, r.AuditRecordHash)
		}
		if r.CommitSHA == "" || len(r.CommitSHA) < 7 {
			t.Fatalf("record %d has invalid or missing CommitSHA: %q", i, r.CommitSHA)
		}
		if r.SpecID == "" {
			t.Fatalf("record %d has missing SpecID", i)
		}
		if r.LedgerRef == "" {
			t.Fatalf("record %d has missing LedgerRef", i)
		}
		if r.Repo == "artix-ai" || r.Repo == "kritix-ai" || r.Repo == "socratix-engine" {
			t.Fatalf("record %d targets vendor repo %s; independent repos required", i, r.Repo)
		}
		// Assert deterministic pricing: tokens * 0.000005 to 0.000010 (no 30% arbitrary jump)
		perTokenRate := r.USD / float64(r.Tokens)
		if perTokenRate < 0.0000049 || perTokenRate > 0.0000051 {
			t.Fatalf("record %d has non-deterministic per-token USD rate: %f (tokens %d, usd %f)", i, perTokenRate, r.Tokens, r.USD)
		}
		repos[r.Repo]++
	}

	if len(repos) < 2 {
		t.Fatalf("expected >= 2 independent repos, found: %+v", repos)
	}
}
