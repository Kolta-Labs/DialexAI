package git

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// RoundCheckpoint records a snapshot of code and feedback at an iteration round.
type RoundCheckpoint struct {
	Round      int       `json:"round"`
	CommitHash string    `json:"commitHash"`
	Diff       string    `json:"diff"`
	Feedback   string    `json:"feedback"`
	Approved   bool      `json:"approved"`
	Timestamp  time.Time `json:"timestamp"`
}

// ShadowWorktree represents an isolated, background git worktree.
type ShadowWorktree struct {
	TaskID      string            `json:"taskId"`
	Branch      string            `json:"branch"`
	BaseBranch  string            `json:"baseBranch"`
	Path        string            `json:"path"`
	Checkpoints []RoundCheckpoint `json:"checkpoints"`
	repoRoot    string
}

// WorktreeManager manages isolated shadow execution environments.
type WorktreeManager struct {
	repoRoot    string
	worktreeDir string
}

// NewWorktreeManager creates a worktree manager for a repository.
func NewWorktreeManager(repoRoot string) *WorktreeManager {
	wtDir := filepath.Join(repoRoot, ".artix", "worktrees")
	_ = os.MkdirAll(wtDir, 0755)

	return &WorktreeManager{
		repoRoot:    repoRoot,
		worktreeDir: wtDir,
	}
}

// CreateShadow creates a detached git worktree on a new branch.
func (m *WorktreeManager) CreateShadow(taskID, baseBranch string) (*ShadowWorktree, error) {
	if baseBranch == "" {
		baseBranch = "HEAD"
	}

	branchName := fmt.Sprintf("artix/shadow-%s-%d", taskID, time.Now().Unix())
	targetPath := filepath.Join(m.worktreeDir, taskID)

	// Remove any leftover worktree at target path
	_ = m.runGit("worktree", "remove", "--force", targetPath)
	_ = os.RemoveAll(targetPath)

	// git worktree add -b <branchName> <targetPath> <baseBranch>
	cmd := exec.Command("git", "worktree", "add", "-b", branchName, targetPath, baseBranch)
	cmd.Dir = m.repoRoot
	if out, err := cmd.CombinedOutput(); err != nil {
		return nil, fmt.Errorf("failed to create shadow worktree: %v\nOutput: %s", err, string(out))
	}

	// Symlink .standards into shadow worktree if exists in root
	rootStandards := filepath.Join(m.repoRoot, ".standards")
	if info, err := os.Stat(rootStandards); err == nil && info.IsDir() {
		_ = os.Symlink(rootStandards, filepath.Join(targetPath, ".standards"))
	}

	return &ShadowWorktree{
		TaskID:      taskID,
		Branch:      branchName,
		BaseBranch:  baseBranch,
		Path:        targetPath,
		Checkpoints: make([]RoundCheckpoint, 0),
		repoRoot:    m.repoRoot,
	}, nil
}

// RecordCheckpoint saves an iteration snapshot commit inside the shadow worktree.
func (sw *ShadowWorktree) RecordCheckpoint(round int, feedback string, approved bool) (*RoundCheckpoint, error) {
	driver := NewDriver(sw.Path)
	diff, _ := driver.Diff(false)

	msg := fmt.Sprintf("checkpoint: round %d (Approved: %v)\n\nFeedback: %s", round, approved, feedback)
	hash, err := driver.CommitAll(msg)
	if err != nil {
		return nil, fmt.Errorf("failed to record round checkpoint: %w", err)
	}

	cp := RoundCheckpoint{
		Round:      round,
		CommitHash: hash,
		Diff:       diff,
		Feedback:   feedback,
		Approved:   approved,
		Timestamp:  time.Now(),
	}

	sw.Checkpoints = append(sw.Checkpoints, cp)
	return &cp, nil
}

// MergeInto merges the shadow worktree branch into the target branch.
func (m *WorktreeManager) MergeInto(sw *ShadowWorktree, targetBranch string, squash bool) (string, error) {
	args := []string{"merge"}
	if squash {
		args = append(args, "--squash")
	}
	args = append(args, sw.Branch)

	cmd := exec.Command("git", args...)
	cmd.Dir = m.repoRoot
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("failed to merge shadow branch %s: %v\nOutput: %s", sw.Branch, err, string(out))
	}

	if squash {
		commitCmd := exec.Command("git", "commit", "-m", fmt.Sprintf("feat: merge shadow worktree %s", sw.TaskID))
		cmd.Dir = m.repoRoot
		_ = commitCmd.Run()
	}

	// Get latest commit hash
	revCmd := exec.Command("git", "rev-parse", "HEAD")
	revCmd.Dir = m.repoRoot
	hashBytes, _ := revCmd.Output()
	return strings.TrimSpace(string(hashBytes)), nil
}

// Remove cleans up the shadow worktree and removes its directory.
func (m *WorktreeManager) Remove(sw *ShadowWorktree) error {
	cmd := exec.Command("git", "worktree", "remove", "--force", sw.Path)
	cmd.Dir = m.repoRoot
	_ = cmd.Run()
	_ = os.RemoveAll(sw.Path)

	// Prune dead worktree metadata
	pruneCmd := exec.Command("git", "worktree", "prune")
	pruneCmd.Dir = m.repoRoot
	return pruneCmd.Run()
}

func (m *WorktreeManager) runGit(args ...string) error {
	cmd := exec.Command("git", args...)
	cmd.Dir = m.repoRoot
	return cmd.Run()
}
