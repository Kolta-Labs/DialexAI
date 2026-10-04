package sdet

import (
	"encoding/json"
	"fmt"
	"math"
	"strings"
	"time"

	"kritix/pkg/driver"
)

// HealingMode governs how runtime locator changes are handled.
type HealingMode string

const (
	// HealModeStrict rejects dynamic fallback during CI regression runs to prevent masking true bugs.
	// Emits a regression finding with a proposed patch rather than silently proceeding.
	HealModeStrict HealingMode = "strict"

	// HealModeAdvisory proceeds with the healed element but flags a regression warning for review.
	HealModeAdvisory HealingMode = "advisory"

	// HealModePermissive silently auto-heals without failing (suitable for exploratory crawls).
	HealModePermissive HealingMode = "permissive"
)

// ResolutionStatus marks the exact verification state of a test step.
type ResolutionStatus string

const (
	// StatusExactPass indicates a 100% deterministic match on intentional IDs.
	StatusExactPass ResolutionStatus = "EXACT_PASS"

	// StatusHealed indicates an element was found via fuzzy fallback.
	// CRITICAL: NEVER counted as a clean PASS in enterprise CI release gates!
	StatusHealed ResolutionStatus = "HEALED_REQUIRES_REVIEW"

	// StatusRegressionFail indicates an unresolvable deviation or strict mode gate trip.
	StatusRegressionFail ResolutionStatus = "REGRESSION_FAIL"
)

// ElementFingerprint holds a multi-anchor signature for resilient locator resolution.
type ElementFingerprint struct {
	ID            string      `json:"id"`
	TestID        string      `json:"test_id,omitempty"`
	Role          string      `json:"role,omitempty"`
	Text          string      `json:"text,omitempty"`
	Tag           string      `json:"tag"`
	XPath         string      `json:"xpath,omitempty"`
	ContainerID   string      `json:"container_id,omitempty"`
	ContainerRole string      `json:"container_role,omitempty"`
	ActionIntent  string      `json:"action_intent,omitempty"`
	BBox          driver.Rect `json:"bbox"`
	PageURL       string      `json:"page_url"`
}

// RiskLevel categorizes the likelihood of masking a semantic regression.
type RiskLevel string

const (
	RiskLow      RiskLevel = "LOW"      // Pure cosmetic rename with matching role and text
	RiskMedium   RiskLevel = "MEDIUM"   // Minor text variation with identical role and container
	RiskHigh     RiskLevel = "HIGH"     // Tag or role shift with inferred purpose
	RiskCritical RiskLevel = "CRITICAL" // Bounding box fallback, coordinate jump, or auth/payment path
)

// LocatorTier defines priority ranking: RoleText (1) -> TestID (2) -> ExactID (3) -> TagText (4) -> BBox (5).
type LocatorTier int

const (
	TierRoleText LocatorTier = 1 // Semantic ARIA role + accessible name (highest stability)
	TierTestID   LocatorTier = 2 // data-testid / test-id
	TierExactID  LocatorTier = 3 // HTML id attribute
	TierTagText  LocatorTier = 4 // Tag + text
	TierBBox     LocatorTier = 5 // Visual BoundingBox (Strictly prohibited for healing in CI)
)

// FallbackAttempt records each attempted locator in the priority fallback ladder.
type FallbackAttempt struct {
	Tier         LocatorTier `json:"tier"`
	Strategy     string      `json:"strategy"`
	Selector     string      `json:"selector"`
	Matched      bool        `json:"matched"`
	FailureCause string      `json:"failure_cause,omitempty"`
}

// SemanticDiffValidator verifies that locator fallbacks do not mask functional regressions, role shifts, or semantic swaps.
type SemanticDiffValidator struct {
	EnforceRoleStrictness bool    `json:"enforce_role_strictness"` // Disallows role mutations (e.g. button -> link)
	EnforceAncestryCheck  bool    `json:"enforce_ancestry_check"`  // Disallows container/ancestry mismatch
	EnforceIntentCheck    bool    `json:"enforce_intent_check"`    // Disallows same-role different-action swaps
	MaxPositionDriftPx    float64 `json:"max_position_drift_px"`   // Max allowed geometric reflow drift (default 150px)
}

