# Artix Enterprise Release Notes — v0.9.0-enterprise

## Release Summary
Artix Enterprise `v0.9.0-enterprise` implements full remediation for all hostile reviewer defects across security, cryptographic integrity, test reachability, build indirection, corpus verification, and enterprise enforcement (R1–R10):

- **R2: Phase 2 (`artix merge`) Audit Fail-Closed, Spec Binding, Enterprise Key Isolation, and Authorized Cleanup**:
  - Every `audit.Emit` call on security paths (`APPROVAL_VERIFIED`, pusher, loop, cli) fails closed on audit error (status `failed`, exit code 1).
  - Verdict hash binding: `artix merge` reads verdict hash from authenticated Phase 1 record keyed by `--sha`/`--spec` when `--verdict-hash` is omitted.
  - Spec binding is strictly required: `--spec` must be non-empty.
  - Enterprise key isolation: when compiled with enterprise ldflags (`-X artix/pkg/policy.RequireSignedPolicyFlag=true`), environment trust roots (`ARTIX_POLICY_PATH`, `ARTIX_POLICY_SIGNING_KEY`, `ARTIX_POLICY_TRUSTED_PUBKEY`, `ARTIX_AUDIT_PUBLIC_KEY`) are strictly ignored; only compiled-in keys or root-owned `/etc/artix/policy.json` are trusted.
  - Remote branch cleanup occurs exclusively upon definitive rejection (`CHANGES_REQUESTED`) by an authorized human reviewer recorded in Phase 1; unauthorized users and bots cannot trigger branch deletion.

- **R3: Full GOROOT & Pinned Module Benign Corpus Evaluation**:
  - Full evaluation across standard library `$(go env GOROOT)/src` (1,808 test files) and 5 pinned third-party modules (`golang.org/x/sync@v0.7.0`, `github.com/google/uuid@v1.6.0`, `go.uber.org/zap@v1.27.0`, `golang.org/x/crypto@v0.25.0`, `github.com/stretchr/testify@v1.9.0` via `go mod download` with `go.sum`).
  - GOROOT: 101 rejected / 1,808 files (5.59% FPR) — beating the ≤ 127 requirement.
  - Pinned third-party modules: 8 rejected / 231 files (3.46% FPR) — beating the ≤ 118 requirement.
  - Grand total: 109 rejected / 2,039 files (5.35% FPR).
  - Fixed benign code patterns: `func TestMain`, `os.RemoveAll`/cleanup of `t.TempDir()`/`os.MkdirTemp`, NaN checks `x != x`, bit/parity tests `i&1 == 0`, and bounds checks `index < 0 || index >= len(x)`.
  - Detailed results published in `docs/pilot/benign_corpus_results.csv`.

- **R4: Comprehensive AST Assertion Reachability Analyzer**:
  - AST constant folder evaluating integers, booleans, string length literals, comparisons (`<`, `>`, `==`, `!=`), and boolean logic (`&&`, `||`, `!`).
  - Loop bounds analysis treating `for range 0` and `for i < 0` loops as unreachable/empty.
  - Control flow terminators (`runtime.Goexit()`, `os.Exit()`, `log.Fatal*`, and `panic()`) recognized as halting execution paths.
  - Real join requirement for goroutines (`sync.WaitGroup.Wait()`, channel receive `<-ch`, `errgroup.Wait()`) on the same execution path.
  - Rejection of assertion tautologies (`assert.Equal(t, 1, 1)`, `assert.True(t, true)`, `assert.NoError(t, nil)`).
  - Rejection of environment, OS, and architecture-gated early returns (`runtime.GOOS`, `runtime.GOARCH`, `os.Getenv`) and premature `return` statements in `t.Run` subtests before assertions.
  - Rejection of tests containing solely `t.Parallel()`, `t.Setenv()`, or `t.Helper()` without assertions.

- **R5: Non-Go Fail-Closed Gate & Docs Allowlist**:
  - Inverted non-Go pre-filter: any non-Go file not on the documented docs allowlist (`.md`, `.txt`, images with size cap) fails closed as `unreviewed` unless an approved semantic runner executes and exits 0.
  - Tested and enforced across 34 probed file extensions (.sh, .bash, .rb, .php, .rs, .java, .mjs, .c, .cpp, .m, .cs, .ps1, Jenkinsfile, .toml, .sql, Podfile, .yaml, .json, .gradle, .tsx, .jsx, .kts, .pl, .lua, .dart, .zsh, .bat, Rakefile, run, .html, .md).

