# Artix Enterprise Release Notes — v0.6.0-enterprise

## Release Summary
Artix Enterprise `v0.6.0-enterprise` implements remediation for Round 8 evaluator findings:
- **R8-2: Cryptographic Audit Trail Verification & Typed Forge Errors**: Whole-log cryptographic validation (`audit.VerifyLogWithPubKey` / `audit.VerifyLog`), chain position and signature integrity verification, binding candidate commit SHA, spec ID, and reviewer verdict hash into Phase 1 `CANDIDATE_PUSHED` audit records, fail-closed enforcement on empty log paths, and typed error assertions (`errors.Is`) for forge dismissals and change requests preventing HTTP 503 error bodies from triggering branch deletion.
- **R8-3: Generic AST Taint Tracking, Reflection Execution Detection, and Linkname Taboo**: Data flow taint tracking across assignments, struct fields, returns, and same-package helper calls; treating `os.LookupEnv`, `viper`-style getters, and sensitive key names as sources; escalating reflection execution on process/network functions (`reflect.ValueOf(exec.Command).Call`), dynamic linkname directives (`//go:linkname`), and aliased operand self-comparisons (`b := f(); c := b; if b != c`) to unreviewed; filtering out benign test assertions and function call comparisons (`f() != f()`).
- **R8-4: Phase 1 Contract & IDE Plugin Alignment**: Reporting `success: false` (with `status: "awaiting_approval"` and `awaitingApproval: true`) during Phase 1 candidate push so consumers never misinterpret awaiting-approval as plain convergence; updating the Claude Code command and contract tests across VS Code, IntelliJ, and Claude Code plugins.

---

## Cryptographic Trust Root & Signatures

### Release Signing Key
- **Signer Identity**: `releases@artix.ai`
- **Key Type**: `ED25519 (SSH format)`
- **Public Key**: `ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ4FO6aRcfHA07XqA9SGUUfpiqYfLsaRz+KUwigN2K5x`
- **Key Fingerprint (SHA-256)**: `SHA256:Dh5vIjePKsm29xfvnLukrKDTOzLM/w5u6rYBcB3hmNg`

### Verifying the Tag
To independently verify the cryptographic signature on this tag:
```bash
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.6.0-enterprise
```

### Tag Immutability Registry
Exact commit targets verified via `git rev-parse <tag>^{commit}`:
- **`v0.1.0-enterprise`**: `92fc42fa5a2f694c3d2f8f4a556d35a529bdc09b` (tag object: `d42da85a32b4905e62d4b980bb6d3320523efd7d`)
- **`v0.2.0-enterprise`**: `7b4164992fd24a9e6154db382b2d8d87f68cab89` (tag object: `51c215a385ded1e206fcf01d461549e97bcf76f6`)
- **`v0.3.0-enterprise`**: `22a07738bbbcbd4b93398801292eb9f9c98299ee` (tag object: `9384732de2a0ffae6aae71935e857ad7c842bed1`)
- **`v0.4.0-enterprise`**: `3cb016d11557268d8d0050d461d62f342fc102c2` (tag object: `aaaf821939ab414d593b763d806a509b7bdced85`)
- **`v0.5.0-enterprise`**: `ea197f8108a8824c64364a2c059a3e261475073f` (tag object: `71fbb86f8c0547e749811da19a74fd80bcc6a0cb`)
- **`v0.6.0-enterprise`**: Canonical signed release incorporating Round 8 fixes.

---

## Round 8 Technical Remediations & Verified Test Suites

### R8-1: Release & CI Verification
- Signed release tag `v0.6.0-enterprise` created with the published ED25519 signing key.
- Clean clone validation passing `go build ./...`, `go vet ./...`, and `go test -race -count=1 ./...`.

