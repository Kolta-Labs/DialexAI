package workflow

func init() {
	RegisterBlueprint(&TicketToShipBlueprint{})
	RegisterBlueprint(&PRSmokeGuardBlueprint{})
	RegisterBlueprint(&APIContractFuzzerBlueprint{})
	RegisterBlueprint(&NightlyDeepAuditBlueprint{})
	RegisterBlueprint(&VisualA11yAuditBlueprint{})
	RegisterBlueprint(&SelfHealingMaintenanceBlueprint{})
	RegisterBlueprint(&OfflineContractAuditBlueprint{})
}

// -------------------------------------------------------------------------
// 1. TICKET-TO-SHIP BLUEPRINT (Flagship Enterprise Multi-Doc Story Verification)
// -------------------------------------------------------------------------

type TicketToShipBlueprint struct{}

func (b *TicketToShipBlueprint) Descriptor() BlueprintDescriptor {
	return BlueprintDescriptor{
		ID:             "ticket-to-ship",
		Name:           "Ticket-to-Ship Verification",
		Category:       "feature",
		Tier:           Tier3Nightly,
		Description:    "Asynchronous deep enterprise verification from Jira ticket, FDD, Copy Doc, Tagging Doc, and A11y notes through to Playwright matrix execution (Post-merge / Feature branch)",
		TargetAudience: "Enterprise Scrum Teams, SDETs, and Product Owners",
		DefaultTimeout: "15m",
		ZeroLLM:        false,
		FastPath:       false,
	}
}

func (b *TicketToShipBlueprint) BuildDAG() (*DAG, error) {
	dag := NewDAG("ticket-to-ship", "Enterprise Multi-Doc Story Verification")
	dag.SetTier(Tier3Nightly)

	// Node 1: Ingest Multi-Doc Bundle
	dag.AddNode("ingest_docs", &IngestMultiDocBlock{})

	// Node 2: Socratic Cross-Model Audit (Depends on ingest_docs)
	dag.AddNode("socratic_audit", &ReviewSocraticBlock{}, "ingest_docs")

	// Node 3: Multi-Viewport Matrix Execution (Depends on socratic_audit)
	dag.AddNode("matrix_execution", &ExecMatrixBlock{}, "socratic_audit")

	// Node 4: Tagging & Analytics Assertion (Depends on matrix_execution)
	dag.AddNode("assert_analytics", &AssertAnalyticsBlock{}, "matrix_execution")

	// Node 5: Jira / Linear Defect Sync (Depends on assert_analytics)
	dag.AddNode("jira_triage", &SyncJiraBlock{}, "assert_analytics")

	return dag, nil
}

// -------------------------------------------------------------------------
// 2. API CONTRACT FUZZER BLUEPRINT
// -------------------------------------------------------------------------

type APIContractFuzzerBlueprint struct{}

func (b *APIContractFuzzerBlueprint) Descriptor() BlueprintDescriptor {
	return BlueprintDescriptor{
		ID:             "api-contract-fuzzer",
		Name:           "Autonomous API Boundary & Contract Fuzzer",
		Category:       "api",
		Tier:           Tier3Nightly,
		Description:    "Ingests OpenAPI schemas, generates boundary values, and verifies SLA latency budgets with zero human test script authoring.",
		TargetAudience: "Backend Platform Engineers, API Guild, and DevOps",
		DefaultTimeout: "10m",
		ZeroLLM:        true,
		FastPath:       true,
	}
}

func (b *APIContractFuzzerBlueprint) BuildDAG() (*DAG, error) {
	dag := NewDAG("api-contract-fuzzer", "Autonomous API Boundary & Contract Fuzzer")
	dag.SetTier(Tier3Nightly)

	// Node 1: Verify Staging Isolation
	dag.AddNode("verify_isolation", &SecurityIsolationBlock{})

	// Node 2: Ingest OpenAPI Specification
	dag.AddNode("ingest_openapi", &IngestOpenAPIBlock{})

	// Node 3: Boundary Value Schema Fuzzing
	dag.AddNode("boundary_fuzzing", &ExecFuzzBlock{}, "verify_isolation", "ingest_openapi")

	// Node 4: Latency Distribution & SLA Budget Audit
	dag.AddNode("latency_audit", &PerfLatencyBlock{}, "boundary_fuzzing")

	return dag, nil
}

// -------------------------------------------------------------------------
// 3. NIGHTLY DEEP AUDIT BLUEPRINT (Autonomous Chaos & Penetration Engine)
// -------------------------------------------------------------------------

type NightlyDeepAuditBlueprint struct{}

func (b *NightlyDeepAuditBlueprint) Descriptor() BlueprintDescriptor {
	return BlueprintDescriptor{
		ID:             "nightly-deep-audit",
		Name:           "Nightly Autonomous Chaos & Deep Audit",
		Category:       "chaos",
		Tier:           Tier3Nightly,
		Description:    "Autonomous multimodal vision crawl, OWASP Top 10 security audit, k6 spike load testing, and executive scorecard generation.",
		TargetAudience: "Engineering Leadership, Security Guild, and Release Managers",
		DefaultTimeout: "45m",
		ZeroLLM:        false,
		FastPath:       false,
	}
}