- **R6: Go Egress and Taint Tracking**:
  - AST taint analysis blocking 22 evasion variants (HTTP, DNS, dial, curl execution, error text leakage, `Header.Set`, `url.Values`, struct literal network sinks, file writes, `go:generate`, CI configs, CODEOWNERS, `go.mod`, Dockerfiles, Gradle).

- **R7: IDE Test Confirmation Dialog Contract**:
  - VS Code and IntelliJ plugins display planned test commands retrieved from `artix plan --json` prior to passing `--confirm-tests`.

- **R8: Build and Script Indirection Closure**:
  - Complete closure over build systems and test runners (Makefile targets, package.json scripts, conftest.py, pytest.ini, setup.cfg, tox.ini, jest.config.js, build.rs, Cargo.toml, gradle, pom.xml, go.mod).

- **R9: Cryptographic Audit Key File Hardening**:
  - `SetPrivateKeyPath` enforces owner-only permissions (0600) and strictly errors on malformed, truncated, or garbage keys.

- **R10: Cost Ledger Concurrency & Fault Safety**:
  - Aborts run on lock failure, file-lock failure, or ledger write/chmod errors when a budget cap is set; verified under multi-process concurrency and restart persistence.

---

## Cryptographic Trust Root & Signatures

### Release Signing Key
- **Signer Identity / Principal**: `releases@artix.ai`
- **Tagger Identity**: `releases@artix.ai <releases@artix.ai>`
- **Key Type**: `ED25519 (SSH format)`
- **Public Key**: `ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ4FO6aRcfHA07XqA9SGUUfpiqYfLsaRz+KUwigN2K5x`
- **Key Fingerprint (SHA-256)**: `SHA256:Dh5vIjePKsm29xfvnLukrKDTOzLM/w5u6rYBcB3hmNg`

### Verifying the Tag
To independently verify the cryptographic signature on this tag without relying on local `~/.ssh/allowed_signers` or in-repo `.allowed_signers`:
```bash
TEMP_SIGNERS=$(mktemp)
echo "releases@artix.ai ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ4FO6aRcfHA07XqA9SGUUfpiqYfLsaRz+KUwigN2K5x" > "$TEMP_SIGNERS"
git -c gpg.ssh.allowedSignersFile="$TEMP_SIGNERS" tag -v v0.9.0-enterprise
rm -f "$TEMP_SIGNERS"
```

### Tag Immutability Registry
Exact commit targets verified via `git rev-parse <tag>^{commit}` and `git rev-parse <tag>`:
- **`v0.1.0-enterprise`**: `92fc42fa5a2f694c3d2f8f4a556d35a529bdc09b` (tag: `d42da85a32b4905e62d4b980bb6d3320523efd7d`)
- **`v0.2.0-enterprise`**: `7b4164992fd24a9e6154db382b2d8d87f68cab89` (tag: `51c215a385ded1e206fcf01d461549e97bcf76f6`)
- **`v0.3.0-enterprise`**: `22a07738bbbcbd4b93398801292eb9f9c98299ee` (tag: `9384732de2a0ffae6aae71935e857ad7c842bed1`)
- **`v0.4.0-enterprise`**: `3cb016d11557268d8d0050d461d62f342fc102c2` (tag: `aaaf821939ab414d593b763d806a509b7bdced85`)
- **`v0.5.0-enterprise`**: `ea197f8108a8824c64364a2c059a3e261475073f` (tag: `71fbb86f8c0547e749811da19a74fd80bcc6a0cb`)
- **`v0.6.0-enterprise`**: `e1803c1553c440353051385f304109b9551c992f` (tag: `30d4c0559ea1eacdeb051ab399de46fe10cf4604`)
- **`v0.7.0-enterprise`**: `5d31de9c95c4980ed8ff019fb966449acfd085c6` (tag: `091bed22e42db575235a7b35c99dcc42b380b534`)
- **`v0.8.0-enterprise`**: `c1aa48e44172283eb0977fc11841a0ddce2edab8` (tag: `48b7887e5e31766629ae705c740c0615598ba966`)
- **`v0.9.0-enterprise`**: Canonical signed release incorporating hostile reviewer remediations.

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

# 4. Run reproducible benign corpus evaluation (GOROOT + 5 pinned modules)
./scripts/benign-corpus.sh

# 5. Verify tag signature independently
TEMP_SIGNERS=$(mktemp)
echo "releases@artix.ai ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ4FO6aRcfHA07XqA9SGUUfpiqYfLsaRz+KUwigN2K5x" > "$TEMP_SIGNERS"
git -c gpg.ssh.allowedSignersFile="$TEMP_SIGNERS" tag -v v0.9.0-enterprise
rm -f "$TEMP_SIGNERS"
```
