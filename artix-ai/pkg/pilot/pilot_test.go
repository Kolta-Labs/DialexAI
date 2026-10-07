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
