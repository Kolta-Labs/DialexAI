# Kritix AI — User Guide for Developers & Product Owners

> **The Sovereign Multi-Agent Software Engineering Platform**  
> *Where Product Planning meets Adversarial Verification.*

---

## 1. What is Kritix AI? (In Plain English)

Most AI coding assistants act like **"yes-men"**: when you ask them to build a feature, they immediately start typing code, hallucinate missing requirements, and declare victory without ever testing if the code actually compiles or runs.

**Kritix AI works like a real-world elite software engineering team.** It splits software creation into two separate, checks-and-balances chambers:

```
┌────────────────────────────────────────────────────────┐
│                   CHAMBER 1: PLANNING                  │
│               The Stakeholder Council                  │
│   (Product Owner + Architect + QA Lead + Eng Manager)  │
│   • Asks clarifying questions ("What if offline?")    │
│   • Agrees on acceptance criteria & boundaries         │
│   • Outputs a verified Story Spec (docs/specs/STORY.md)│
└──────────────────────────┬─────────────────────────────┘
                           │ Approved Spec
                           ▼
┌────────────────────────────────────────────────────────┐
│                  CHAMBER 2: EXECUTION                  │
│          Domain Coder ⟷ Adversarial Reviewer           │
│   • Coder writes the code in an isolated sandbox       │
│   • Sandbox runs your actual tests (Gradle, Go, etc.) │
│   • Reviewer scrutinizes diffs & checks Taboo Spaces   │
│   • Loops until tests pass 100% and Reviewer approves  │
└────────────────────────────────────────────────────────┘
```

---

## 2. Who is this for?

| Role | What you get out of Kritix |
| :--- | :--- |
| **Product Owners & Managers** | Turn rough feature ideas into ironclad, verified specifications with automated acceptance tests (Gherkin format). Never worry about missing edge cases or technical debt. |
| **Software Engineers** | Build features and fix bugs without the AI messing up your open files or dirtying your workspace. Code runs in an isolated background sandbox and merges only when tests are green. |
| **Tech Leads & Architects** | Enforce "Taboo Spaces" (strict architectural rules the AI is forbidden from violating, such as *"Never import database drivers directly into UI presentation code"*). |

---

## 3. Quick Start (5-Minute Guide)

You can use Kritix in three ways:
1. **Interactive Terminal (CLI)** — Instant, keyboard-driven shell.
2. **Standalone Desktop Cockpit App** — Beautiful visual workbench.
3. **Your Favorite Editor** — Works inside VS Code, IntelliJ, Zed, or Neovim via standard Language Server Protocol (LSP).

### Option A: Launch the Interactive Terminal

In your project folder, simply run:
```bash
./bin/kritix
```

You are greeted by the Kritix Shell:
```text
Kritix AI Shell — Dialectic Coding & Alignment Studio
Type /help for available commands or /exit to quit.

kritix> 
```

### Option B: Launch the Desktop Cockpit App

Run the desktop application:
```bash
./gradlew :kritix:app:run
```

---

## 4. Guide for Product Owners: Planning Features

As a Product Owner, your goal is to make sure developers build **the right thing** without scope creep or missed requirements.

### Step 1: Run an Alignment Interview (`/grill-me`)
Before writing specifications, ask the Council to interview you on potential blind spots:

```bash
kritix> /grill-me "Add Google OAuth2 login and token refresh"
```

The Stakeholder Council immediately analyzes your prompt and asks 3 critical questions:
1. **[Product Owner]**: *"What is the exact fallback behavior when the Google authentication server is offline or times out?"*
2. **[Senior Architect]**: *"Which layer manages token storage, and what security constraints must be enforced?"*
3. **[QA Lead]**: *"What negative test scenarios (e.g., expired refresh token, network drop) must pass before release?"*

### Step 2: Generate the Verified Story Spec (`/plan`)
Once aligned, generate the specification:

```bash
kritix> /plan "Implement Google OAuth2 login with silent token refresh"
```

Kritix deliberates and writes a clean, version-controlled markdown file into your repository:  
`docs/specs/STORY-101.md`.

### Step 3: Review the Story Spec
Open `docs/specs/STORY-101.md`. It contains:
- **User Story**: High-level value summary.
- **In-Scope & Out-of-Scope**: Explicit boundaries to prevent scope creep.
- **Acceptance Criteria (Gherkin)**:
  ```gherkin
  Scenario: Silent Background Refresh
    Given the access token is expired
    When an API call is initiated
    Then the refresh token seamlessly fetches a new access token
    And the user session is uninterrupted
  ```
- **Architectural Decision Records (ADRs)**: Technical decisions and trade-offs.
- **Verification Commands**: The exact test commands required to pass (e.g., `./gradlew testDebugUnitTest` or `go test ./...`).

> **💡 Pro-Tip for POs:** You can choose your communication style:
> - `kritix plan --style ponytail "..."`: Executive bullet points for stakeholders and leadership.
> - `kritix plan --style caveman "..."`: Direct, zero-fluff commands for technical engineers.

---

## 5. Guide for Developers: Zero-Disruption Coding

### Problem Kritix Solves:
Other tools edit your files live on disk, breaking your build and ruining your active git workspace.