### R8-2: Cryptographic Audit Trail Verification & Typed Forge Errors
- **Whole-Log Cryptographic Verification**: `verifyPhase1AuditBinding` runs `audit.VerifyLogWithPubKey` (or `audit.VerifyLog`) over the entire audit log, confirming HMAC/ED25519 signatures and hash chaining across every entry.
- **Fail-Closed on Missing/Empty Audit Paths**: Fails verification if the audit log path is empty, unreadable, or missing valid records.
- **Event & Status Validation**: Strictly matches records where `EventType == "CANDIDATE_PUSHED"` and status is `AWAITING_APPROVAL` or `SUCCESS`. Rejects `FAILED` events, forged appended lines, tampered middle records, or truncated logs.
- **Reviewer Verdict Hash Binding**: Binds the reviewer verdict hash recorded during Phase 1 into the candidate push event details and verifies exact equality at Phase 2 (`VerifyAndMergeCandidate`).
- **Typed Error Matching for Branch Cleanup**: Replaced substring-based error checks with typed errors (`ErrReviewDismissed`, `ErrChangesRequested`, `ErrReviewerNotAllowed`, `ErrStaleCommitSHA`, `ErrSelfApprovalForbidden`, `ErrBotApprovalForbidden`). HTTP 503 and network errors cannot match typed rejection errors and never trigger branch cleanup.
- **Verified Tests**:
  - `pkg/forge/e2e_test.go`: `TestR8_2_CryptographicAuditBinding_TamperingRejections` (tampered middle, forged append, failed event, empty path, verdict mismatch).
  - `pkg/forge/e2e_test.go`: `TestR8_2_TypedErrorBranchCleanup_503VsDismissal` (HTTP 503 response containing keywords preserved; explicit dismissal triggers cleanup).

### R8-3: Generic AST Taint Tracking, Reflection Execution, Linkname Directives
- **Multi-Hop Taint Tracking**: Traces secret sources (`os.Getenv`, `os.LookupEnv`, `cfg.Get("secret_key")`, functions returning secrets) through variable assignments, struct literals (`C{k: os.Getenv(...)}`), and multi-hop function calls (`g(secret())`).
- **Sink Reaching in Same Package**: Flags non-test code where tainted variables reach sinks: error creation (`errors.New`, `fmt.Errorf`), logging (`log.Printf`, `fmt.Println`), network APIs (`http.Get`, `http.Post`, `net.Dial`), and process execution (`exec.Command`).
- **Reflection Execution Taboo**: Flags calls to `reflect.ValueOf(exec.Command).Call(...)` and reflection-based invocations of process and network functions.
- **Linkname Directive Detection**: Flags `//go:linkname` compiler directives bypassing Go visibility rules.
- **Aliased Operand Self-Comparison**: Flags self-comparisons where identical operands are assigned to aliases (`b := f(); c := b; if b != c`).
- **Test Quality Check**: Flags tests that contain only logging (`t.Logf`) without any assertions.
- **Benign Pattern Preservation**: Excludes dynamic function calls (`f() != f()`) and standard `got != want` patterns from false positive flags.
- **Verified Tests**:
  - `pkg/reviewer/reviewer_test.go`: `TestR8_3_HostileReviewCorpus_AllRejectedOrUnreviewed`.
  - `pkg/reviewer/reviewer_test.go`: `TestR8_3_BenignPatterns_NotFalsePositivelyRejected`.

### R8-4: Phase 1 JSON & IDE Plugins Contract
- **Report `success: false` for Awaiting Approval**: In Phase 1 candidate push, Coordinator loop and CLI emit `success: false`, `ok: false`, `status: "awaiting_approval"`, `awaitingApproval: true`.
- **Plugin Alignment**:
  - `plugins/claude-code/commands/artix-code.md`: Documents `awaiting_approval` status and instructs user to run `artix merge` after human forge review.
  - `plugins/vscode/src/extension.ts`: Evaluates `status === "awaiting_approval" || awaitingApproval` before success.
  - `plugins/intellij/src/main/kotlin/ai/artix/ide/Actions.kt`: Evaluates `status == "awaiting_approval" || awaitingApproval` before success.
- **Verified Tests**:
  - `cli/golden_test.go`: `TestR8_4_Phase1_AwaitingApproval_ReportsSuccessFalse_StatusAwaitingApproval_AndPluginsContract`.

---

## Clean Clone Verification Commands
On a clean clone of the repository:
```bash
# 1. Verify build
go build ./...

# 2. Verify vet
go vet ./...

# 3. Verify all tests with race detector
go test -race -count=1 ./...

# 4. Verify tag signature
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.6.0-enterprise
```