func (b *NightlyDeepAuditBlueprint) BuildDAG() (*DAG, error) {
	dag := NewDAG("nightly-deep-audit", "Nightly Autonomous Chaos & Deep Audit")
	dag.SetTier(Tier3Nightly)

	// Node 1: Verify Staging Infrastructure Isolation
	dag.AddNode("verify_isolation", &SecurityIsolationBlock{})

	// Node 2: Autonomous Multimodal Vision Crawl (Depends on verify_isolation)
	dag.AddNode("vision_crawl", &AgentVisionCrawlBlock{}, "verify_isolation")

	// Node 3: OWASP Top 10 Security Audit (Depends on verify_isolation)
	dag.AddNode("owasp_dast", &ExecOWASPDASTBlock{}, "verify_isolation")

	// Node 4: k6 Spike Load Test (Depends on verify_isolation)
	dag.AddNode("k6_spike_load", &PerfK6SpikeBlock{}, "verify_isolation")

	// Node 5: Compile Executive Quality Scorecard (Depends on all three audits)
	dag.AddNode("executive_report", &ReportExecutiveBlock{}, "vision_crawl", "owasp_dast", "k6_spike_load")

	return dag, nil
}

// -------------------------------------------------------------------------
// 4. VISUAL & ACCESSIBILITY AUDIT BLUEPRINT
// -------------------------------------------------------------------------

type VisualA11yAuditBlueprint struct{}

func (b *VisualA11yAuditBlueprint) Descriptor() BlueprintDescriptor {
	return BlueprintDescriptor{
		ID:             "visual-a11y-audit",
		Name:           "Visual Regression & WCAG 2.1 AAA Audit",
		Category:       "a11y",
		Tier:           Tier2MergeGate,
		Description:    "Captures responsive snapshots and audits full WCAG 2.1 compliance (contrast, ARIA tree, touch targets).",
		TargetAudience: "UI/UX Designers, Accessibility Champions, and Frontend Engineers",
		DefaultTimeout: "5m",
		ZeroLLM:        true,
		FastPath:       true,
	}
}

func (b *VisualA11yAuditBlueprint) BuildDAG() (*DAG, error) {
	dag := NewDAG("visual-a11y-audit", "Visual Regression & WCAG 2.1 AAA Audit")
	dag.SetTier(Tier2MergeGate)

	// Node 1: Capture Responsive Viewports
	dag.AddNode("viewport_snapshots", &DriverViewportsBlock{})

	// Node 2: WCAG 2.1 AA/AAA Audit
	dag.AddNode("wcag_audit", &AuditWCAGBlock{}, "viewport_snapshots")

	return dag, nil
}

// -------------------------------------------------------------------------
// 5. SELF-HEALING MAINTENANCE BLUEPRINT
// -------------------------------------------------------------------------

type SelfHealingMaintenanceBlueprint struct{}

func (b *SelfHealingMaintenanceBlueprint) Descriptor() BlueprintDescriptor {
	return BlueprintDescriptor{
		ID:             "self-healing-maintenance",
		Name:           "Autonomous Self-Healing Test Maintenance",
		Category:       "maintenance",
		Tier:           Tier3Nightly,
		Description:    "Ingests existing Playwright/Cypress test repositories, repairs mutated selectors via AXTree + geometry anchors, and submits automated Git Pull Requests.",
		TargetAudience: "SDETs, QA Automation Engineers, and Platform Teams",
		DefaultTimeout: "10m",
		ZeroLLM:        true,
		FastPath:       true,
	}
}

func (b *SelfHealingMaintenanceBlueprint) BuildDAG() (*DAG, error) {
	dag := NewDAG("self-healing-maintenance", "Autonomous Self-Healing Test Maintenance")
	dag.SetTier(Tier3Nightly)

	// Node 1: Ingest Test Repo
	dag.AddNode("ingest_existing_tests", &IngestTestsBlock{})

	// Node 2: Heal Selectors
	dag.AddNode("heal_locators", &SDETSelfHealBlock{}, "ingest_existing_tests")

	// Node 3: Open Git PR
	dag.AddNode("create_git_pr", &GitPRBlock{}, "heal_locators")

	return dag, nil
}

// -------------------------------------------------------------------------
// 6. OFFLINE CONTRACT AUDIT BLUEPRINT (Zero-Telemetry Sovereign Offline Engine)
// -------------------------------------------------------------------------

type OfflineContractAuditBlueprint struct{}

func (b *OfflineContractAuditBlueprint) Descriptor() BlueprintDescriptor {
	return BlueprintDescriptor{
		ID:             "offline-contract-audit",
		Name:           "Offline OpenAPI Contract Audit",
		Category:       "offline",
		Tier:           Tier1PRGate,
		Description:    "Completely offline, air-gapped OpenAPI contract validation and schema parsing with guaranteed zero network egress.",
		TargetAudience: "Security Guild, Sovereign Cloud Engineers",
		DefaultTimeout: "30s",
		ZeroLLM:        true,
		FastPath:       true,
	}
}

func (b *OfflineContractAuditBlueprint) BuildDAG() (*DAG, error) {
	dag := NewDAG("offline-contract-audit", "Offline OpenAPI Contract Audit")
	dag.SetTier(Tier1PRGate)
	dag.AddNode("ingest_openapi", &IngestOpenAPIBlock{})
	return dag, nil
}

