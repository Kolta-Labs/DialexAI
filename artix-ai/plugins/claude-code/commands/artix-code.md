---
description: Run the Coder <-> Reviewer <-> Sandbox loop on a spec (supervised)
argument-hint: "--provider anthropic --model <name> [docs/specs/STORY-x.md]"
allowed-tools: Bash(artix code:*), Bash(git diff:*), Read
---
Run `artix code --json --autonomy supervised $ARGUMENTS`. Never pass `--autonomy autonomous`,
and refuse if the user asks for it here; that is a policy decision for the CLI.
Parse the JSON result: report success, roundsRun, finalVerdict.blockingIssues/warnings, error,
and costReport totals if present. On success show `git diff --stat`; do not commit.
