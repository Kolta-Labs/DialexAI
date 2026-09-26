# Feature 10 Architectural Specification: Autonomous Artifact Sandbox & Code Verification Engine (v2.0)

> **Status**: **APPROVED & READY FOR IMPLEMENTATION**  
> **Target Version**: `v2.0`  
> **Components**: Go Engine (`pkg/sandbox`, `pkg/api`), KMP Shared (`domain`, `data`, `presentation/chat`, `presentation/sandbox`), Desktop & Mobile Compose UI, Deliverable Exporters.  
> **Cross-Platform Compatibility**: macOS, Linux, Windows, Android (via Engine RPC or Local Fallback).  

---

## 1. Executive Summary & Problem Formulation

### 1.1 The Epistemic Blind Spot of Synthesized Artifacts
In multi-agent technical deliberation, AI agents propose concrete software architectures, database schemas, cryptographic protocols, concurrent worker pools, and production code diffs. While frontier models produce persuasive syntactic structures, **unexecuted code remains epistemic speculation**.

Traditional LLM deliberation platforms suffer from three core failure modes:
1. **API Hallucinations**: Personas invoke non-existent library functions (e.g., `sync.RwMutex` instead of `sync.RWMutex`, or deprecated cloud SDK methods).
2. **Subtle Concurrency & Logic Flaws**: Race conditions, deadlock-prone channel locks, off-by-one boundary conditions, and broken SQL constraints that appear sound upon visual inspection.
3. **Disjointed Verification**: The human architect must manually copy code blocks out of the debate into a separate terminal, install dependencies, compile, and report back compiler errors manually.

### 1.2 The Sovereign Sandbox Solution (v2.0)
**Feature 10: Autonomous Artifact Sandbox & Code Verification Engine** integrates a zero-cloud, secure, isolated execution environment directly into the Dialex Go Engine and Compose UI. Dialex autonomously extracts, compiles, and verifies code deliverables, feeding compiler diagnostics directly back into the debate loop.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   DIALEX AI CLOSED-LOOP VERIFICATION                   │
└────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
       ┌─────────────────────────────────────────────────────────┐
       │ Multi-Agent Council Turn / Deliverable Synthesis       │
       │ (e.g. Claude generates High-Throughput Go Ring Buffer)  │
       └─────────────────────────────────────────────────────────┘
                                    │
                         Code Extraction Engine
                                    │
                                    ▼
       ┌─────────────────────────────────────────────────────────┐
       │ Sandboxed Subprocess / Micro-Environment Execution     │
       │ - Ephemeral isolated directory (0700)                   │
       │ - Strict timeout (default 5s, max 30s)                  │
       │ - Subprocess tree termination (SIGKILL process group)   │
       │ - Capped buffers & memory tracking                      │
       └─────────────────────────────────────────────────────────┘
                                    │
                     ┌──────────────┴──────────────┐
                     ▼                             ▼
              [ EXIT CODE: 0 ]              [ EXIT CODE: != 0 ]
            Execution Succeeded            Compilation/Test Panic
                     │                             │
                     ▼                             ▼
       ┌───────────────────────────┐ ┌───────────────────────────┐
       │ Attach Verified Badge     │ │ Inject Diagnostic Note to │
       │ [ ✅ VERIFIED (42ms) ]     │ │ Council for Next Round    │
       │ Embed in ADR & Memo       │ │ "Fix compiler error at L14"│
       └───────────────────────────┘ └───────────────────────────┘
