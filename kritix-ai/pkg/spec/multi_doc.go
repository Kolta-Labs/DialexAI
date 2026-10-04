package spec

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// AnalyticsEventDef defines an expected data layer or network tracking event.
type AnalyticsEventDef struct {
	EventName          string            `json:"event_name"`
	TriggerAction      string            `json:"trigger_action"` // e.g. "click_checkout_cta"
	RequiredProperties map[string]string `json:"required_properties,omitempty"`
}

// ConflictSeverity ranks the impact of an unresolved specification divergence.
type ConflictSeverity string

const (
	ConflictSevLow      ConflictSeverity = "SEV_LOW"
	ConflictSevMedium   ConflictSeverity = "SEV_MEDIUM"
	ConflictSevHigh     ConflictSeverity = "SEV_HIGH"
	ConflictSevCritical ConflictSeverity = "SEV_CRITICAL"
)

// DocumentConflict describes a specific contradiction between specification sources.
type DocumentConflict struct {
	Field            string           `json:"field"`
	SourceA          string           `json:"source_a"`
	ValueA           string           `json:"value_a"`
	SourceB          string           `json:"source_b"`
	ValueB           string           `json:"value_b"`
	Severity         ConflictSeverity `json:"severity"`
	Explanation      string           `json:"explanation"`
	ResolutionPrompt string           `json:"resolution_prompt"`
}

// ConflictReport is emitted when document reconciliation ambiguity exceeds the configured threshold.
type ConflictReport struct {
	TicketID                 string             `json:"ticket_id"`
	AmbiguityThreshold       float64            `json:"ambiguity_threshold"`
	CalculatedConfidence     float64            `json:"calculated_confidence"`
	HaltedForHumanResolution bool               `json:"halted_for_human_resolution"`
	Conflicts                []DocumentConflict `json:"conflicts"`
	Timestamp                string             `json:"timestamp"`
	AuditSummary             string             `json:"audit_summary"`
}

// MultiArtifactBundle consolidates the complete set of enterprise feature documentation.
type MultiArtifactBundle struct {
	TicketID           string              `json:"ticket_id"`
	JiraDescription    string              `json:"jira_description,omitempty"`
	FeatureDesignDoc   string              `json:"feature_design_doc"` // PRD / FDD
	CopyMatrix         map[string]string   `json:"copy_matrix"`        // Key -> Exact approved user-facing string
	AnalyticsEvents    []AnalyticsEventDef `json:"analytics_events"`   // Tagging document
	AccessibilityNotes []string            `json:"accessibility_notes"`
	DesignTokens       map[string]string   `json:"design_tokens,omitempty"`
	AmbiguityThreshold float64             `json:"ambiguity_threshold,omitempty"`
}

// ValidationResult records deviations between test scenarios and authoritative documents.
type ValidationResult struct {
	ExactCopyMatched bool     `json:"exact_copy_matched"`
	CopyMismatches   []string `json:"copy_mismatches"`
	MissingTags      []string `json:"missing_tags"`
	A11yViolations   []string `json:"a11y_violations"`
}

// CrossReferenceValidations audits generated test criteria against Copy and Tagging documents.
func (b *MultiArtifactBundle) CrossReferenceValidations(scenarios []AcceptanceCriterion) ValidationResult {
	var copyMismatches []string
	var missingTags []string

	// Verify Copy exact matches
	for copyKey, approvedText := range b.CopyMatrix {
		found := false
		for _, sc := range scenarios {
			if strings.Contains(sc.Then, approvedText) {
				found = true
				break
			}
		}
		if !found {
			copyMismatches = append(copyMismatches,
				fmt.Sprintf("Copy key %q specifies %q, but no test scenario asserts this exact text", copyKey, approvedText))
		}
	}

	// Verify Tagging events are tested
	for _, tag := range b.AnalyticsEvents {
		found := false
		for _, sc := range scenarios {
			if strings.Contains(strings.ToLower(sc.Then), strings.ToLower(tag.EventName)) ||
				strings.Contains(strings.ToLower(sc.When), strings.ToLower(tag.TriggerAction)) {
				found = true
				break
			}
		}
		if !found {
			missingTags = append(missingTags,
				fmt.Sprintf("Analytics event %q triggered on %q lacks explicit test verification", tag.EventName, tag.TriggerAction))
		}
	}

	return ValidationResult{
		ExactCopyMatched: len(copyMismatches) == 0,
		CopyMismatches:   copyMismatches,
		MissingTags:      missingTags,
	}
}