### How Kritix Works (Shadow Worktrees):
Kritix creates an isolated background clone of your repository (a **Shadow Worktree** in `.kritix/worktrees/`). The Domain Coder and Adversarial Reviewer iterate in that private space.

```
Your Active Workspace (Untouched)  ─────────► You keep working on your branch
                                                ▲
                                                │ Clean Merge when Verified
                                                ▼
Isolated Shadow Worktree           ─────────► Coder modifies code
                                              Sandbox runs actual tests
                                              Reviewer rejects / approves
```

### Step 1: Start the Convergence Loop
In your terminal, pass the Story Spec you want to build:

```bash
kritix> /code docs/specs/STORY-101.md
```

Kritix executes the loop:
1. **Round 1**: Coder generates a patch.
2. **Sandbox Run**: Sandbox executes project tests (e.g., `go test -v ./...` or `./gradlew test`).
3. **Reviewer Check**: Adversarial Reviewer inspects both the code diff and the test results.
   - If a test fails, or if a Taboo rule is broken, the Reviewer rejects the patch and gives specific feedback.
4. **Round 2**: Coder fixes the exact issue based on reviewer feedback.
5. **Round 3**: Tests pass! Reviewer signs off with `"SIGN-OFF APPROVED"`.

### Step 2: Use Context Mentions (`@` and `#`)
When talking to Kritix, easily pull in repository context:
- `@file:src/auth/TokenManager.kt` — attaches the file content into the prompt.
- `@spec:STORY-101` — attaches the active story specification.
- `@rule:taboo-sqlite` — binds a specific steering invariant.
- `#symbol:AuthRepository` — focuses the Coder on a specific class or function.

```bash
kritix> Check @file:src/auth/TokenManager.kt against @spec:STORY-101 and fix timeout handling
```

---

## 6. Steering Studio: Setting the Rules of the Road

### What is a "Taboo Space"?
A **Taboo Space** is a hard constraint that the AI is forbidden from violating under any circumstance.

**Common Examples:**
- 🚫 *"Never import direct SQLite libraries in the UI/ViewModel layer."*
- 🚫 *"Never use Thread.sleep or block the Android main thread."*
- 🚫 *"Never commit plaintext API tokens or hardcoded secrets."*

### How to Bind Rules to Personas
You can link rules to specific agent personas:
```bash
# Bind the taboo-sqlite rule to the Android engineer persona:
./bin/kritix steering bind android_engineer taboo-sqlite

# List all active steering rules in the project:
./bin/kritix steering list
```

### Learning from Human Reviews (PR-to-Rule)
Whenever a human senior engineer leaves a review comment on GitHub/GitLab:
> *"Never use java.util.Date in our Kotlin Multiplatform code, use kotlinx.datetime instead!"*

Kritix's **Rule Synthesizer** automatically turns that feedback into a permanent Taboo rule so no AI agent (or future developer) repeats that mistake.

---

## 7. Universal IDE Support (VS Code, IntelliJ, Zed, Neovim)

You do **not** need to install separate, fragile editor plugins. Kritix includes a built-in Language Server Protocol backend:

```bash
./bin/kritix lsp
```

### Features inside your editor:
1. **Real-Time Taboo Warnings**: Red/yellow squiggly lines appear as you type whenever code violates architectural rules.
2. **CodeLens Action**: A clickable button appears above class declarations:  
   `[⚡ Kritix: Deliberate Story Spec]`.
3. **Quick Fixes**: Lightbulb menu offers:  
   `[Kritix: Fix Taboo Space Violation]`.

---

## 8. CLI Command Cheat Sheet

| Command | What it does |
| :--- | :--- |
| `kritix` (or `kritix repl`) | Opens the interactive terminal shell. |
| `kritix plan "<prompt>"` | Assembles Stakeholder Council and produces `docs/specs/STORY-<id>.md`. |
| `kritix plan --style ponytail` | Produces an executive-level summary for leadership. |
| `kritix plan --style caveman` | Produces terse, code-only commands for engineers. |
| `kritix code [spec-file]` | Runs the Coder $\leftrightarrow$ Reviewer verification loop in an isolated worktree. |
| `kritix review` | Scrutinizes your current uncommitted git diff against active Taboo Spaces. |
| `kritix steering list` | Lists all discovered repository and external steering rules. |
| `kritix steering bind <role> <rule>` | Binds a rule to an agent persona. |
| `kritix persona` | Lists all 11 built-in SWE Personas (Architect, PO, QA, Backend, Android, etc.). |
| `kritix lsp` | Launches Language Server Protocol backend for IDEs. |
| `kritix daemon --addr :8080` | Starts headless server for GitHub/GitLab PR automation. |

---

## 9. Summary: Why Teams Love Kritix AI

1. **Requirements are crystal clear before coding starts** (PO Council deliberation).
2. **Developers' workspaces are protected** (Isolated Shadow Worktrees).
3. **No untested code enters main branches** (Deterministic sandbox verification).
4. **Architectural rules are strictly enforced** (Taboo Spaces and Steering Studio).
5. **The platform learns continuously** (Auto-evolving Knowledge Items and PR-to-rule synthesis).
