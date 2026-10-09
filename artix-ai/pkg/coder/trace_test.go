package coder

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"artix/pkg/persona"
	"artix/pkg/policy"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
)

func TestPerJobRoundTrace_WrittenNextToAuditLog(t *testing.T) {
	tempDir, d := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	_ = os.WriteFile(filepath.Join(tempDir, "main.go"), []byte("package main\n\nfunc main() {}\n"), 0644)
	_, _ = d.CommitAll("initial commit")

	reg := persona.NewRegistry(tempDir)
	dc, err := NewDomainCoder("backend_engineer", reg)
	if err != nil {
		t.Fatalf("NewDomainCoder failed: %v", err)
	}

	rev := reviewer.NewAdversarialReviewer(reg)
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(dc, rev, d, box)

	story := &spec.StorySpec{
		ID:           "TRACE-JOB-1",
		Title:        "Testing round trace recording",
		TestCommands: []string{"go test -v ./..."},
	}

	rc, _ := repo.DetectContext(tempDir)

	opts := &LoopOptions{
		MaxRounds:             1,
		Autonomy:              AutonomySupervised,
		TestCommandsConfirmed: true,
		TestTimeout:           5 * time.Second,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/main.go b/main.go\n--- a/main.go\n+++ b/main.go\n@@ -2,1 +2,2 @@\n func main() {}\n+// trace tested\n"
		},
	}

	res := coord.Run(context.Background(), story, rc, nil, nil, opts)
	_ = res

	// Verify trace file written next to the audit log
	auditLogPath := policy.EffectiveAuditLogPath(tempDir)
	tracePath := filepath.Join(filepath.Dir(auditLogPath), "trace.jsonl")

	data, err := os.ReadFile(tracePath)
	if err != nil {
		t.Fatalf("expected trace file to exist at %s: %v", tracePath, err)
	}

	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) == 0 || lines[0] == "" {
		t.Fatalf("trace file is empty")
	}

	var trace RoundTrace
	if err := json.Unmarshal([]byte(lines[0]), &trace); err != nil {
		t.Fatalf("failed to parse trace entry: %v", err)
	}

	if trace.TaskID != "TRACE-JOB-1" {
		t.Errorf("expected TaskID TRACE-JOB-1, got %q", trace.TaskID)
	}
	if trace.Round != 1 {
		t.Errorf("expected round 1, got %d", trace.Round)
	}
	if trace.PromptHash == "" {
		t.Errorf("expected prompt hash to be non-empty")
	}
	if trace.PatchHash == "" {
		t.Errorf("expected patch hash to be non-empty")
	}
	if trace.SandboxCommand == "" {
		t.Errorf("expected sandbox command to be non-empty")
	}
}
