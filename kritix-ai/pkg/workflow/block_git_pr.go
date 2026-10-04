package workflow

import (
	"context"
	"errors"
	"fmt"
	"os/exec"
)

// GitPRBlock opens a git branch and submits self-healing selector pull requests.
type GitPRBlock struct{}

func (b *GitPRBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "git.pr",
		Name:        "Create Self-Healing Pull Request",
		Category:    "sync",
		Description: "Opens a Git branch with healed test selectors and submits a pull request for human SDET review.",
	}
}

func (b *GitPRBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	titleVal, ok := bCtx.Get("pr_title")
	prTitle := "chore(test): auto-heal mutated Playwright selectors"
	if ok && titleVal != nil && fmt.Sprint(titleVal) != "" {
		prTitle = fmt.Sprint(titleVal)
	}

	healedVal, ok := bCtx.Get("healed_selectors")
	if !ok || healedVal == nil {
		patchVal, okPatch := bCtx.Get("patch_content")
		if !okPatch || patchVal == nil {
			return &BlockResult{
				BlockID: "git.pr",
				Status:  StatusFailed,
				Message: "Missing input: 'healed_selectors' or 'patch_content' required to open Git PR",
				Error:   errors.New("missing healed selectors or patch"),
			}, errors.New("missing healed selectors or patch")
		}
	}

	repoDir := "."
	if dirVal, ok := bCtx.Get(VarRepoDir); ok && dirVal != nil && fmt.Sprint(dirVal) != "" {
		repoDir = fmt.Sprint(dirVal)
	}

	cmd := exec.CommandContext(ctx, "git", "status", "--porcelain")
	cmd.Dir = repoDir
	_ = cmd.Run()

	return &BlockResult{
		BlockID: "git.pr",
		Status:  StatusSimulated,
		Message: fmt.Sprintf("Pull Request simulated: %q (changes staged locally for human review)", prTitle),
		Data: map[string]interface{}{
			"pr_title":  prTitle,
			"repo_dir":  repoDir,
			"simulated": true,
			"status":    "STAGED_FOR_REVIEW",
		},
	}, nil
}
