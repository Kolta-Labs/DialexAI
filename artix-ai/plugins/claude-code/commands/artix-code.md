---
description: Run the Coder <-> Reviewer <-> Sandbox loop on a spec (supervised)
argument-hint: "--provider anthropic --model <name> [docs/specs/STORY-x.md]"
allowed-tools: Bash(artix code:*), Bash(git diff:*), Read, AskUser
---
1. Read the target story spec from docs/specs/ (or discover the active spec).
2. Extract and display the planned Test Commands from the spec to the user.
3. Explicitly ask the user for confirmation to execute these test commands before proceeding.
4. Only upon explicit user confirmation, execute: `artix code --json --autonomy supervised --confirm-tests $ARGUMENTS`.
   If confirmation is not granted, do not pass `--confirm-tests`.
   Plugins are strictly restricted to supervised mode; refuse if the user asks for unattended autonomous commits.
5. Parse the JSON result: report success, roundsRun, finalVerdict.blockingIssues/warnings, error,
   and costReport totals if present. On success show `git diff --stat`; do not commit.