// DefaultSemanticDiffValidator constructs a validator enforcing strict role, ancestry, and intent stability.
func DefaultSemanticDiffValidator() *SemanticDiffValidator {
	return &SemanticDiffValidator{
		EnforceRoleStrictness: true,
		EnforceAncestryCheck:  true,
		EnforceIntentCheck:    true,
		MaxPositionDriftPx:    150.0,
	}
}

// ValidateDiff verifies that candidate element does not exhibit dangerous semantic, ancestry, or layout drift.
func (v *SemanticDiffValidator) ValidateDiff(fp ElementFingerprint, candidate *driver.Element) (bool, string) {
	if candidate == nil {
		return false, "candidate element is nil"
	}

	// 1. Role Mutation Check: e.g. "button" -> "checkbox" or "button" -> "link"
	if v.EnforceRoleStrictness && fp.Role != "" && candidate.Role != "" && !strings.EqualFold(fp.Role, candidate.Role) {
		return false, fmt.Sprintf("SEMANTIC DRIFT DETECTED: Element ARIA role mutated from %q to %q. Self-healing aborted to prevent false-positive pass.", fp.Role, candidate.Role)
	}

	// 2. Action Intent Check: prevents dangerous same-role-different-action semantic swaps
	if v.EnforceIntentCheck && fp.ActionIntent != "" && candidate.ActionIntent != "" && !strings.EqualFold(fp.ActionIntent, candidate.ActionIntent) {
		return false, fmt.Sprintf("ACTION INTENT MISMATCH DETECTED: Target action intent is %q, but candidate has action intent %q. Self-healing aborted to prevent dangerous semantic swap.", fp.ActionIntent, candidate.ActionIntent)
	}

	// 3. Container / Ancestry Anchor Check: prevents cross-container semantic swaps
	if v.EnforceAncestryCheck && fp.ContainerID != "" && candidate.ContainerID != "" && !strings.EqualFold(fp.ContainerID, candidate.ContainerID) {
		return false, fmt.Sprintf("ANCESTRY / CONTAINER MISMATCH: Element moved from container %q to container %q. Self-healing aborted.", fp.ContainerID, candidate.ContainerID)
	}

	// 4. Severe Position Drift Check: if candidate moved > MaxPositionDriftPx without matching text
	if fp.BBox.Width > 0 && candidate.BoundingBox.Width > 0 {
		dist := math.Hypot(candidate.BoundingBox.X-fp.BBox.X, candidate.BoundingBox.Y-fp.BBox.Y)
		if dist > v.MaxPositionDriftPx && !strings.EqualFold(strings.TrimSpace(fp.Text), strings.TrimSpace(candidate.Text)) {
			return false, fmt.Sprintf("LAYOUT DRIFT DETECTED: Element shifted %.1fpx (exceeds %.1fpx threshold) with non-matching text. Aborting healing.", dist, v.MaxPositionDriftPx)
		}
	}

	return true, ""
}

// HealedSelectorArtifact is a mandatory auditable artifact emitted on any locator healing event.
type HealedSelectorArtifact struct {
	OriginalSelector string            `json:"original_selector"`
	NewSelector      string            `json:"new_selector"`
	Diff             string            `json:"diff"`
	ConfidenceScore  float64           `json:"confidence_score"`
	RiskLevel        RiskLevel         `json:"risk_level"`
	Timestamp        string            `json:"timestamp"`
	AuditFinding     string            `json:"audit_finding"`
	RequiresReview   bool              `json:"requires_review"`
	ResolvedTier     LocatorTier       `json:"resolved_tier"`
	FallbackChain    []FallbackAttempt `json:"fallback_chain"`
}

