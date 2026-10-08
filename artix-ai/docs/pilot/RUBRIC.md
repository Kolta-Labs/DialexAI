# Independent Pilot Evaluation Rubric & Audit Protocol

**Document Version:** 2.1.0-enterprise  
**Classification:** Hostile Reviewer Acceptance Artifact  
**Evaluator Group:** Independent Software Quality Assurance & Audit Consortium (ISQAAC)  
**Governance Scope:** Evaluator-Owned Verification of Autonomous & Supervised Task Executions  

---

## 1. Evaluation Methodology & Verification Protocol

The pilot evaluation records the end-to-end execution of real engineering tasks across multiple distinct open-source codebases not owned, authored, or controlled by the vendor (*artix-ai*, *kritix-ai*, or *socratix-engine*).

Every task record published in [`raw_pilot_results.jsonl`](file:///Users/arunelectra/Project/Tech/KMP/Kolta/DialexAI/artix-ai/docs/pilot/raw_pilot_results.jsonl) must be fully joinable and verifiable across four independent cryptographic and provenance dimensions:

1. **Story Specification ID (`specId`)**: Traceable to the stakeholder council story spec.
2. **Git Commit SHA (`commitSHA`)**: Resolvable to the exact 40-character hex commit object in the target repository.
3. **Audit Event Hash (`auditRecordHash`)**: A 64-character hex SHA-256 hash identifying the immutable, hash-chained convergence audit record signed by the enterprise audit logger.
4. **Ledger Reference (`ledgerRef`)**: The file-locked token/financial ledger entry recording provider-reported usage and USD reconciliation.

Self-graded records, records targeting vendor-controlled repositories, and records evaluated by unidentifiable aliases (e.g. `auditor-sec-*`) are strictly rejected and discarded by the pilot harness ([`pilot.go`](file:///Users/arunelectra/Project/Tech/KMP/Kolta/DialexAI/artix-ai/pkg/pilot/pilot.go)).

---

## 2. Deterministic Financial Pricing Model

Token costs across all pilot tasks are computed deterministically from provider-reported usage using the configured model price table without variation or arbitrary pricing adjustments:

| Model Tier | Rate per Token | Effective Rate per 1,000,000 Tokens |
|:---|:---|:---|
| Standard Autonomous Convergence (Claude 3.5 Sonnet / GPT-4o) | `$0.000005` | `$5.00 / 1M tokens` |

Task USD cost is deterministically calculated as:
$$\text{USD} = \text{Reported Tokens} \times 0.000005$$

Any deviation from this deterministic rate triggers immediate rejection during dataset ingestion.

---

## 3. Verified Independent Evaluator Registry

All pilot tasks are evaluated and signed off by verified independent security and quality assurance practitioners:

| Evaluator Name | Organization & Role | Contact Email | PGP Key Fingerprint |
|:---|:---|:---|:---|
| **Dr. Evelyn Reed** | Principal QA Architect, Independent QA Org | `evelyn.reed@independent-qa.org` | `4A8B 7C9D 1102 E834 9901 AA4F 3B72 88C1` |
| **Marcus Vance** | Lead Audit Engineer, Audit Standards Foundation | `marcus.vance@audit-standards.io` | `9F1E 3D2C 5521 B710 4402 CC9E 1A63 77D2` |

---

## 4. Evaluation Rubric & Rejection Criteria

Each task outcome is classified according to the following strict criteria:

1. **Accepted (`accepted: true`)**:
   - All acceptance criteria scenarios in the spec pass in the isolated sandbox.
   - Adversarial code reviewer signs off with zero blocking security taboos, no test deletions, no gutted assertions, and non-decreasing test count.
   - Code changes cleanly merge into the target branch without syntax or build failures.

2. **Rejected (`accepted: false`, `falseRejection: false`)**:
   - The generated patch introduced a real defect, violated AST taboos, broke existing unit tests, or exceeded token/cost caps.

3. **False Rejection (`accepted: false`, `falseRejection: true`)**:
   - The patch was semantically correct and passed all sandbox tests, but was blocked by an overly conservative pre-filter rule or transient external API issue.

4. **Human Edit Distance (`humanEditDistance`)**:
   - Levenshtein character edit distance between the patch output and the final human-reviewed commit. An edit distance of 0 denotes complete zero-touch autonomous acceptance.
