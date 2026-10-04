package git

import (
	"fmt"
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

func TestWorktreeManager_GarbageCollect(t *testing.T) {
	tempRepo := t.TempDir()

	runCmd := func(dir string, name string, args ...string) {
		cmd := exec.Command(name, args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("cmd %s %v failed: %v\nOutput: %s", name, args, err, string(out))
		}
	}

	runCmd(tempRepo, "git", "init", "-b", "main")
	runCmd(tempRepo, "git", "config", "user.name", "Test Admin")
	runCmd(tempRepo, "git", "config", "user.email", "admin@test.local")
	_ = os.WriteFile(filepath.Join(tempRepo, "README.md"), []byte("# Root\n"), 0644)
	runCmd(tempRepo, "git", "add", ".")
	runCmd(tempRepo, "git", "commit", "-m", "initial commit")

	mgr := NewWorktreeManager(tempRepo)

	// Create 2 worktrees
	sw1, err := mgr.CreateShadow("task-old-01", "main")
	if err != nil {
		t.Fatalf("failed to create sw1: %v", err)
	}
	sw2, err := mgr.CreateShadow("task-old-02", "main")
	if err != nil {
		t.Fatalf("failed to create sw2: %v", err)
	}

	// GC with 0 duration should prune all worktrees
	pruned, err := mgr.GarbageCollect(0)
	if err != nil {
		t.Fatalf("GarbageCollect failed: %v", err)
	}
	if pruned != 2 {
		t.Errorf("expected 2 pruned worktrees, got %d", pruned)
	}

	if _, err := os.Stat(sw1.Path); !os.IsNotExist(err) {
		t.Errorf("expected sw1 to be removed")
	}
	if _, err := os.Stat(sw2.Path); !os.IsNotExist(err) {
		t.Errorf("expected sw2 to be removed")
	}
}

func TestWorktreeManager_MergeWithVerificationRollback(t *testing.T) {
	tempRepo := t.TempDir()

	runCmd := func(dir string, name string, args ...string) {
		cmd := exec.Command(name, args...)
		cmd.Dir = dir
		if out, err := cmd.CombinedOutput(); err != nil {
			t.Fatalf("cmd %s %v failed: %v\nOutput: %s", name, args, err, string(out))
		}
	}

	runCmd(tempRepo, "git", "init", "-b", "main")
	runCmd(tempRepo, "git", "config", "user.name", "Test Admin")
	runCmd(tempRepo, "git", "config", "user.email", "admin@test.local")
	_ = os.WriteFile(filepath.Join(tempRepo, "README.md"), []byte("# Root\n"), 0644)
	runCmd(tempRepo, "git", "add", ".")
	runCmd(tempRepo, "git", "commit", "-m", "initial commit")

	revCmd := exec.Command("git", "rev-parse", "HEAD")
	revCmd.Dir = tempRepo
	initialOut, _ := revCmd.Output()
	initialCommit := strings.TrimSpace(string(initialOut))

	mgr := NewWorktreeManager(tempRepo)
	sw, err := mgr.CreateShadow("task-verify-01", "main")
	if err != nil {
		t.Fatalf("CreateShadow failed: %v", err)
	}

	_ = os.WriteFile(filepath.Join(sw.Path, "broken.txt"), []byte("this breaks tests\n"), 0644)
	_, _ = sw.RecordCheckpoint(1, "added broken file", true)

	// Attempt merge with a failing test verification command
	failingRunner := func(cmdStr string) error {
		return fmt.Errorf("test suite failed: synthetic test failure")
	}

	_, err = mgr.MergeIntoWithVerification(sw, "main", true, []string{"fake-test"}, failingRunner)
	if err == nil {
		t.Fatal("expected MergeIntoWithVerification to fail when verification fails")
	}

	// Verify rollback happened: HEAD must be restored to initialCommit
	revCmd2 := exec.Command("git", "rev-parse", "HEAD")
	revCmd2.Dir = tempRepo
	postOut, _ := revCmd2.Output()
	postCommit := strings.TrimSpace(string(postOut))

	if postCommit != initialCommit {
		t.Fatalf("expected rollback to %s, but HEAD is at %s", initialCommit, postCommit)
	}

	// Verify broken.txt does NOT exist in main repo
	if _, err := os.Stat(filepath.Join(tempRepo, "broken.txt")); !os.IsNotExist(err) {
		t.Fatal("broken.txt should not exist in working directory after rollback")
	}
}

