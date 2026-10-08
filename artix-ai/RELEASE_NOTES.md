# Artix Enterprise Release Notes — v0.5.0-enterprise

## Release Summary
Artix Enterprise `v0.5.0-enterprise` implements complete remediation for Round 7 evaluator findings:
- Generic AST secret-source-to-sink data flow analysis across network, process, file write, log/stdout, and error text sinks.
- AST and token-based identical operand comparison detection (`if a != a`).
- Dynamic code loading (`plugin.Open`), low-level execution (`syscall.Exec`, Cgo `C.system`), and dynamic linker manipulation (`LD_PRELOAD`) detection.
- Non-destructive remote candidate branch cleanup (preserves candidate branch on missing reviews and HTTP 5xx/network errors; deletes only on definitive negative reviews).
- Branch protection heuristics protecting `develop`, `dev`, `staging`, `main`, `master`, `trunk`, `prod`, `production`, `release/*`, `releases/*`, `hotfix/*`, `hotfixes/*` without false-positiving standard feature branches (`verify-login`, `validation-fix`, `vendor-update`).
- Cryptographic Phase 1 to Phase 2 audit trail binding in `VerifyAndMergeCandidate` / `artix merge`.
- Accurate IDE plugin and CLI output handling for `status: "awaiting_approval"`.

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
git tag -v v0.5.0-enterprise
```
Using SSH allowed signers configuration:
```bash
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.5.0-enterprise
```

### Tag Immutability Declaration
In accordance with release integrity standards:
- **`v0.2.0-enterprise`** is permanently anchored at commit `7b4164992fd24a9e6154db382b2d8d87f68cab89`.
- **`v0.3.0-enterprise`** is permanently anchored at commit `22a0773663677610113f01bbdd37be9d96ca418c`.
- **`v0.4.0-enterprise`** is permanently anchored at commit `3cb016dbb96238b6d3bc091cbe04620f4f9810bb`.
- **`v0.5.0-enterprise`** is the canonical, newly signed release encompassing all Round 7 remediations.

---

## Round 7 Remediation Breakdown & Verified Test Suites

### R7-1 (R6-1): Signed Release & External Trust Root
- Release tag `v0.5.0-enterprise` cryptographically signed with ED25519 key matching `releases@artix.ai`.
- Published external key fingerprint `SHA256:Dh5vIjePKsm29xfvnLukrKDTOzLM/w5u6rYBcB3hmNg`.
- CI workflow builds `./cli` and `./cmd/artixd` with `-ldflags "-X artix/pkg/policy.RequireSignedPolicyFlag=true"`, runs `go test -race -count=1 ./...`, and publishes `SHA256SUMS`.

### R7-2 (R6-2): Two-Phase Flow Deficiencies Remediated
- **Accurate Audit State**: Renamed verify-only audit state to `APPROVAL_VERIFIED` in `VerifyAndMergeCandidate` and `artix merge` (Verified in `TestR6_2_TwoPhaseAutonomousPRFlow_RealBareRepo`, `TestR7_2_Phase2_BoundToPhase1AuditRecord`).
- **Non-Destructive Cleanup**: `IsDefinitiveRejection` deletes remote candidate branch only on explicit human/policy rejection (`CHANGES_REQUESTED`, dismissal, stale commit SHA mismatch, author self-approval). Never deletes on "no reviews yet" or HTTP 503 / network errors (Verified in `TestR7_2_NonDestructiveCleanup_NoReviewsYet_And_503`).
- **Strict Branch Protection Heuristics**: `IsProtectedBranch` protects `develop`, `dev`, `staging`, `main`, `master`, `trunk`, `prod`, `production`, `release/*`, `releases/*`, `hotfix/*`, `hotfixes/*`, and removes erroneous `v*` prefix matching so `verify-login`, `validation-fix`, `vendor-update` are not false-positived (Verified in `TestR7_2_BranchProtection_NoFalsePositives_ProtectsDevelopStaging`).
- **Phase 1 to Phase 2 Cryptographic Audit Binding**: `VerifyAndMergeCandidate` verifies that candidate SHA and spec ID are bound to a Phase 1 audit record (`CANDIDATE_PUSHED` / `EventCodeConvergence`), rejecting unbound or foreign candidate commits (Verified in `TestR7_2_Phase2_BoundToPhase1AuditRecord`).
- **IDE Plugins & CLI Contract**: CLI, VS Code extension, and IntelliJ plugin handle `status: "awaiting_approval"` and `awaitingApproval: true` distinctly from committed/uncommitted states (Verified in `TestR7_2_CLI_JSON_AwaitingApproval_Contract`).

### R7-3 (R6-3): Generic AST & Semantic Security Rules
- **Self-Comparison & Tautologies**: Token-based and AST binary expression evaluation rejects self-comparisons on identical operands (`if a != a`, `a == a`, etc.) (Verified in `TestR7_3_HostileReviewCorpus_AllRejectedOrUnreviewed`, `TestR7_3_CoordinatorRun_HostileCorpus_AllRejected`).
- **Generic Secret Source to Sink Exfiltration**: AST guard inspects non-test Go code for reads of sensitive environment variables (`TOKEN`, `KEY`, `SECRET`, `PASSWORD`, etc.) or credential files (`.aws/credentials`, `.ssh`, `id_rsa`) reaching any sink (network `http.Get`/`Post`/`Dial`, process `exec.Command`, file write `os.WriteFile`, stdout/logging `fmt.Println`, error text `fmt.Errorf`/`errors.New`) in the same package/file, escalating to unreviewed (Verified in `TestR7_3_HostileReviewCorpus_AllRejectedOrUnreviewed`, `TestR7_3_CoordinatorRun_HostileCorpus_AllRejected`).
- **Dynamic Code Loading & Low-Level Exec**: Escalates `plugin.Open`, `syscall.Exec`, Cgo `C.system`, and `os.Setenv("LD_PRELOAD", ...)` to unreviewed (Verified in `TestR7_3_HostileReviewCorpus_AllRejectedOrUnreviewed`, `TestR7_3_CoordinatorRun_HostileCorpus_AllRejected`).
- **Git Push Exfiltration**: Detects and rejects `exec.Command("git", "push", ...)` exfiltration attempts (Verified in `TestR7_3_HostileReviewCorpus_AllRejectedOrUnreviewed`, `TestR7_3_CoordinatorRun_HostileCorpus_AllRejected`).
- **Weak Assertions & Empty Loops**: Detects assertions that only check `err == nil` or assertions nested inside loops iterating over empty slices (Verified in `TestR7_3_HostileReviewCorpus_AllRejectedOrUnreviewed`, `TestR7_3_CoordinatorRun_HostileCorpus_AllRejected`).

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
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.5.0-enterprise
```
