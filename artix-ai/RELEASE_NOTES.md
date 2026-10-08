# Artix Enterprise Release Notes — v0.8.0-enterprise

## Release Summary
Artix Enterprise `v0.8.0-enterprise` delivers full remediation for Round 10 evaluator findings:
- **R10-2: End-to-End Binary Phase 1 to Phase 2 Execution & Verdict Hash Binding**:
  - `artix code --json` in enterprise mode outputs the computed reviewer verdict SHA-256 hash (`verdictHash`).
  - `artix merge` accepts `--verdict-hash` (or reads it from the authenticated Phase 1 record keyed by `--sha`/`--spec`).
  - Strict cryptographic binding verification in Phase 2 ensures that a mismatched verdict hash fails closed (status `failed`).
  - If an audit log exists in enterprise mode, a nil audit logger parameter is rejected (fail-closed).
  - Validated via end-to-end binary tests executing the compiled `artix` CLI against a local bare Git repository and mock forge server (`TestR10_2_BuiltBinary_Phase1ToPhase2_EndToEndWithVerdictHash`).
- **R10-3: Benign Stdlib Corpus Study & Zero False Positive Rejection**:
  - Deleted prior synthetic corpus claims.
  - Implemented reproducible evaluation harness `scripts/benign-corpus.sh` (`cmd/artix-corpus/main.go`) evaluating real test files across 34 Go standard library packages from `$(go env GOROOT)/src`.
  - Discovered and evaluated **262 real test files**.
  - Results published in `docs/pilot/benign_corpus_results.csv`: **262 Approved / 0 Rejected (0.00% False Positive Rejection / FPR)**.
  - Fixed edge cases including string literal formatting, Example functions, distinct variable alias comparisons (`b != c` vs `b == c`), and multithreaded test helper analysis.
- **R10-4: Comprehensive AST Assertion Reachability Analyzer**:
  - Deterministically detects and rejects tests where no assertion is reachable on the main execution path.
  - Rejects tests containing only `t.Logf` / `t.Log` without assertions.
  - Rejects tests with assertions only in unjoined goroutines (`go func() { ... }()`).
  - Rejects tests with assertions only inside `t.Cleanup`.
  - Rejects tests with assertions placed after unconditional `return` statements.
  - Rejects tests with assertions guarded by constant `false` conditions (e.g. `const debug = false; if debug { ... }` or `ok := true; if !ok { ... }`).
  - Rejects tests with assertions defined in closures that are never called (`_ = check`).
  - Rejects tests relying solely on helper functions that cannot fail (return unconditionally on all paths).

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
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.8.0-enterprise
```

### Tag Immutability Registry
Exact commit targets verified via `git rev-parse <tag>^{commit}` and `git rev-parse <tag>`:
- **`v0.1.0-enterprise`**: `92fc42fa5a2f694c3d2f8f4a556d35a529bdc09b` (tag object: `d42da85a32b4905e62d4b980bb6d3320523efd7d`)
- **`v0.2.0-enterprise`**: `7b4164992fd24a9e6154db382b2d8d87f68cab89` (tag object: `51c215a385ded1e206fcf01d461549e97bcf76f6`)
- **`v0.3.0-enterprise`**: `22a07738bbbcbd4b93398801292eb9f9c98299ee` (tag object: `9384732de2a0ffae6aae71935e857ad7c842bed1`)
- **`v0.4.0-enterprise`**: `3cb016d11557268d8d0050d461d62f342fc102c2` (tag object: `aaaf821939ab414d593b763d806a509b7bdced85`)
- **`v0.5.0-enterprise`**: `ea197f8108a8824c64364a2c059a3e261475073f` (tag object: `71fbb86f8c0547e749811da19a74fd80bcc6a0cb`)
- **`v0.6.0-enterprise`**: `e1803c1553c440353051385f304109b9551c992f` (tag object: `30d4c0559ea1eacdeb051ab399de46fe10cf4604`)
- **`v0.7.0-enterprise`**: `5d31de9c95c4980ed8ff019fb966449acfd085c6` (tag object: `091bed22e42db575235a7b35c99dcc42b380b534`)
- **`v0.8.0-enterprise`**: Canonical signed release incorporating Round 10 fixes.

---

## Round 10 Technical Remediations & Verified Test Suites

### R10-2: Built-Binary Phase 1 to Phase 2 with Verdict Hash Binding
- **CLI Phase 1 Output**: `artix code --json` outputs `verdictHash`.
- **CLI Phase 2 Input**: `artix merge` binds `--verdict-hash` to the cryptographic audit record.
- **Fail-Closed Audit Binding**: If an audit log exists, nil logger verification is rejected in enterprise mode.
- **Verified Tests**:
  - `pkg/forge/e2e_test.go`: `TestR10_2_BuiltBinary_Phase1ToPhase2_EndToEndWithVerdictHash`
  - `pkg/forge/forge_test.go`: `TestR10_2_VerifyPhase1AuditBinding_NilLogger_FailsClosedWhenLogExists`

### R10-3: Benign Stdlib Corpus Study
- **Reproducible Script**: `scripts/benign-corpus.sh` evaluates test files across 34 stdlib packages.
- **Results**: 262/262 files approved (0.00% FPR), documented in `docs/pilot/benign_corpus_results.csv`.
- **Verified Tests**:
  - `pkg/reviewer/reviewer_test.go`: `TestR10_3_BenignStdlibPatterns_NotFalsePositivelyRejected`

### R10-4: AST Assertion Reachability Analyzer
- **Only Logging**: Detects tests with only `t.Logf`/`t.Log`.
- **Unjoined Goroutines**: Flags assertions isolated inside unjoined `go func() { ... }()`.
- **Cleanup Only**: Flags assertions isolated inside `t.Cleanup`.
- **Unreachable Code**: Flags assertions placed after unconditional returns.
- **Constant False Guard**: Flags assertions under `const false` or negated constant true flags.
- **Uninvoked Closures**: Flags assertion closures assigned to variables that are never called.
- **Unfailable Helpers**: Flags helper functions that return before reaching assertions.
- **Panic Swallowing**: Flags `defer func() { recover() }()` panic swallowing in tests.
- **Verified Tests**:
  - `pkg/reviewer/reviewer_test.go`: `TestR10_4_HostileReviewCorpus_AssertionReachability_AllRejected`

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

# 4. Run reproducible benign corpus evaluation
./scripts/benign-corpus.sh

# 5. Verify tag signature
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.8.0-enterprise
```
