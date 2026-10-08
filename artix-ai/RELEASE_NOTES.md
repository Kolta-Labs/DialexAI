# Artix Enterprise Release Notes — v0.3.0-enterprise

## Release Summary
Artix Enterprise `v0.3.0-enterprise` delivers complete end-to-end remediations for all findings identified across Rounds 1 through 5 of hostile adversarial evaluation, achieving full enterprise autonomous governance compliance (`ACCEPT-ENTERPRISE-AUTONOMOUS`).

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
git tag -v v0.3.0-enterprise
```
Using SSH allowed signers configuration:
```bash
git -c gpg.ssh.allowedSignersFile=.allowed_signers tag -v v0.3.0-enterprise
```

### Tag Immutability Declaration
In accordance with release integrity standards:
- **`v0.2.0-enterprise`** is permanently anchored at commit `7b4164992fd24a9e6154db382b2d8d87f68cab89` (the original evaluated commit from Round 4). It is immutable and was not reused or rewritten.
- **`v0.3.0-enterprise`** is the canonical, newly signed release encompassing all Round 5 remediations.

---

## Round 5 Remediation Breakdown

### R5-1 (R4-1): Release Integrity & External Trust Root
- Release tag `v0.3.0-enterprise` cryptographically signed with ED25519 key matching `releases@artix.ai`.
- Published external key fingerprint `SHA256:Dh5vIjePKsm29xfvnLukrKDTOzLM/w5u6rYBcB3hmNg`.
- Continuous Integration workflow (`.github/workflows/artix.yml`) builds `./cli` and `./cmd/artixd` with `-ldflags "-X artix/pkg/policy.RequireSignedPolicyFlag=true"`, runs `go test -race -count=1 ./...`, and publishes `SHA256SUMS`.

### R5-2 (R4-2): Real Autonomous PR Push & Forge Approval Flow
- In autonomous mode, candidate commits are produced locally and pushed to the remote PR branch via `ForgePusher`.
- The human reviewer inspects and approves that exact pushed head commit on GitHub / GitLab.
- `ForgeVerifier` queries the server-side forge API to verify approvals against the PR's true remote head commit.
- Safe rollback mechanism (`safeRollbackCandidate`) guarantees `ResetHard` only rolls back candidate commits produced during the current run, never touching or destroying pre-existing user commits.

### R5-3 (R4-3): Broadened AST Guard, Review Escalation, & Strict Runner Allowlist
- **Secret & Egress Sinks**: Broadened AST inspection to flag secret/credential reading (`os.Environ()`, `os.Getenv()`, `os.ReadFile()` for credentials) combined with network egress (`http.Post`, `http.Get`, `http.Do`, `net.Dial`) anywhere in non-test Go code.
- **Multi-Language Taboos**: Flag Swift `Process()` with `/bin/sh` or arbitrary execution and TypeScript `child_process.execSync` as inherent security taboos.
- **Test Integrity**: Detect test skipping aliases (`t.Skipf`) and self-comparison tautologies (`if a != a`).
- **Review Escalation**: Modifications to `go.work`, `go.work.sum`, golden files under `testdata/`, or `//go:generate` directives automatically escalate to `StatusUnreviewed` (fail closed for human review).
- **Semantic Runner Allowlist**: `policy.IsAllowedSemanticRunner` strictly enforces exact allowlisted tool binaries, rejects relative/tmp paths, forbids shell metacharacters (`;|&`$><`), and rejects no-op `--version`/`--help` bypasses.

### R5-4 (R4-6): Daemon Default-Deny Authorization & Issue Comment Triggers
- Daemon webhook triggers enforce strict default-deny sender authorization across all events (`author_association` must be `OWNER`, `MEMBER`, or `COLLABORATOR`, or user in `AllowedUsers`).
- Senders who apply labels or trigger events are verified against authorization gates.
- Added full support for `issue_comment` (`created`) events with dual authorization (validates both issue author and comment sender for `/artix` commands).

### R5-5 (G9): Pilot Ledger Token & USD Matching
- `pkg/pilot` implements `VerifyLedgerEntryMatch`, reading actual ledger JSON entries and verifying exact token counts and USD amounts against task execution records.

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
git tag -v v0.3.0-enterprise
```