```

---

## 2. Core Functional Requirements & Specifications

### 2.1 Supported Languages & Execution Engines
The Dialex Sandbox dynamically introspects the host machine to determine available language runtimes:

| Language | Engine Identifier | Command / Toolchain | Test Harness Pattern |
|---|---|---|---|
| **Go** | `go` | `go run main.go` / `go test -v .` | `main.go` + `main_test.go` |
| **Python** | `python` | `python3 -u main.py` / `pytest` | `main.py` + `test_main.py` |
| **TypeScript / JS** | `typescript` / `javascript` | `bun run` / `deno run` / `npx tsx` / `node` | In-memory runner or ephemeral package runner |
| **Rust** | `rust` | `rustc main.rs && ./main` / `cargo test` | Single-file script or lightweight cargo tempdir |
| **SQL** | `sql` | Embedded pure-Go SQLite memory database | DDL creation + test insertion queries |
| **POSIX Shell** | `bash` | `/bin/bash --restricted` / `sh` | Script syntax validation and execution |

If a specific toolchain is not installed on the user's host (e.g., Rust compiler absent), the engine reports `RUNTIME_UNAVAILABLE` and offers pure-syntax validation or execution via container/fallback.

---

## 3. Security, Containment & Isolation Constraints

Running untrusted or LLM-generated code requires stringent isolation parameters:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SANDBOX ISOLATION ENVELOPE                      │
└────────────────────────────────────────────────────────────────────────┘
  1. Ephemeral FS: /tmp/dialex-sandbox/{uuid}/ with 0700 file permissions.
  2. Immediate Wipe: defer os.RemoveAll(tmpDir) guaranteed on all code paths.
  3. Execution Ceiling: Hard timeout (context.WithTimeout, default 5s).
  4. Process Group Tree Kill: syscall.Kill(-pgid, syscall.SIGKILL).
  5. Capped I/O Buffers: Stdout and Stderr capped at 64KB (prevent OOM).
  6. Environment Sanitization: Cleans API keys, Vault secrets, and tokens from subprocess env.
```

### 3.1 Process Tree Termination
To prevent child processes from hanging indefinitely in the background:
```go
cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
// Upon timeout expiration:
_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
```

---

## 4. Data Models & API Specifications

### 4.1 Go Domain Models (`engine/pkg/model/sandbox.go`)

```go
package model

type SandboxLanguage string

const (
	LangGo         SandboxLanguage = "go"
	LangPython     SandboxLanguage = "python"
	LangTypeScript SandboxLanguage = "typescript"
	LangJavaScript SandboxLanguage = "javascript"
	LangRust       SandboxLanguage = "rust"
	LangSQL        SandboxLanguage = "sql"
	LangBash       SandboxLanguage = "bash"
	LangUnknown    SandboxLanguage = "unknown"
)

type SandboxExecutionRequest struct {
	ArtifactID  string            `json:"artifactId,omitempty"`
	DiscussionID string           `json:"discussionId,omitempty"`
	Language    SandboxLanguage   `json:"language"`
	Code        string            `json:"code"`
	Entrypoint  string            `json:"entrypoint,omitempty"`
	TestCode    string            `json:"testCode,omitempty"`
	TimeoutSecs int               `json:"timeoutSecs,omitempty"` // default 5s, max 30s
	EnvVars     map[string]string `json:"envVars,omitempty"`
}

type SandboxExecutionResult struct {
	ExecutionID   string          `json:"executionId"`
	ArtifactID    string          `json:"artifactId,omitempty"`
	DiscussionID  string          `json:"discussionId,omitempty"`
	Language      SandboxLanguage `json:"language"`
	Success       bool            `json:"success"`
	ExitCode      int             `json:"exitCode"`
	Stdout        string          `json:"stdout"`
	Stderr        string          `json:"stderr"`
	DurationMs    int64           `json:"durationMs"`
	PeakMemoryKb  int64           `json:"peakMemoryKb"`
	ErrorMessage  string          `json:"errorMessage,omitempty"`
	TimestampMs   int64           `json:"timestampMs"`
	VerifiedAt    string          `json:"verifiedAt"`
}

type AvailableRuntime struct {
	Language    SandboxLanguage `json:"language"`
	BinaryPath  string          `json:"binaryPath"`
	Version     string          `json:"version"`
	IsAvailable bool            `json:"isAvailable"`
}
```

### 4.2 REST Endpoints (`engine/pkg/api/`)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/sandbox/runtimes` | List available toolchains and versions installed on host. |
| `POST` | `/sandbox/execute` | Execute arbitrary code snippet in sandbox with output telemetry. |
| `POST` | `/debates/{id}/verify-artifact` | Extract and verify code inside a discussion artifact or deliverable. |
| `GET` | `/debates/{id}/verifications` | Retrieve verification history and results for a discussion. |

---

## 5. KMP Shared Domain & Multiplatform Architecture

### 5.1 Architecture Call Chain
```
SandboxTerminalDrawer (Compose UI)
  │
  ▼
ChatViewModel / SandboxViewModel (MVI Presentation)
  │
  ▼
ExecuteSandboxSnippetUseCase / VerifyArtifactUseCase (Domain)
  │
  ▼
SandboxRepository (Domain Interface)
  │
  ▼
SandboxRepositoryImpl (Data Layer)
  │
  ▼
EngineDataSource & EngineClient (Ktor HTTP Client)
```

