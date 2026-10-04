# Formal Threat Model: Session State & Credential Lifecycle

**Platform**: Kritix AI Enterprise Testing Engine  
**Standard Compliance**: SOC 2 Type II (CC6.1, CC6.3), ISO 27001 (A.9.4.2), FedRAMP SC-18  
**Scope**: Playwright `storageState`, Cookies, LocalStorage, MFA Virtual TOTP, and CI/CD Runner Environments  

---

## 1. Asset Inventory & Data Classification

| Asset | Classification | Storage Location | Risk Exposure |
|---|---|---|---|
| **Active Session Cookies** | Confidential / High | Memory / Ephemeral | Session Hijacking against Staging/Prod |
| **LocalStorage Auth Tokens (JWT/OAuth)** | Confidential / High | Memory / Ephemeral | Unauthorized API Impersonation |
| **Virtual TOTP Base32 Secrets** | Restricted / Critical | Environment / KMS | Multi-Factor Authentication Bypass |
| **CI Runner Disk Cache** | Internal | Runner Local Disk | Cross-job credential exfiltration |

---

## 2. Threat Actors & Attack Vectors

### Threat Actor 1: Compromised CI/CD Runner or Malicious PR Code
- **Vector**: A pull request containing malicious build scripts inspects the workspace directory for `storageState.json` or dumps environment variables.
- **Impact**: Attacker steals active session cookies, impersonating authenticated users in staging or internal microservices.
- **Mitigation**:
  1. **Zero Plaintext Disk Writes**: Kritix AI defaults to `EphemeralSessionStore` (RAM-only). Sessions are purged via `Wipe()` upon test suite teardown.
  2. **KMS Envelope Encryption**: If persistence is required, states are written as `EnvelopeEncryptedContainer` using ephemeral AES-256-GCM Data Encryption Keys (DEKs) encrypted by a customer Key Encryption Key (KEK) via AWS KMS / HashiCorp Vault.
  3. **Pre-Commit Enforcement**: The `pre-commit-guard.sh` hook halts commits containing session tokens or `storageState*.json` files.

### Threat Actor 2: Accidental Git Repository Exposure
- **Vector**: An engineer or script accidentally adds `storageState.json` to a Git commit, pushing live session cookies to public or enterprise GitHub/GitLab repositories.
- **Impact**: Permanent credential exposure in git history requiring global credential revocation.
- **Mitigation**:
  1. Gitignore default: `*storageState*.json` and `*.enc` are hardcoded into `.gitignore`.
  2. Pre-commit hook actively parses file diffs for cookie/localStorage dumps and blocks the commit with exit code 1.
  3. Short-lived TTL: All session states enforce a mandatory maximum TTL of 4 hours (`ErrSessionExpired`). Even if a leaked token was pushed, it is rendered inert within hours.

### Threat Actor 3: Cross-Tenant Session Contamination
- **Vector**: In a multi-tenant CI runner pool, test runs from Squad A access residual cookies or localStorage items left behind by Squad B.
- **Impact**: Cross-tenant data leakage and test flakiness due to contaminated session caches.
- **Mitigation**:
  1. Each test run generates a unique `tenant_id` and isolated ephemeral browser context (`BrowserContext.close()` destroys cache, indexedDB, and service workers).
  2. Automatic memory wipe after every workflow DAG execution.

---

## 3. Cryptographic Specification

- **Envelope Encryption Standard**: AES-256-GCM with 96-bit random nonce generated via `crypto/rand`.
- **Key Hierarchy**:
  - **Master KEK**: Customer-managed via AWS KMS (`kms:Encrypt`, `kms:Decrypt`), GCP Cloud KMS, or HashiCorp Vault Transit engine.
  - **Ephemeral DEK**: 32-byte cryptographically secure random key generated per session file, never reused across test executions.
- **File Permissions**: In the rare case of encrypted disk persistence, files are written with POSIX `0600` (read/write restricted strictly to the runner process user).
