# Journey 7: The Standalone Cockpit App UI/UX
**Target Users:** Developers, Tech Leads, Product Owners  
**Tech Stack:** Kotlin Compose Multiplatform (Desktop) reusing **KoltLibs** (`koltx:compose-kmp`, `koltx:utils`, `koltx:logutils`)

---

## 1. Overview & Objective

While developers love the terminal CLI (`kritix`) for rapid daily coding, complex architectural deliberation, multi-agent debate visualization, steering management, and side-by-side git diff inspection benefit enormously from a rich graphical desktop application.

Journey 7 specifies the **Kritix Standalone Cockpit App**, built in Kotlin using Compose Multiplatform and following **Kolt MVI architecture**.

```
+---------------------------------------------------------------------------------------------------------+
|  Kritix Cockpit                     [ Repo: /Users/arun/.../DialexAI ]    [ Branch: kritix/feat-auth ]  |
+---------------------------------------------------------------------------------------------------------+
|  [ 📋 Story Specs ]   [ 🏛️ Council Chamber ]   [ 💻 Engineering Lab ]   [ 📜 Steering Studio ]         |
+---------------------+-----------------------------------------------------------------------------------+
| WORKSPACE NAVIGATOR | MAIN WORKBENCH PANEL                                                              |
|                     |                                                                                   |
| 📁 Repo Tree:       | [ Split Mode: Coder Output | Reviewer Critique & Terminal Log ]                   |
|  - shared/          |                                                                                   |
|  - desktopApp/      | Domain Coder (Android)            Adversarial Reviewer                            |
|  - engine/          | ----------------------            --------------------                            |
|                     | Applying patch to:                Running ./gradlew testDebugUnitTest             |
| ⚙️ Active Steering:  | `CatalogCache.kt`                 > Task :shared:compileKotlinJvm                 |
|  [✓] kmp-arch.md    |                                   > Task :shared:test                             |
|  [✓] pii-sec.md     | Unified Diff:                     PASS: CatalogCacheTest.testEviction             |
|                     | + fun save(item: Item) {          PASS: CatalogCacheTest.testExpiry               |
| 🔌 Connected MCP:   | +   driver.execute(...)           All 14 tests passed in 1.4s.                    |
|  ● GitHub Server    | + }                                                                               |
|  ● PostgreSQL       |                                   Reviewer Verdict:                               |
|                     | Status: Patch Applied             [ ✓ APPROVED FOR MERGE ]                        |
+---------------------+-----------------------------------------------------------------------------------+
| ACTIONS: [ Discard Changes ]   [ Reject Chunk ]   [ Accept & Commit to Git ]   [ Push & Open PR ]       |
+---------------------------------------------------------------------------------------------------------+
```

---

## 2. Key Screen Workspaces

### Workspace 1: The Story Spec & Council Chamber
* **Roundtable View**: Avatars for PO, Architect, QA Lead, and EM showing who is currently speaking.
* **Transcript & Credence Heatmap**: Displays Bayesian confidence scores and flags unaddressed risk tensions.
* **Live Markdown Deliverable**: Shows real-time rendered spec with one-click export to GitHub Discussions, Jira, or a local file.

### Workspace 2: The Engineering Lab (Coding & Review)
* **Dual Split-Screen Layout**:
  - *Left*: Streaming tokens and file edits from the Domain Coder.
  - *Right*: Adversarial Reviewer commentary, AST inspection flags, and embedded terminal output running real test commands.
* **Interactive Diff Inspector**: Syntax-highlighted unified diff with chunk-by-chunk accept/reject buttons.

### Workspace 3: The Steering Studio
* Dynamic Persona-to-Rule Binding Matrix.
* Live System Prompt & Taboo Space Simulator.
* Remote Git/URL standards feed synchronizer.

### Workspace 4: Persona Studio
* Visual Radar Chart for Epistemic Bias, Rigor Threshold, and Safety vs. Velocity.
* Interactive 8-layer cognitive DNA editor with JSON import/export.

---

## 3. Kolt Framework Integration

The Cockpit App is built strictly according to Kolt standards:
1. **Design System**: Reuses `koltx:compose-kmp` for `KoltTheme`, `KoltButton`, `KoltCard`, `KoltTextField`, and typography.
2. **State Management**: Implements `MviViewModel` with typed `KritixState`, `KritixIntent`, and `KritixEffect`.
3. **Structured Logging**: Uses `koltx:logutils` (`KoltLogger`) for high-throughput desktop logging.
