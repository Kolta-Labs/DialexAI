---
description: Adversarial review of the current git diff
argument-hint: "[--provider anthropic --model <name>]"
allowed-tools: Bash(artix review:*)
---
Run `artix review --json $ARGUMENTS`. Parse the JSON verdict.
Print status, summary, blockingIssues, warnings, actionableFeedback.
If status is `unreviewed`, say plainly that no model Critic ran (pass --provider/--model) and that this is NOT an approval.
