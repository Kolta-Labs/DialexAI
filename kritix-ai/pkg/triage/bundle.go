package triage

import (
	"fmt"
	"strings"
	"time"

	"kritix/pkg/driver"
)

// Severity indicates defect impact.
type Severity string

const (
	SeverityBlocker  Severity = "blocker"
	SeverityCritical Severity = "critical"
	SeverityMajor    Severity = "major"
	SeverityMinor    Severity = "minor"
)

// DefectCategory categorizes whether a failure is application code or environmental.
type DefectCategory string

const (
	CategoryCodeRegression DefectCategory = "code_regression"
	CategoryInfraFlake     DefectCategory = "infra_timeout_or_502"
	CategoryUnclassified   DefectCategory = "unclassified"
)

// DefectReport encapsulates all artifacts necessary to reproduce and fix a bug.
type DefectReport struct {
	ID                 string                `json:"id"`
	Title              string                `json:"title"`
	Severity           Severity              `json:"severity"`
	Category           DefectCategory        `json:"category"`
	TargetURL          string                `json:"target_url"`
	StepsToReproduce   []string              `json:"steps_to_reproduce"`
	ExpectedBehavior   string                `json:"expected_behavior"`
	ActualBehavior     string                `json:"actual_behavior"`
	ConsoleErrors      []string              `json:"console_errors"`
	FailedNetworkReqs  []driver.NetworkEvent `json:"failed_network_reqs,omitempty"`
	PlaywrightRepro    string                `json:"playwright_repro"`
	CurlRepro          string                `json:"curl_repro,omitempty"`
	FailureFile        string                `json:"failure_file,omitempty"`
	FailureLine        int                   `json:"failure_line,omitempty"`
	GitBlameHint       string                `json:"git_blame_hint,omitempty"`
	AIDiagnosisComment string                `json:"ai_diagnosis_comment,omitempty"`
	ProposedFixDiff    string                `json:"proposed_fix_diff,omitempty"`
	ConfidenceScore    float64               `json:"confidence_score"` // 0.0 to 1.0
	Tags               []string              `json:"tags"`             // e.g. ["kritix-ai-generated", "needs-triage"]
	DiscoveredAt       time.Time             `json:"discovered_at"`
}

// ShouldEmitSuggestedDiff protects developers from AI spam.
// Diff is ONLY attached if confidence >= 0.85 and defect is a proven code regression, NOT an infra timeout!
func (d *DefectReport) ShouldEmitSuggestedDiff() bool {
	if d.Category == CategoryInfraFlake {
		return false
	}
	return d.ConfidenceScore >= 0.85 && strings.TrimSpace(d.ProposedFixDiff) != ""
}

// FormatJiraMarkdown renders a clean ticket strictly prioritizing the deterministic Playwright repro.
func (d *DefectReport) FormatJiraMarkdown() string {
	var sb strings.Builder

	sb.WriteString(fmt.Sprintf("h2. 🚨 [KRITIX-AI: UNREVIEWED] %s\n\n", d.Title))
	sb.WriteString(fmt.Sprintf("*Status:* `[KRITIX-AI: UNREVIEWED]` (Requires SDET sign-off to mark `[KRITIX-AI: VERIFIED]` before action)\n"))
	sb.WriteString(fmt.Sprintf("*Severity:* %s | *Category:* %s | *AI Confidence:* %.0f%%\n",
		d.Severity, d.Category, d.ConfidenceScore*100))
	sb.WriteString(fmt.Sprintf("*Tags:* %s\n\n", strings.Join(d.Tags, ", ")))

	sb.WriteString("h3. 1. Deterministic Playwright Reproduction (Copy & Paste)\n")
	sb.WriteString("{code:typescript}\n")
	sb.WriteString(d.PlaywrightRepro)
	sb.WriteString("{code}\n\n")

	if d.CurlRepro != "" {
		sb.WriteString("h3. 2. Network Curl Reproduction\n")
		sb.WriteString("{code:bash}\n")
		sb.WriteString(d.CurlRepro)
		sb.WriteString("{code}\n\n")
	}

	if d.GitBlameHint != "" {
		sb.WriteString(fmt.Sprintf("*Suspected Commit / Author:* %s\n\n", d.GitBlameHint))
	}

	// AI hypothesis only included with clear advisory warning
	if d.AIDiagnosisComment != "" {
		sb.WriteString("h3. 3. AI Root-Cause Hypothesis (Advisory)\n")
		sb.WriteString(fmt.Sprintf("> _Note: AI-generated assessment. Verify against system logs before action._\n\n%s\n\n", d.AIDiagnosisComment))
	}

	// Gated diff: ONLY if confidence >= 85%
	if d.ShouldEmitSuggestedDiff() {
		sb.WriteString(fmt.Sprintf("h3. 4. Suggested Code Patch (⚠️ DO NOT APPLY WITHOUT REVIEW - Confidence: %.0f%%)\n", d.ConfidenceScore*100))
		sb.WriteString("*⚠️ CAUTION: AI-generated diffs may be syntactically valid but semantically flawed. Mandatory SDET / Tech Lead review required before applying.*\n")
		sb.WriteString("{code:diff}\n")
		sb.WriteString(d.ProposedFixDiff)
		sb.WriteString("\n{code}\n")
	} else if d.Category == CategoryInfraFlake {
		sb.WriteString("_ℹ️ Code diff suppressed: Defect detected as upstream infrastructure timeout or gateway error (502/504), not an application code bug._\n")
	} else if d.ConfidenceScore < 0.85 {
		sb.WriteString("_ℹ️ Code diff suppressed: AI confidence score (<85%) below threshold to prevent incorrect code suggestions._\n")
	}

	return sb.String()
}

