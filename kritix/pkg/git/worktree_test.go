package git

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestWorktreeManager_Lifecycle(t *testing.T) {
	tempRepo, err := os.MkdirTemp("", "kritix-wt-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempRepo)

	runCmd := func(dir string, name string, args ...string) {
		cmd := exec.Command(name, args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("cmd %s %v failed: %v\nOutput: %s", name, args, err, string(out))
		}
	}

	// 1. Initialize repo
	runCmd(tempRepo, "git", "init", "-b", "main")
	runCmd(tempRepo, "git", "config", "user.name", "Test Admin")
	runCmd(tempRepo, "git", "config", "user.email", "admin@test.local")
	_ = os.WriteFile(filepath.Join(tempRepo, "README.md"), []byte("# Root Repo\n"), 0644)
	runCmd(tempRepo, "git", "add", ".")
	runCmd(tempRepo, "git", "commit", "-m", "initial commit")

	mgr := NewWorktreeManager(tempRepo)

	// 2. Create shadow worktree
	taskID := "task-xyz"
	sw, err := mgr.CreateShadow(taskID, "main")
	if err != nil {
		t.Fatalf("failed to create shadow worktree: %v", err)
	}

	if _, err := os.Stat(sw.Path); os.IsNotExist(err) {
		t.Fatalf("shadow worktree path does not exist: %s", sw.Path)
	}

	// 3. Make changes and record checkpoint 1 (Rejected)
	_ = os.WriteFile(filepath.Join(sw.Path, "feature.txt"), []byte("v1"), 0644)
	cp1, err := sw.RecordCheckpoint(1, "Missing tests", false)
	if err != nil {
		t.Fatalf("failed to record checkpoint 1: %v", err)
	}
	if cp1.Round != 1 || cp1.Approved != false || cp1.CommitHash == "" {
		t.Errorf("unexpected checkpoint 1: %+v", cp1)
	}

	// 4. Make changes and record checkpoint 2 (Approved)
	_ = os.WriteFile(filepath.Join(sw.Path, "feature.txt"), []byte("v2 with tests"), 0644)
	cp2, err := sw.RecordCheckpoint(2, "Tests pass", true)
	if err != nil {
		t.Fatalf("failed to record checkpoint 2: %v", err)
	}
	if cp2.Round != 2 || cp2.Approved != true {
		t.Errorf("unexpected checkpoint 2: %+v", cp2)
	}

	if len(sw.Checkpoints) != 2 {
		t.Errorf("expected 2 checkpoints recorded, got %d", len(sw.Checkpoints))
	}

	// 5. Merge into main
	mergeHash, err := mgr.MergeInto(sw, "main", true)
	if err != nil {
		t.Fatalf("failed to merge shadow into main: %v", err)
	}
	if mergeHash == "" {
		t.Errorf("expected non-empty merge hash")
	}

	// Verify merged file exists in root repo
	content, err := os.ReadFile(filepath.Join(tempRepo, "feature.txt"))
	if err != nil || strings.TrimSpace(string(content)) != "v2 with tests" {
		t.Fatalf("merged content mismatch: %s (err: %v)", string(content), err)
	}

	// 6. Clean up shadow worktree
	if err := mgr.Remove(sw); err != nil {
		t.Fatalf("failed to remove shadow worktree: %v", err)
	}

	if _, err := os.Stat(sw.Path); !os.IsNotExist(err) {
		t.Errorf("expected shadow path to be deleted")
	}
}
