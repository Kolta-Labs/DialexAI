# Artix Enterprise Release Notes — v0.4.0-enterprise

## Release Summary
Artix Enterprise `v0.4.0-enterprise` implements two-phase autonomous PR candidate push and merge verification, branch protection, AST exfiltration inspection, strict runner allowlists, atomic per-task budget ledger accounting, and secure file permission enforcement.

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
git tag -v v0.4.0-enterprise
```
Using SSH allowed signers configuration:
```bash
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.4.0-enterprise
```

### Tag Immutability Declaration
In accordance with release integrity standards:
- **`v0.2.0-enterprise`** is permanently anchored at commit `7b4164992fd24a9e6154db382b2d8d87f68cab89`.
- **`v0.3.0-enterprise`** is permanently anchored at commit `22a0773663677610113f01bbdd37be9d96ca418c`.
- **`v0.4.0-enterprise`** is the canonical, newly signed release encompassing all Round 6 remediations.

---

## Round 6 Remediation Breakdown

### R6-1 (R5-1): Signed Release & External Trust Root
- Release tag `v0.4.0-enterprise` cryptographically signed with ED25519 key matching `releases@artix.ai`.
- Published external key fingerprint `SHA256:Dh5vIjePKsm29xfvnLukrKDTOzLM/w5u6rYBcB3hmNg`.
- CI workflow builds `./cli` and `./cmd/artixd` with `-ldflags "-X artix/pkg/policy.RequireSignedPolicyFlag=true"`, runs `go test -race -count=1 ./...`, and publishes `SHA256SUMS`.

### R6-2 (R5-2): Two-Phase Autonomous PR Flow & ForgePusher
- **Phase 1 (`artix code`)**: Pushes autonomous candidate commits to dedicated PR branches (`refs/heads/artix-pr-*`) via `ForgePusher` with strict branch protection (refuses direct pushes to `main`, `master`, `trunk`, `prod`, `production`, `release/*`), returning `AwaitingApproval: true` and candidate SHA.
- **Phase 2 (`artix merge` / daemon review event)**: Verifies server-side forge approval on the exact candidate SHA via `VerifyAndMergeCandidate` before merging and logging audit records.
- **Rejection Cleanup**: On forge rejection or verification error, automatically deletes remote candidate branches (`CleanupCandidateBranch`).
- **End-to-End Verification**: Validated in `TestR6_2_TwoPhaseAutonomousPRFlow_RealBareRepo` against a real local bare git repository.

### R6-3 (R5-3): AST Exfiltration Sinks, Credential Inspection, & Runner Allowlist
- **Exfiltration Sinks**: Flags DNS exfiltration (`net.LookupHost`, `net.LookupIP`), process execution exfiltration (`exec.Command("curl", ...)`), and package/variable HTTP client POSTs (`c.Post(...)`).
- **Credential Protection**: Flags reading credential files (`.aws/credentials`, `.ssh`, `id_rsa`, etc.) in non-test code as prohibited exfiltration risks.
- **Language Security**: Detects Swift `NSTask()` / `NSTask.launchedTask` and TypeScript `new Function(...)` / string-concatenated `require('child_' + 'process')`.
- **Tautology & Assertion Checks**: Rejects self-comparisons on identical operands (`if a != a`).
- **Runner Allowlist Hardening**: Rejects no-op/dummy configs (`--no-eslintrc`, `--rule {}`, `-c /dev/null`, `nothing.js`).

### R6-4 (R5-5): Per-Task Budget Ledger & Secure File Permissions
- **Per-Task Entries**: `RecordRoundUsage` atomically records `tasks[taskId]` entries containing `Tokens` and `USD`, allowing exact join verification in `pilot.VerifyLedgerEntryMatch`.
- **Secure Default Path**: Relocated default ledger from world-writable `/tmp` to secure per-user directory `~/.artix/budget-ledger.json` (`0700` dir, `0600` file).
- **Permission Verification**: `ValidateLedgerSecurity` strictly refuses any ledger file with group or world write bits (`mode.Perm()&0077 != 0`) or owned by an untrusted UID.

### R6-5 (R5-4): Comment Author Authorization
- Daemon webhook handler strictly verifies the comment author's identity and permission on `issue_comment` events, handling payloads where GitHub's `sender` object lacks `author_association`.

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
git tag -v v0.4.0-enterprise
```