// ClassifyFailure categorizes whether an error is environmental or code logic.
func ClassifyFailure(consoleErrors []string, netEvents []driver.NetworkEvent) DefectCategory {
	for _, ne := range netEvents {
		if ne.StatusCode == 502 || ne.StatusCode == 503 || ne.StatusCode == 504 {
			return CategoryInfraFlake
		}
	}
	for _, ce := range consoleErrors {
		ceLower := strings.ToLower(ce)
		if strings.Contains(ceLower, "econnrefused") ||
			strings.Contains(ceLower, "gateway timeout") ||
			strings.Contains(ceLower, "network request failed") {
			return CategoryInfraFlake
		}
	}
	return CategoryCodeRegression
}

// GeneratePlaywrightRepro builds a standalone, copy-pasteable Playwright TypeScript test.
func GeneratePlaywrightRepro(targetURL string, actions []driver.Action, expectedCondition string) string {
	var sb strings.Builder
	sb.WriteString("import { test, expect } from '@playwright/test';\n\n")
	sb.WriteString("test('reproduce issue: " + escapeString(expectedCondition) + "', async ({ page }) => {\n")
	sb.WriteString(fmt.Sprintf("  // Step 1: Navigate to target URL\n"))
	sb.WriteString(fmt.Sprintf("  await page.goto(%q);\n\n", targetURL))

	for i, a := range actions {
		sb.WriteString(fmt.Sprintf("  // Step %d: %s\n", i+2, a.Description))
		switch a.Type {
		case driver.ActionClick:
			if a.TargetXPath != "" {
				sb.WriteString(fmt.Sprintf("  await page.locator(%q).click();\n", a.TargetXPath))
			} else if a.TargetRole != "" && a.TargetText != "" {
				sb.WriteString(fmt.Sprintf("  await page.getByRole(%q, { name: %q }).click();\n", a.TargetRole, a.TargetText))
			} else if a.TargetText != "" {
				sb.WriteString(fmt.Sprintf("  await page.getByText(%q).click();\n", a.TargetText))
			}
		case driver.ActionTypeKey:
			if a.TargetXPath != "" {
				sb.WriteString(fmt.Sprintf("  await page.locator(%q).fill(%q);\n", a.TargetXPath, a.Value))
			}
		case driver.ActionWait:
			sb.WriteString(fmt.Sprintf("  await page.waitForTimeout(%s);\n", a.Value))
		}
	}

	sb.WriteString("\n  // Verification / Assertion\n")
	sb.WriteString(fmt.Sprintf("  // Expected: %s\n", expectedCondition))
	sb.WriteString("  await expect(page).toHaveScreenshot('repro_failure.png');\n")
	sb.WriteString("});\n")

	return sb.String()
}

// GenerateCurlRepro creates an exact curl command reproducing an API or network failure.
func GenerateCurlRepro(event driver.NetworkEvent) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("curl -i -X %s %q", event.Method, event.URL))
	for k, v := range event.Headers {
		sb.WriteString(fmt.Sprintf(" \\\n  -H %q", fmt.Sprintf("%s: %s", k, v)))
	}
	sb.WriteString("\n")
	return sb.String()
}

func escapeString(s string) string {
	s = strings.ReplaceAll(s, "'", "\\'")
	s = strings.ReplaceAll(s, "\n", " ")
	return s
}