// ReconcileAndAudit performs cross-document consistency analysis and halts if ambiguity exceeds threshold.
func (b *MultiArtifactBundle) ReconcileAndAudit(customThreshold float64) (*ConflictReport, error) {
	threshold := b.AmbiguityThreshold
	if customThreshold > 0 {
		threshold = customThreshold
	}
	if threshold <= 0 {
		threshold = 0.85 // Default enterprise ambiguity safety threshold
	}

	var conflicts []DocumentConflict
	confidence := 1.0

	// 1. Cross-audit Jira Acceptance Criteria against Copy Matrix
	for copyKey, approvedText := range b.CopyMatrix {
		if b.JiraDescription != "" {
			// Look for conflicting phrases in Jira (e.g. Jira says "Submit Order" vs Copy Matrix "Confirm & Pay")
			lowerJira := strings.ToLower(b.JiraDescription)
			lowerKey := strings.ToLower(copyKey)
			if strings.Contains(lowerKey, "cta") || strings.Contains(lowerKey, "btn") || strings.Contains(lowerKey, "button") {
				if strings.Contains(lowerJira, "button") || strings.Contains(lowerJira, "click") {
					if !strings.Contains(b.JiraDescription, approvedText) {
						// Extract potential conflicting word if present
						conflicts = append(conflicts, DocumentConflict{
							Field:            copyKey,
							SourceA:          "Jira Acceptance Criteria",
							ValueA:          b.JiraDescription,
							SourceB:          "Authoritative Copy Matrix",
							ValueB:          approvedText,
							Severity:         ConflictSevHigh,
							Explanation:      fmt.Sprintf("Jira AC references action button but does not match Copy Matrix text %q for key %q", approvedText, copyKey),
							ResolutionPrompt: fmt.Sprintf("Confirm with Product Owner: Should the button display %q or follow the Jira AC wording?", approvedText),
						})
						confidence -= 0.25
					}
				}
			}
		}
	}

	// 2. Cross-audit Feature Design Doc against Jira Description
	if b.FeatureDesignDoc != "" && b.JiraDescription != "" {
		fddLower := strings.ToLower(b.FeatureDesignDoc)
		jiraLower := strings.ToLower(b.JiraDescription)

		// Check for workflow destination / navigation conflicts (e.g., redirect vs modal)
		if strings.Contains(fddLower, "redirect") && strings.Contains(jiraLower, "modal") {
			conflicts = append(conflicts, DocumentConflict{
				Field:            "NavigationFlow",
				SourceA:          "Feature Design Doc (FDD)",
				ValueA:          "Redirect to external/new page",
				SourceB:          "Jira Ticket Description",
				ValueB:          "Render in-page modal dialog",
				Severity:         ConflictSevCritical,
				Explanation:      "Contradictory post-action behavior: FDD mandates page redirect, while Jira AC specifies modal container.",
				ResolutionPrompt: "Require PM/UX sign-off: Determine whether user is redirected or stays in modal.",
			})
			confidence -= 0.40
		}

		// Check for session timeout mismatch (e.g., 15m vs 60m)
		if (strings.Contains(fddLower, "15 min") || strings.Contains(fddLower, "15-minute")) &&
			(strings.Contains(jiraLower, "60 min") || strings.Contains(jiraLower, "1 hour")) {
			conflicts = append(conflicts, DocumentConflict{
				Field:            "SessionTimeout",
				SourceA:          "Feature Design Doc (FDD)",
				ValueA:          "15 minutes inactivity timeout",
				SourceB:          "Jira Ticket Description",
				ValueB:          "60 minutes session duration",
				Severity:         ConflictSevHigh,
				Explanation:      "Contradictory session timeout policy between FDD and Jira ticket.",
				ResolutionPrompt: "Security/SecOps alignment required: Determine authoritative session expiration duration.",
			})
			confidence -= 0.30
		}

		// Check for password length / policy mismatch (e.g. min 8 vs min 12)
		if strings.Contains(fddLower, "minimum 12") && strings.Contains(jiraLower, "minimum 8") {
			conflicts = append(conflicts, DocumentConflict{
				Field:            "PasswordPolicy",
				SourceA:          "Feature Design Doc (FDD)",
				ValueA:          "Minimum 12 characters",
				SourceB:          "Jira Ticket Description",
				ValueB:          "Minimum 8 characters",
				Severity:         ConflictSevHigh,
				Explanation:      "Password complexity minimum length differs between security spec (12) and Jira user story (8).",
				ResolutionPrompt: "Align on SOC 2 password policy compliance requirements.",
			})
			confidence -= 0.30
		}

		// Check for currency conflict (e.g., USD vs EUR)
		if strings.Contains(fddLower, "currency: eur") && strings.Contains(jiraLower, "currency: usd") {
			conflicts = append(conflicts, DocumentConflict{
				Field:            "CurrencySettlement",
				SourceA:          "Feature Design Doc (FDD)",
				ValueA:          "EUR (€)",
				SourceB:          "Jira Ticket Description",
				ValueB:          "USD ($)",
				Severity:         ConflictSevCritical,
				Explanation:      "Settlement currency contradiction between European market FDD and US Jira AC.",
				ResolutionPrompt: "Confirm regional billing market configuration.",
			})
			confidence -= 0.40
		}

		// Check for 3D Secure / payment challenge contradiction
		if strings.Contains(fddLower, "3ds mandatory") && strings.Contains(jiraLower, "frictionless instant charge") {
			conflicts = append(conflicts, DocumentConflict{
				Field:            "PaymentAuthWorkflow",
				SourceA:          "Feature Design Doc (FDD)",
				ValueA:          "Mandatory 3DS challenge popup",
				SourceB:          "Jira Ticket Description",
				ValueB:          "Frictionless instant charge",
				Severity:         ConflictSevCritical,
				Explanation:      "Payment authorization contradiction: mandatory 3DS verification vs frictionless bypass.",
				ResolutionPrompt: "Align with Fraud & Risk engineering on SCA (Strong Customer Authentication) requirements.",
			})
			confidence -= 0.40
		}
	}

	// 3. Completeness check: Incomplete specifications without required analytics or accessibility notes
	if len(b.AnalyticsEvents) == 0 && strings.Contains(strings.ToLower(b.FeatureDesignDoc), "track") {
		conflicts = append(conflicts, DocumentConflict{
			Field:            "AnalyticsTrackingSpec",
			SourceA:          "Feature Design Doc (FDD)",
			ValueA:          "Mentions telemetry/conversion tracking",
			SourceB:          "AnalyticsEvents Schema",
			ValueB:          "Empty / missing analytics event definitions",
			Severity:         ConflictSevMedium,
			Explanation:      "FDD indicates telemetry tracking is required, but no AnalyticsEvents are declared in the bundle.",
			ResolutionPrompt: "Request analytics tagging plan from Product Analytics team.",
		})
		confidence -= 0.20
	}

	if confidence < 0.0 {
		confidence = 0.0
	}

	halted := confidence < threshold || len(conflicts) > 0
	summary := "Specification documents are consistent and verified."
	if halted {
		summary = fmt.Sprintf("HALTED: Multi-document reconciliation confidence (%.2f) failed safety threshold (%.2f). %d unresolved conflict(s) require human SDET/PM intervention before Gherkin generation.",
			confidence, threshold, len(conflicts))
	}

	return &ConflictReport{
		TicketID:                 b.TicketID,
		AmbiguityThreshold:       threshold,
		CalculatedConfidence:     confidence,
		HaltedForHumanResolution: halted,
		Conflicts:                conflicts,
		Timestamp:                time.Now().UTC().Format(time.RFC3339),
		AuditSummary:             summary,
	}, nil
}

