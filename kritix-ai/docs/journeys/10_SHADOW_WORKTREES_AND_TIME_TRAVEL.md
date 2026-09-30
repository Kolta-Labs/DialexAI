# Journey 10: Shadow Worktrees & Git Isolation

## 1. Vision & Goals
Eliminate developer context disruption. Traditional AI coding agents dirty the developer's working directory, overwrite uncommitted changes, or cause test collisions. Kritix introduces **Shadow Worktrees**: isolated execution branches running in detached directories via `git worktree`.

## 2. Key Capabilities
1. **Worktree Manager (`pkg/git/worktree.go`)**:
   - `CreateShadowWorktree(baseBranch string) (*ShadowWorktree, error)` creates `.kritix/worktrees/<task-id>`.
   - Copies or re-symlinks active build caches and `.standards`.
   - Runs Coder patches and test sandboxes strictly inside the shadow worktree.
2. **Time-Travel Round Snapshots**:
   - Commits an ephemeral checkpoint commit per iteration round: `round-1`, `round-2`, `round-3`.
   - Preserves reviewer rejection rationales alongside code diffs.
   - Allows scrubbing between rounds: `kritix time-travel list` and `kritix time-travel checkout <round>`.
3. **Safe Merge / Cherry-Pick Gate**:
   - When the Adversarial Reviewer signs off and tests pass, presents an interactive merge prompt:
     - Merge directly to the current working branch.
     - Squash and merge.
     - Export as a patch file or Pull Request.
   - Cleans up ephemeral worktrees on completion (`git worktree remove --force`).