// HealedResolution records the outcome of a self-healing attempt.
type HealedResolution struct {
	Status                ResolutionStatus        `json:"status"` // EXACT_PASS vs HEALED_REQUIRES_REVIEW vs REGRESSION_FAIL
	OriginalFingerprint   ElementFingerprint      `json:"original_fingerprint"`
	ResolvedElement       *driver.Element         `json:"resolved_element"`
	ConfidenceScore       float64                 `json:"confidence_score"` // 0.0 to 1.0
	HealedSelector        string                  `json:"healed_selector"`
	Reason                string                  `json:"reason"`
	IsExactMatch          bool                    `json:"is_exact_match"`
	RegressionDetected    bool                    `json:"regression_detected"`
	ProposedSelectorPatch string                  `json:"proposed_selector_patch,omitempty"`
	AuditFinding          string                  `json:"audit_finding,omitempty"`
	RiskLevel             RiskLevel               `json:"risk_level,omitempty"`
	Artifact              *HealedSelectorArtifact `json:"artifact,omitempty"`
	ResolvedTier          LocatorTier             `json:"resolved_tier"`
	FallbackChain         []FallbackAttempt       `json:"fallback_chain"`
}

// BlocksPRGate determines if the resolution outcome blocks the CI PR quality gate.
func (r *HealedResolution) BlocksPRGate() (bool, string) {
	if r == nil {
		return false, ""
	}
	if r.Status == StatusRegressionFail {
		return true, fmt.Sprintf("PR gate failed: %s", r.Reason)
	}
	if r.ResolvedTier == TierBBox {
		return true, "PR gate failed: Bounding box fallback is strictly prohibited in CI release gates."
	}
	if r.Status == StatusHealed && r.ResolvedTier > TierTestID {
		return true, fmt.Sprintf("PR gate blocked: Locator healed using low-rank fallback %s (Tier %d below TierTestID). Regression review required.", r.HealedSelector, r.ResolvedTier)
	}
	return false, "PR gate satisfied"
}

// SelfHealingLocatorRegistry manages element fingerprints and executes resilient resolution.
type SelfHealingLocatorRegistry struct {
	fingerprints      map[string]ElementFingerprint
	defaultMode       HealingMode
	allowBBoxFallback bool // Disabled by default in accordance with SDET guidelines
	diffValidator     *SemanticDiffValidator
}

// NewSelfHealingLocatorRegistry constructs a new registry with strict default (fail hard, propose patch) and BBox disabled.
func NewSelfHealingLocatorRegistry() *SelfHealingLocatorRegistry {
	return &SelfHealingLocatorRegistry{
		fingerprints:      make(map[string]ElementFingerprint),
		defaultMode:       HealModeStrict,
		allowBBoxFallback: false, // Bounding box fallback is disabled by default to prevent false-positives
		diffValidator:     DefaultSemanticDiffValidator(),
	}
}

// SetSemanticDiffValidator overrides the diff validator.
func (r *SelfHealingLocatorRegistry) SetSemanticDiffValidator(v *SemanticDiffValidator) {
	r.diffValidator = v
}

// SetDefaultMode configures the default healing governance mode.
func (r *SelfHealingLocatorRegistry) SetDefaultMode(mode HealingMode) {
	r.defaultMode = mode
}

// SetAllowBBoxFallback toggles geometric bounding box fallback.
// CAUTION: Bounding Box is the least semantically meaningful locator and should remain disabled in CI.
func (r *SelfHealingLocatorRegistry) SetAllowBBoxFallback(allow bool) {
	r.allowBBoxFallback = allow
}

// RegisterFingerprint stores an element's multi-factor signature.
func (r *SelfHealingLocatorRegistry) RegisterFingerprint(fp ElementFingerprint) {
	r.fingerprints[fp.ID] = fp
}

// Resolve attempts exact match first, then falls back to multi-factor fuzzy heuristic.
func (r *SelfHealingLocatorRegistry) Resolve(fingerprintID string, currentElements []driver.Element) *HealedResolution {
	return r.ResolveWithMode(r.defaultMode, fingerprintID, currentElements)
}