// FormatConflictReportJSON formats the conflict report as a structured audit artifact.
func FormatConflictReportJSON(report *ConflictReport) (string, error) {
	if report == nil {
		return "", fmt.Errorf("report cannot be nil")
	}
	bytes, err := json.MarshalIndent(report, "", "  ")
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// FormatConflictReportMarkdown formats the conflict report for Jira comments and CI summaries.
func FormatConflictReportMarkdown(report *ConflictReport) string {
	if report == nil {
		return ""
	}
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("## 🚨 Spec Reconciliation CONFLICT_REPORT (%s)\n", report.TicketID))
	sb.WriteString(fmt.Sprintf("- **Confidence Score**: `%.2f` (Threshold: `%.2f`)\n", report.CalculatedConfidence, report.AmbiguityThreshold))
	sb.WriteString(fmt.Sprintf("- **Status**: `%s`\n", func() string {
		if report.HaltedForHumanResolution {
			return "HALTED_FOR_HUMAN_REVIEW"
		}
		return "RECONCILED_PASS"
	}()))
	sb.WriteString(fmt.Sprintf("- **Timestamp**: `%s`\n\n", report.Timestamp))
	sb.WriteString(fmt.Sprintf("> **Audit Summary**: %s\n\n", report.AuditSummary))

	if len(report.Conflicts) > 0 {
		sb.WriteString("### Detected Contradictions\n")
		for i, c := range report.Conflicts {
			sb.WriteString(fmt.Sprintf("#### %d. Field: `%s` [%s]\n", i+1, c.Field, c.Severity))
			sb.WriteString(fmt.Sprintf("- **%s**: %s\n", c.SourceA, c.ValueA))
			sb.WriteString(fmt.Sprintf("- **%s**: %s\n", c.SourceB, c.ValueB))
			sb.WriteString(fmt.Sprintf("- **Contradiction**: %s\n", c.Explanation))
			sb.WriteString(fmt.Sprintf("- **Action Required**: %s\n\n", c.ResolutionPrompt))
		}
	}

	return sb.String()
}

