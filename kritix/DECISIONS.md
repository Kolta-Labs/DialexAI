# Architecture & Product Decision Record
**Date:** September 29, 2026  
**Status:** Approved & Specified  
**Next Session:** Finalize name selection (Archlex vs. Praxex) and implement Phase 3 (Repo Scanner & Steering Ingestion).

---

## 1. Product Ecosystem: The Triad Model

The ecosystem is structured into three specialized product lines sharing common core engines and libraries:

| Product | Focus & Target Audience | Tech Stack | Status |
| :--- | :--- | :--- | :--- |
| **Dialex** | Synthetic Board of Advisors & Deliberation for Scholars, Executives, Researchers. | Go Engine + Compose Multiplatform (Desktop/Mobile) | **100% Sealed** (Untouched) |
| **Scribex** | AI-powered Writing, Documentation & Authoring Assistant. | Common Go Core + Kolt UI | Reserved for Future Development |
| **Archlex / Praxex** *(formerly Kritix AI/Scribex)* | Adversarial Multi-Agent Software Engineering Platform (Story Specs, Domain Coding, Verification). | Go CLI + Standalone Cockpit App (Kolt KMP) | **Active Development** |

---

## 2. Monorepo & Sealing Decisions

1. **Single Monorepo with Separate Folders**:
   - Selected over multi-repo for atomic commits, single CI pipelines, and zero version synchronization lag.
   - Go Workspace (`go.work`) at the repository root unifies `./engine` and `./kritix` (to be renamed).
2. **Dialex is Completely Sealed**:
   - Zero lines modified in `engine/`, `shared/`, `desktopApp/`, or `androidApp/`.
   - The coding tool imports `dialex/pkg/...` strictly as a read-only Go module.
3. **Common Reusable Module**:
   - A shared library module (to be housed in `KoltLibs` under `libs/persona-studio` or `libs/dialex-kit`) will encapsulate:
     - The **Persona Builder / DNA Studio** (radar chart visualizer, 8-layer form, JSON validator).
     - The **SSE Deliberation Bus & Streaming Client**.
     - Shared Kolt theme tokens and markdown viewers.
   - This ensures Dialex, the coding tool, and Scribex share identical persona editing capabilities without code duplication.

---

## 3. Standards & Kolt Integration

1. **Standards Symlinks**:
   - Kolt KMP & Engineering Standards (`Standards/steering/kmp`) have been symlinked to all workspace directories (`engine/`, `kritix/`, `shared/`, `desktopApp/`, `androidApp/`).
   - `.standards`, `AGENTS.md`, `CLAUDE.md`, and `GEMINI.md` resolve identically across all folders.
2. **Kolt Foundation**:
   - Standalone App will be built in Compose Multiplatform using `KoltLibs` (`koltx:compose-kmp`, `koltx:utils`, `koltx:logutils`).
   - Follows Kolt MVI architecture (`MviViewModel`, `AsyncState`, unidirectional intent flows).

---

## 4. Software Engineering Capabilities

The tool provides three independent, decoupled capabilities (not a rigid pipeline):

1. **Capability 1: Story Spec Generation & Planning (`spec` / `plan`)**:
   - Virtual Stakeholder Council: Product Owner, Senior Architect, QA Lead, Engineering Manager, Senior Staff Engineer.
   - Deliberates on requirements, NFRs, and edge cases against repository context.
   - Produces a verified, testable `docs/specs/STORY-<id>.md` (Acceptance criteria in Gherkin, ADR, file scope, test commands).
2. **Capability 2: Domain Implementation Loop (`code`)**:
   - Domain Engineering Specialists: Backend, Android (Compose), iOS (Swift), Web, Architectural Overseer, Business Domain Expert.
   - Paired with an Adversarial Reviewer and a deterministic test sandbox (`go test`, `./gradlew`, `npm test`).
   - Iterative patch-test-critique convergence loop.
3. **Capability 3: Standalone Adversarial Review (`review`)**:
   - Review Council for existing git diffs, branches, and PRs.

---

## 5. Persona Engine & Dynamic Extensibility

Implemented and verified in `kritix/pkg/persona/`:
- **11 Built-in SWE Personas** defined with complete 8-Layer Persona DNA.
- **Extensible Registry (`registry.go`)**:
  - Global user personas (`~/.<tool>/personas/*.json`).
  - Project-scoped personas (`<repo>/.<tool>/personas/*.json`).
  - Full CRUD API (Get, List, SaveCustom, DeleteCustom).
- **Unit Test Suite**: 100% passing (`TestRegistryBuiltins`, `TestRegistryCustomPersona`).

---

## 6. Naming & Trademark Decisions

| Name Candidate | Status | Reason / Legal Finding |
| :--- | :--- | :--- |
| **Scribex** | **Reserved** | Assigned to the upcoming AI Writing Assistant. |
| **Kritix AI** | **Rejected** | BMC Software owns "BMC AMI Kritix AI" trademark; "Kritix AI" is also generic shorthand for Developer Experience. |
| **Archex** | **Rejected** | Conflict with active open-source AI coding tool on GitHub (`Mathews-Tom/archex`). |
| **Archlex** ⭐ | **Top Candidate** | *Architecture* + *-lex* (Dialex suffix). 100% clean trademark; zero competing AI tools. |
| **Praxex** | **Strong Alternative** | *Praxis* (practical execution) + *-ex*. 100% clean trademark. |

---

## 7. Action Items for Tomorrow

1. **Confirm Name**: Select between **Archlex** and **Praxex**, and perform final folder rename from `kritix/`.
2. **Phase 3 Execution**:
   - Implement `pkg/repo/detector.go` (auto-detect git root, active branch, build tools).
   - Implement `pkg/steering/aggregator.go` (hierarchical ingestion of `CLAUDE.md`, `.standards/`, `.dialex/steering/`).
3. **CLI Entrypoint**:
   - Scaffold `cli/main.go` and wire the initial CLI commands.