### 5.2 Kotlin Serialization Models (`shared/src/commonMain/kotlin/com/dialex/domain/model/SandboxModels.kt`)
```kotlin
@Serializable
data class SandboxExecutionRequest(
    val artifactId: String? = null,
    val discussionId: String? = null,
    val language: String,
    val code: String,
    val entrypoint: String? = null,
    val testCode: String? = null,
    val timeoutSecs: Int = 5,
    val envVars: Map<String, String> = emptyMap()
)

@Serializable
data class SandboxExecutionResult(
    val executionId: String,
    val artifactId: String? = null,
    val discussionId: String? = null,
    val language: String,
    val success: Boolean,
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val durationMs: Long = 0L,
    val peakMemoryKb: Long = 0L,
    val errorMessage: String? = null,
    val timestampMs: Long = 0L,
    val verifiedAt: String = ""
) {
    val isCleanPass: Boolean
        get() = success && exitCode == 0
}
```

---

## 6. Compose Multiplatform UI Design (`SandboxTerminalDrawer.kt`)

### 6.1 Visual Design & Ergonomics
- **Dual Form Factor**:
  - **Desktop**: Full slide-over or floating dialog with terminal console styling (`#0F172A`), monospace typography (JetBrains Mono / Roboto Mono), ANSI colored text, and execution telemetry metrics.
  - **Mobile**: Touch-optimized bottom sheet with swipe-down dismiss and compact status chips.
- **Keyboard Shortcut**: `Cmd+Shift+X` (macOS) / `Ctrl+Shift+X` (Linux/Windows) toggles the Sandbox Drawer.
- **Transcript Code Block Integration**: Every rendered code block in a turn or deliverable displays a small `[ ▶ Run in Sandbox ]` button in its top right header.
- **Execution Telemetry Badge**:
  - Green pill: `[ ✅ PASS • 0.04s • 0KB stderr ]`
  - Red pill: `[ ❌ FAIL • Exit 1 • 2 errors ]`

---

## 7. Deliverable Integration: ADRs & Executive Memorandums

When an ADR or Executive Memorandum is exported:
- Code blocks that have passed sandbox execution automatically display a sovereign verification seal:
```markdown
> [!NOTE]
> 🛡️ **Empirically Verified in Dialex Sandbox**:
> Language: Go 1.22 | Exit Code: 0 | Execution Time: 34ms | Memory: 1,840 KB
> Invariant Check: 4/4 assertions passed with zero data races.
```
- In the Executive HTML Memorandum, a green verified chip with execution timestamps gives stakeholders assurance of real-world validity.

---

## 8. Implementation Phases & Milestones

1. **Phase 1: Go Engine Sandbox Infrastructure (`engine/pkg/sandbox/`)**:
   - Runtime detector (discovers Go, Python, Node, Deno, Rust, SQLite).
   - Isolated execution manager with tempfs, process group timeouts, buffer caps, and environment scrubbing.
   - Comprehensive test suite in `engine/pkg/sandbox/manager_test.go`.
2. **Phase 2: Go HTTP REST API Handlers & Routing**:
   - Handlers for `/sandbox/runtimes` and `/sandbox/execute`.
   - Router registration and HTTP integration tests in `sandbox_handlers_test.go`.
3. **Phase 3: KMP Shared Domain & Data Layer**:
   - `SandboxModels.kt`, `SandboxRepository.kt`, `SandboxRepositoryImpl.kt`, `ExecuteSandboxUseCase.kt`.
   - Wiring into `EngineDataSource` and `EngineClient`.
4. **Phase 4: Compose Multiplatform UI & Terminal Drawer**:
   - `SandboxTerminalDrawer.kt` with live execution output, terminal styling, and runtime selector.
   - Integration into `ChatScreen.kt`, `ChatViewModel.kt`, and `WorkspaceHeader.kt`.
   - Code block "Run in Sandbox" launcher.
5. **Phase 5: Deliverable Exporters & Automated Verification**:
   - Verification badges in `ExecutiveMemoExporter.kt` and `MarkdownExport.kt`.
6. **Phase 6: Verification & Test Suite**:
   - Go engine tests, KMP shared unit tests, and end-to-end execution.
