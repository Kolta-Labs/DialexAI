# Artix IDE plugins

Thin wrappers over the `artix` binary (must be on `PATH`, or set the plugin's binary path).
Each one runs the same three steps and nothing else:

| Step | Command | Result |
|---|---|---|
| Plan | `artix plan --json [--provider P --model M] "<story>"` | `docs/specs/STORY-*.md` |
| Code | `artix code --json --autonomy supervised --provider P --model M [spec]` | patch applied in a shadow worktree; loop result |
| Review | `artix review --json [--provider P --model M]` | verdict for the current `git diff` |

Rules every plugin follows:
- `--json` prints one JSON object on stdout; progress goes to stderr.
- **Never** passes `--autonomy autonomous`. Unattended commits stay a CLI/policy decision.
- The model API key comes from the environment (`ANTHROPIC_API_KEY`, …); plugins never store keys.
- Review `status: "unreviewed"` (no model Critic) is shown as a warning, not a pass.

| Plugin | Dir | Build |
|---|---|---|
| Claude Code | `claude-code/` | none (markdown commands) |
| VS Code | `vscode/` | `npm i && npm run build` |
| JetBrains | `intellij/` | `gradle buildPlugin` |
