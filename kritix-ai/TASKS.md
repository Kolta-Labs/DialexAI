# Kritix AI: Detailed Implementation Tasks & Roadmap

This document outlines the phased, granular tasks for developing **Kritix AI**. 
> [!IMPORTANT]
> **Dialex is completely sealed**. All implementations, tools, plugins, and persona registries live strictly inside the `kritix/` workspace, importing `engine/pkg/...` strictly as a read-only Go module.

---

## Phase 1: Foundation, Workspace & Standards (Completed)

- [x] **TASK-0101**: Create `kritix/` workspace directory structure (`cli/`, `app/`, `pkg/`, `docs/`, `docs/journeys/`).
- [x] **TASK-0102**: Author master technical specification in `kritix/SPEC.md` and 7 modular Journey specs.
- [x] **TASK-0103**: Initialize `kritix/go.mod` (`module kritix`, Go 1.27).
- [x] **TASK-0104**: Configure root `go.work` linking `./engine` and `./kritix` and verify `go work sync`.
- [x] **TASK-0105**: Symlink `.standards` (`Standards/steering/kmp`), `AGENTS.md`, `CLAUDE.md`, and `GEMINI.md` to `engine/`, `kritix/`, `shared/`, `desktopApp/`, and `androidApp/`.

---

## Phase 2: Persona Engine & Extensible Registry (Completed)

- [x] **TASK-0201**: Implement `kritix/pkg/persona/builtins.go`:
  - 11 built-in SWE Personas with complete 8-Layer Persona DNA:
    - Stakeholders: `product_owner_lead`, `senior_software_architect`, `qa_testing_lead`, `engineering_manager`, `senior_staff_engineer`.
    - Domain Engineers: `backend_engineer`, `android_engineer`, `ios_engineer`, `business_domain_expert`.
    - Reviewers: `adversarial_code_reviewer`, `security_auditor`.
- [x] **TASK-0202**: Implement `kritix/pkg/persona/registry.go`:
  - Hierarchical registry loading builtins, user personas (`~/.kritix/personas/*.json`), and project personas (`<repo>/.kritix/personas/*.json`).
  - Full CRUD operations: Get, List, SaveCustom, DeleteCustom.
- [x] **TASK-0203**: Author and verify unit tests in `kritix/pkg/persona/registry_test.go` (100% PASS).

---

## Phase 3: Repository Context & Dynamic Steering Engine (`kritix/pkg/repo`, `kritix/pkg/steering`)

*Objective: Build the repository context analyzer, external steering syncer, and dynamic persona binding engine.*

### 3.1 Repository Context
- [x] **TASK-0301**: Implement `kritix/pkg/repo/detector.go`:
  - Auto-detect git root, current branch, uncommitted files, and build manifests (`go.mod`, `build.gradle.kts`, `package.json`, `Cargo.toml`).
- [x] **TASK-0302**: Implement `kritix/pkg/repo/tree.go`:
  - `.gitignore`-aware file tree indexer and file content reader.

