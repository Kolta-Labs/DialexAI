---
description: Stakeholder Council plans a story into docs/specs/STORY-*.md
argument-hint: "<story> [--provider anthropic --model <name>]"
allowed-tools: Bash(artix plan:*), Read
---
Run `artix plan --json $ARGUMENTS` in the repo root. Parse the one-line JSON from stdout.
Show the spec path, title, scenario count and test commands, then Read the spec and summarise it.
If the command fails, show stderr verbatim. Do not edit the spec.
