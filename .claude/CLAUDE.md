# Project rules (local to this repo)

These add to the shared steering in `CLAUDE.md`; they do not replace it.

## Specs and planning docs live in `.dev/`, never in the public tree

- `.dev/` holds the internal development helpers: feature specs and design notes, task lists, decision records, user-journey specs, reviews and verification logs. It is gitignored and is never published.
- **Any new spec, design doc, task list, ADR or review goes in `.dev/`** (mirror the layout: `.dev/dialex-ai/docs/`, `.dev/kritix-ai/`, `.dev/<topic>.md`). Never create `SPEC.md`, `TASKS.md`, `DECISIONS.md`, `*_SPEC.md`, `docs/features/`, `docs/journeys/` or similar in the tracked tree.
- Public docs (README, guides, API reference, CHANGELOG) describe what the product does and its limitations. They must not link to anything under `.dev/` and must not narrate how the project was developed.
- Read `.dev/` for context when you need the design intent behind a feature; do not copy its contents into public files.