### 3.2 Dynamic Steering Ingestion & Remote Sync
- [x] **TASK-0303**: Implement `kritix/pkg/steering/aggregator.go`:
  - Ingest local repo rules: `.standards/*`, `CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `.kritix/steering/*.md`.
- [x] **TASK-0304**: Implement `kritix/pkg/steering/remote_syncer.go`:
  - Pull and cache external standards from remote Git repositories and live HTTP/URL feeds.
  - Check ETag / Git commit hash for auto-refresh.
- [x] **TASK-0305**: Implement `kritix/pkg/steering/binder.go`:
  - Dynamic Persona-to-Rule Binding Matrix configured in `.kritix/steering.json`.
  - Translates bound rules into Persona DNA `TabooSpace` constraints and `HeuristicRule` lists.
- [x] **TASK-0306**: Implement `kritix/pkg/steering/manager.go`:
  - Programmatic steering manager with persistence in `.kritix/steering.json`.
  - CLI operations: `kritix steering list`, `kritix steering sync`, `kritix steering bind <persona> <rule>`.

---

## Phase 4: SWE Execution Sandbox & Git Driver (`kritix/pkg/sandbox`, `kritix/pkg/git`)

*Objective: Provide isolated execution and safe file mutation primitives.*

- [x] **TASK-0401**: Implement `kritix/pkg/sandbox/exec.go`:
  - Command execution sandbox (`exec.CommandContext`) supporting timeout, cwd containment, stdout/stderr streaming, and exit code checking.
- [x] **TASK-0402**: Implement `kritix/pkg/git/driver.go`:
  - Git status, branch creation, unified diff extraction, and commit operations.
- [x] **TASK-0403**: Implement `kritix/pkg/git/patch.go`:
  - Atomic patch applier with backup and rollback on failure.

---

## Phase 5: Story Spec Generator (`kritix spec` / `kritix plan`)

*Objective: Enable stakeholder planning councils that deliberate on user stories and output verified specifications.*

- [x] **TASK-0501**: Implement `kritix/pkg/spec/council.go`:
  - Assemble Stakeholder Council using `engine/pkg/orchestrator` read-only.
  - Pass repository AST map, file tree, and bound steering rules to Council context.
- [x] **TASK-0502**: Implement multi-round planning debate:
  - Round 1: PO outlines User Story & Acceptance Criteria.
  - Round 2: Architect proposes technical design & file targets; QA Lead challenges failure modes.
  - Round 3: Synthesis into consolidated spec.
- [x] **TASK-0503**: Implement Style Vectors:
  - Support `ponytail` (structured executive bullets) and `caveman` (zero-fluff technical commands).
- [x] **TASK-0504**: Implement `kritix/pkg/spec/formatter.go`:
  - Write formatted markdown to `docs/specs/STORY-<id>.md` including Gherkin acceptance criteria, ADR rationale, file mutation manifest, and test verification commands.
- [x] **TASK-0505**: Wire `kritix spec` and `kritix plan` CLI subcommands (`kritix/cli/main.go`).

---

## Phase 6: Domain Coder, Reviewer & Sandbox Loop (`kritix code`)

*Objective: Implement the iterative coding and verification engine.*

- [x] **TASK-0601**: Implement `kritix/pkg/coder/engine.go`:
  - Domain Coder prompt compiler incorporating selected domain persona (e.g., Android, Backend) and active story spec.
- [x] **TASK-0602**: Implement `kritix/pkg/reviewer/engine.go`:
  - Adversarial Reviewer prompt compiler checking AST diffs against bound steering rules and test outputs.
- [x] **TASK-0603**: Implement the Convergence Loop:
  - Step 1: Coder generates atomic patch $\rightarrow$ Sandbox applies patch.
  - Step 2: Sandbox executes project test command (e.g., `./gradlew testDebugUnitTest` or `go test ./...`).
  - Step 3: Reviewer inspects diff and test results.
  - Step 4: If tests fail or bugs found, Reviewer sends feedback $\rightarrow$ repeat (Max $N$ rounds).
  - Step 5: If tests pass and Reviewer signs off $\rightarrow$ mark task completed.
- [x] **TASK-0604**: Implement Autonomy Gates:
  - `supervised`: Interactive prompt asking user to confirm before applying final diff or committing.
  - `interactive`: Pause after each round for developer feedback.
  - `autonomous`: Auto-commit if git status is clean and test exit code is 0.
- [x] **TASK-0605**: Wire `kritix code` CLI subcommand (`kritix/cli/main.go`).

---

## Phase 7: Autonomous Remote Worker & Forge Integration (`kritix/pkg/forge`) (Completed)

*Objective: Enable headless server runs that clone remote repos and open PRs.*

- [x] **TASK-0701**: Implement `kritix/pkg/forge/github.go`:
  - GitHub App and PAT integration: clone, create remote branch, push, and open Pull Request with attached Story Spec and test logs.
- [x] **TASK-0702**: Implement `kritix/pkg/forge/gitlab.go`:
  - GitLab Project Access Token integration: clone, create remote branch, push, and open Merge Request.
- [x] **TASK-0703**: Implement server worker daemon (`kritix/pkg/forge/server.go`):
  - HTTP webhook listener for GitHub Issues, GitLab Issues, and asynchronous worker queue management.
- [x] **TASK-0704**: Author and verify unit tests in `kritix/pkg/forge/forge_test.go` (100% PASS, 73.0% coverage).

---

## Phase 8: Plugin Architecture & MCP Extensibility (`kritix/pkg/plugins`, `kritix/pkg/mcp`, `kritix/pkg/skills`) (Completed)

*Objective: Support pluggable build drivers, VCS forges, and external MCP tools.*

- [x] **TASK-0801**: Implement Build Driver Plugin Interface (`kritix/pkg/plugins/build_driver.go`):
  - Defined `BuildDriver` interface (`Compile`, `Test`, `Lint`, `ParseErrorTrace`).
  - Implemented built-in drivers: Go, Gradle (Android/KMP), Cargo (Rust), and npm (Node/TypeScript) with structured error trace parsing.
- [x] **TASK-0802**: Implement MCP Client in `kritix/pkg/mcp/client.go` & `connectors.go`:
  - JSON-RPC 2.0 client supporting stdio and HTTP transports for tools and resources.
  - Pre-configured connectors for Jira, PostgreSQL, and Figma MCP servers.
- [x] **TASK-0803**: Implement Project Skills loader in `kritix/pkg/skills/loader.go`:
  - Discovers and executes custom tools and workflows from `.kritix/skills/*` and `~/.kritix/skills/*` (Markdown `SKILL.md` frontmatter & JSON).
- [x] **TASK-0804**: Author and verify unit tests in `pkg/plugins`, `pkg/mcp`, and `pkg/skills` (100% PASS).

---

## Phase 9: Standalone Cockpit App & Steering Studio (Compose Multiplatform + Kolt) (Completed)

*Objective: Build the graphical developer workbench reusing KoltLibs.*

- [x] **TASK-0901**: Configure `kritix/app/build.gradle.kts`:
  - Added `:kritix:app` to `settings.gradle.kts`.
  - Implemented Kolt dependencies: `io.github.koltalabs.kolt:compose-kmp`, `utils`, `logutils`.
- [x] **TASK-0902**: Implement Kolt MVI Presentation layer (`MviViewModel`, `AsyncState`).
- [x] **TASK-0903**: Implement Council Chamber UI (`CouncilChamberView.kt`):
  - Stakeholder avatar row, live debate transcript, influence meters, live markdown spec preview.
- [x] **TASK-0904**: Implement Engineering Lab UI (`EngineeringLabView.kt`):
  - Split view: Coder patch editor on left, Reviewer critique & live terminal test output on right.
  - Interactive unified diff viewer with chunk-by-chunk accept/reject buttons.
- [x] **TASK-0905**: Implement Steering Studio UI (`SteeringStudioView.kt`):
  - Dynamic Persona-to-Rule Binding Matrix.
  - Rule inspector & remote Git/URL standards feed synchronizer.
- [x] **TASK-0906**: Implement Persona Studio UI (`PersonaStudioView.kt`):
  - Custom Canvas Radar Chart visualizer and 8-layer DNA editor.
- [x] **TASK-0907**: Implement Native CLI entrypoint (`kritix/cli/main.go`) and Daemon (`cmd/kritixd/main.go`).

---

## Phase 10: Interactive TUI REPL & Alignment Interviews (`kritix repl`, `@mentions`, `/grill-me`) (Completed)

*Objective: Deliver a high-ergonomics terminal REPL with context mentions and alignment interviews.*

- [x] **TASK-1001**: Implement TUI Engine & Shell in `kritix/pkg/tui/repl.go`:
  - Interactive REPL loop with prompt formatting, ANSI colorized output, and command dispatch.
- [x] **TASK-1002**: Implement Context Mention Parser in `kritix/pkg/tui/mentions.go`:
  - Resolves `@file:<path>`, `@spec:<id>`, `@rule:<id>`, and `#symbol:<name>` into live context attachments.
- [x] **TASK-1003**: Implement Slash Commands & Alignment Interview (`kritix/pkg/tui/interview.go`):
  - Stakeholder Council conducts 3-question Socratic alignment interview (`/grill-me`) before planning.
- [x] **TASK-1004**: Wire `kritix repl` subcommand into `kritix/cli/main.go` (and default interactive mode).
- [x] **TASK-1005**: Author unit tests in `kritix/pkg/tui/tui_test.go` (100% PASS).

---

## Phase 11: Shadow Worktrees & Time-Travel Isolation (`kritix/pkg/git/worktree.go`) (Completed)

*Objective: Provide zero-disruption background execution without dirtying developer workspaces.*

- [x] **TASK-1101**: Implement Shadow Worktree Manager in `kritix/pkg/git/worktree.go`:
  - Created isolated `.kritix/worktrees/<task-id>` via `git worktree add` with `.standards` symlinking.
  - Automatic worktree removal and pruning on exit.
- [x] **TASK-1102**: Implement Time-Travel Snapshotting & Round Checkpoints (`RecordCheckpoint`):
  - Ephemeral commits per round with diffs and reviewer feedback preservation.
- [x] **TASK-1103**: Implement Safe Merge & Cherry-Pick Gate (`MergeInto`):
  - Fast-forward or squash-merges shadow worktree results into working branch.
- [x] **TASK-1104**: Author unit tests in `kritix/pkg/git/worktree_test.go` (100% PASS, 81.2% coverage).

---

## Phase 12: Universal Language Server Protocol (LSP) Server Mode (`kritix/pkg/lsp`) (Completed)

*Objective: Universal IDE bridge for VS Code, IntelliJ, Zed, and Neovim.*

- [x] **TASK-1201**: Implement LSP JSON-RPC 2.0 Base Protocol & Dispatcher in `kritix/pkg/lsp/server.go`:
  - Lifecycle (`initialize`, `initialized`, `shutdown`, `exit`).
  - Document tracking (`textDocument/didOpen`, `textDocument/didChange`).
- [x] **TASK-1202**: Implement Real-Time Taboo Diagnostics (`textDocument/publishDiagnostics`):
  - Streams Taboo Space violations (raw SQLite, blocking main thread) as editor squigglies.
- [x] **TASK-1203**: Implement Code Action & CodeLens Provider (`textDocument/codeAction`, `textDocument/codeLens`):
  - Quick-fixes and clickable Council Deliberation lenses.
- [x] **TASK-1204**: Wire `kritix lsp` subcommand into `kritix/cli/main.go`.
- [x] **TASK-1205**: Author unit tests in `kritix/pkg/lsp/lsp_test.go` (100% PASS, 86.4% coverage).

---

## Phase 13: Auto-Evolving Knowledge Items (KI) & PR-to-Rule Synthesizer (`kritix/pkg/knowledge`) (Completed)

*Objective: Institutional memory that learns from every coding session and PR comment.*

- [x] **TASK-1301**: Implement Knowledge Item Store in `kritix/pkg/knowledge/store.go`:
  - Thread-safe CRUD and indexing of repository learnings in `.kritix/knowledge/*.json`.
- [x] **TASK-1302**: Implement Auto-Learning Extractor in `kritix/pkg/knowledge/extractor.go`:
  - Synthesizes breakthrough insights from converged multi-round coder/reviewer loops.
- [x] **TASK-1303**: Implement PR-to-Rule Synthesizer in `kritix/pkg/knowledge/synthesizer.go`:
  - Converts natural code review comments into proposed Taboo Space constraints and Heuristics.
- [x] **TASK-1304**: Author unit tests in `kritix/pkg/knowledge/knowledge_test.go` (100% PASS, 90.2% coverage).