// ResolveWithMode executes resolution enforcing the specified healing governance policy.
func (r *SelfHealingLocatorRegistry) ResolveWithMode(mode HealingMode, fingerprintID string, currentElements []driver.Element) *HealedResolution {
	fp, exists := r.fingerprints[fingerprintID]
	if !exists {
		return nil
	}

	fallbackChain := make([]FallbackAttempt, 0)

	// 1. Exact TestID match (1.0 confidence) -> Clean EXACT_PASS
	if fp.TestID != "" {
		testIDFound := false
		for i := range currentElements {
			el := &currentElements[i]
			if el.TestID == fp.TestID {
				testIDFound = true
				fallbackChain = append(fallbackChain, FallbackAttempt{
					Tier:     TierTestID,
					Strategy: "data-testid",
					Selector: fmt.Sprintf("[data-testid=%q]", el.TestID),
					Matched:  true,
				})
				return &HealedResolution{
					Status:              StatusExactPass,
					OriginalFingerprint: fp,
					ResolvedElement:     el,
					ConfidenceScore:     1.0,
					HealedSelector:      fmt.Sprintf("[data-testid=%q]", el.TestID),
					Reason:              "Exact match on data-testid",
					IsExactMatch:        true,
					ResolvedTier:        TierTestID,
					FallbackChain:       fallbackChain,
				}
			}
		}
		if !testIDFound {
			fallbackChain = append(fallbackChain, FallbackAttempt{
				Tier:         TierTestID,
				Strategy:     "data-testid",
				Selector:     fmt.Sprintf("[data-testid=%q]", fp.TestID),
				Matched:      false,
				FailureCause: "Element with specified data-testid not found in current DOM",
			})
		}
	}

	// 2. Exact ID match (0.95 confidence) -> Clean EXACT_PASS
	if fp.ID != "" {
		idFound := false
		for i := range currentElements {
			el := &currentElements[i]
			if el.ID == fp.ID {
				idFound = true
				fallbackChain = append(fallbackChain, FallbackAttempt{
					Tier:     TierExactID,
					Strategy: "element-id",
					Selector: "#" + el.ID,
					Matched:  true,
				})
				return &HealedResolution{
					Status:              StatusExactPass,
					OriginalFingerprint: fp,
					ResolvedElement:     el,
					ConfidenceScore:     0.95,
					HealedSelector:      "#" + el.ID,
					Reason:              "Exact match on element ID",
					IsExactMatch:        true,
					ResolvedTier:        TierExactID,
					FallbackChain:       fallbackChain,
				}
			}
		}
		if !idFound {
			fallbackChain = append(fallbackChain, FallbackAttempt{
				Tier:         TierExactID,
				Strategy:     "element-id",
				Selector:     "#" + fp.ID,
				Matched:      false,
				FailureCause: "Element with specified ID not found in current DOM",
			})
		}
	}

	// 3. Multi-factor heuristic scoring (Priority: RoleText -> TagText -> BoundingBox)
	var bestCandidate *driver.Element
	bestScore := 0.0
	bestReason := ""
	bestTier := TierTagText
	matchedViaBBoxOnly := false

	for i := range currentElements {
		el := &currentElements[i]
		score := 0.0
		var reasons []string
		hasSemanticMatch := false

		if el.Role != "" && el.Role == fp.Role {
			score += 0.35
			reasons = append(reasons, "matching role '"+el.Role+"'")
			hasSemanticMatch = true
		}
		if el.Tag != "" && el.Tag == fp.Tag {
			score += 0.20
			reasons = append(reasons, "matching tag '"+el.Tag+"'")
		}
		textMatch := el.Text != "" && strings.EqualFold(strings.TrimSpace(el.Text), strings.TrimSpace(fp.Text))
		if textMatch {
			score += 0.50
			reasons = append(reasons, "matching text '"+el.Text+"'")
			hasSemanticMatch = true
		}
		// Identity gate: role+tag alone match every button on the page, so a candidate that does not
		// share the original visible text can never be healed onto (it would click the wrong control).
		// ponytail: icon-only elements (empty text) are never healed, give them a data-testid.
		if !textMatch && score >= 0.50 {
			score = 0.49
		}

		// Geometric proximity bonus
		isBBoxClose := false
		if r.allowBBoxFallback {
			dist := math.Hypot(el.BoundingBox.X-fp.BBox.X, el.BoundingBox.Y-fp.BBox.Y)
			if dist < 80 && (el.BoundingBox.X > 0 || el.BoundingBox.Y > 0) {
				score += 0.15
				reasons = append(reasons, "matching visual coordinates")
				isBBoxClose = true
				if !hasSemanticMatch || (fp.Text != "" && el.Text != "" && !strings.EqualFold(strings.TrimSpace(fp.Text), strings.TrimSpace(el.Text))) {
					// Fallback relied on BBox to match an element whose text differs or lacks semantic match
					matchedViaBBoxOnly = true
				}
			}
		}

		tier := TierTagText
		if el.Role != "" && el.Role == fp.Role && el.Text != "" && strings.EqualFold(strings.TrimSpace(el.Text), strings.TrimSpace(fp.Text)) {
			tier = TierRoleText
		} else if isBBoxClose && matchedViaBBoxOnly {
			tier = TierBBox
		}

		if score > 1.0 {
			score = 1.0
		}

		if score > bestScore {
			bestScore = score
			bestCandidate = el
			bestReason = strings.Join(reasons, ", ")
			bestTier = tier
		}
	}

	// 4. Bounding Box Rule (Contract Blocker #2):
	// Bounding box fallback must ALWAYS fail the check, never heal!
	if matchedViaBBoxOnly || bestTier == TierBBox {
		fallbackChain = append(fallbackChain, FallbackAttempt{
			Tier:         TierBBox,
			Strategy:     "bounding-box",
			Selector:     "bbox-proximity",
			Matched:      true,
			FailureCause: "Bounding box fallback is strictly prohibited in CI release gates as it masks layout shifts and incorrect element targets.",
		})
		return &HealedResolution{
			Status:              StatusRegressionFail,
			OriginalFingerprint: fp,
			ConfidenceScore:     bestScore,
			Reason:              "CRITICAL REGRESSION: Bounding box fallback is strictly prohibited in CI release gates as it masks layout shifts and incorrect element targets.",
			RegressionDetected:  true,
			ResolvedTier:        TierBBox,
			FallbackChain:       fallbackChain,
		}
	}

	// 5. Candidate Evaluation and Semantic Diff Validation
	if bestCandidate != nil && bestScore >= 0.50 {
		selector := ""
		if bestCandidate.Role != "" && bestCandidate.Text != "" {
			selector = fmt.Sprintf("getByRole(%q, { name: %q })", bestCandidate.Role, bestCandidate.Text)
		} else if bestCandidate.Text != "" {
			selector = fmt.Sprintf("getByText(%q)", bestCandidate.Text)
		} else if bestCandidate.XPath != "" {
			selector = bestCandidate.XPath
		} else {
			selector = bestCandidate.Tag
		}

		// Semantic Diff Validation: fail loudly if ARIA role mutated or layout shifted severely
		if r.diffValidator != nil {
			if ok, diffReason := r.diffValidator.ValidateDiff(fp, bestCandidate); !ok {
				fallbackChain = append(fallbackChain, FallbackAttempt{
					Tier:         bestTier,
					Strategy:     "semantic-diff-validator",
					Selector:     selector,
					Matched:      false,
					FailureCause: diffReason,
				})
				return &HealedResolution{
					Status:              StatusRegressionFail,
					OriginalFingerprint: fp,
					ConfidenceScore:     bestScore,
					Reason:              diffReason,
					RegressionDetected:  true,
					ResolvedTier:        bestTier,
					FallbackChain:       fallbackChain,
				}
			}
		}

		fallbackChain = append(fallbackChain, FallbackAttempt{
			Tier:     bestTier,
			Strategy: bestReason,
			Selector: selector,
			Matched:  true,
		})

		// Compute risk level for the resolution
		risk := RiskMedium
		if bestScore >= 0.85 && bestCandidate.Role == fp.Role && strings.EqualFold(strings.TrimSpace(bestCandidate.Text), strings.TrimSpace(fp.Text)) {
			risk = RiskLow
		} else if bestCandidate.Role != fp.Role || bestScore < 0.70 {
			risk = RiskHigh
		}

		origSelector := fp.ID
		if origSelector == "" {
			origSelector = fp.TestID
		}
		if origSelector == "" {
			origSelector = fp.Tag
		}

		// In Strict CI Mode: Flag regression, do NOT click shifted element
		if mode == HealModeStrict {
			auditFinding := fmt.Sprintf("Regression prevented: Exact selector '%s' not found. Shifted candidate '%s' proposed for PR review.", fp.ID, selector)
			art := &HealedSelectorArtifact{
				OriginalSelector: origSelector,
				NewSelector:      selector,
				Diff:             fmt.Sprintf("- await page.locator(%q)\n+ await page.%s", origSelector, selector),
				ConfidenceScore:  bestScore,
				RiskLevel:        risk,
				Timestamp:        time.Now().UTC().Format(time.RFC3339),
				AuditFinding:     auditFinding,
				RequiresReview:   true,
				ResolvedTier:     bestTier,
				FallbackChain:    fallbackChain,
			}
			return &HealedResolution{
				Status:                StatusRegressionFail,
				OriginalFingerprint:   fp,
				ResolvedElement:       nil,
				ConfidenceScore:       bestScore,
				HealedSelector:        selector,
				Reason:                fmt.Sprintf("STRICT CI REGRESSION: Original selector '%s' missing. Potential match found via %s (confidence: %.2f)", fp.ID, bestReason, bestScore),
				IsExactMatch:          false,
				RegressionDetected:    true,
				ProposedSelectorPatch: art.Diff,
				AuditFinding:          auditFinding,
				RiskLevel:             risk,
				Artifact:              art,
				ResolvedTier:          bestTier,
				FallbackChain:         fallbackChain,
			}
		}

		// In Advisory or Permissive Mode: Explicitly mark as StatusHealed (HEALED_REQUIRES_REVIEW)
		isAdvisory := mode == HealModeAdvisory
		finding := ""
		if isAdvisory {
			finding = fmt.Sprintf("WARNING (HEALED): Locator '%s' dynamically healed to '%s'. Mandatory SDET review required before release.", fp.ID, selector)
		}
		art := &HealedSelectorArtifact{
			OriginalSelector: origSelector,
			NewSelector:      selector,
			Diff:             fmt.Sprintf("- await page.locator(%q)\n+ await page.%s", origSelector, selector),
			ConfidenceScore:  bestScore,
			RiskLevel:        risk,
			Timestamp:        time.Now().UTC().Format(time.RFC3339),
			AuditFinding:     finding,
			RequiresReview:   isAdvisory,
			ResolvedTier:     bestTier,
			FallbackChain:    fallbackChain,
		}

		return &HealedResolution{
			Status:                StatusHealed, // Never marked as clean EXACT_PASS!
			OriginalFingerprint:   fp,
			ResolvedElement:       bestCandidate,
			ConfidenceScore:       bestScore,
			HealedSelector:        selector,
			Reason:                "Self-healed via " + bestReason,
			IsExactMatch:          false,
			RegressionDetected:    isAdvisory,
			ProposedSelectorPatch: art.Diff,
			AuditFinding:          finding,
			RiskLevel:             risk,
			Artifact:              art,
			ResolvedTier:          bestTier,
			FallbackChain:         fallbackChain,
		}
	}

	if bestCandidate != nil {
		return &HealedResolution{
			Status:              StatusRegressionFail,
			OriginalFingerprint: fp,
			ConfidenceScore:     bestScore,
			Reason:              fmt.Sprintf("REGRESSION: locator %q is gone and no element shares its visible text; refusing to heal onto a different control", fp.ID),
			RegressionDetected:  true,
			ResolvedTier:        bestTier,
			FallbackChain:       fallbackChain,
		}
	}

	return nil
}

// FormatHealedSelectorArtifact serializes the healing audit artifact to JSON for CI/CD archiving.
func FormatHealedSelectorArtifact(artifact *HealedSelectorArtifact) (string, error) {
	if artifact == nil {
		return "", fmt.Errorf("artifact cannot be nil")
	}
	bytes, err := json.MarshalIndent(artifact, "", "  ")
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

// FormatHealedSelectorMarkdown formats the healed selector event into an executive PR review report.
func FormatHealedSelectorMarkdown(artifact *HealedSelectorArtifact) string {
	if artifact == nil {
		return ""
	}
	return fmt.Sprintf("### ⚠️ HEALED_SELECTOR Review Required\n"+
		"- **Risk Level**: `%s`\n"+
		"- **Confidence Score**: `%.2f`\n"+
		"- **Timestamp**: `%s`\n\n"+
		"```diff\n%s\n```\n\n"+
		"> **Audit Finding**: %s\n",
		artifact.RiskLevel,
		artifact.ConfidenceScore,
		artifact.Timestamp,
		artifact.Diff,
		artifact.AuditFinding,
	)
}
