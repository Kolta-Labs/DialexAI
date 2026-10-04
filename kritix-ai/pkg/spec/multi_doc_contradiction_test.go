package spec

import (
	"testing"
)

func TestReconcileAndAudit_ContradictionBenchmark(t *testing.T) {
	// ≥10 Deliberately Contradictory & Incomplete Real-World Specification Bundles
	testCases := []struct {
		name                 string
		bundle               MultiArtifactBundle
		expectedField        string
		expectedSev          ConflictSeverity
		expectedHalted       bool
		expectedMaxConfidence float64
	}{
		{
			name: "Case 1: Navigation Redirect vs Modal Dialog",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-101",
				FeatureDesignDoc: "Checkout completes and browser redirects to /order/confirmation summary page",
				JiraDescription:  "User clicks submit payment and renders in-page modal dialog for confirmation",
			},
			expectedField:        "NavigationFlow",
			expectedSev:          ConflictSevCritical,
			expectedHalted:       true,
			expectedMaxConfidence: 0.65,
		},
		{
			name: "Case 2: Action Button Copy Matrix vs Jira AC",
			bundle: MultiArtifactBundle{
				TicketID:        "PROJ-102",
				JiraDescription: "User clicks submit order button to finalize purchase",
				CopyMatrix: map[string]string{
					"cta_checkout_btn": "Confirm & Pay Now",
				},
			},
			expectedField:        "cta_checkout_btn",
			expectedSev:          ConflictSevHigh,
			expectedHalted:       true,
			expectedMaxConfidence: 0.75,
		},
		{
			name: "Case 3: Session Inactivity Timeout Contradiction (15m vs 60m)",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-103",
				FeatureDesignDoc: "Security policy requires strict 15 min session inactivity timeout",
				JiraDescription:  "Customer stays logged in for 60 min session duration",
			},
			expectedField:        "SessionTimeout",
			expectedSev:          ConflictSevHigh,
			expectedHalted:       true,
			expectedMaxConfidence: 0.70,
		},
		{
			name: "Case 4: Password Minimum Length Policy Contradiction (8 vs 12)",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-104",
				FeatureDesignDoc: "Auth security standard enforces minimum 12 characters with special symbols",
				JiraDescription:  "User registration form validates minimum 8 characters",
			},
			expectedField:        "PasswordPolicy",
			expectedSev:          ConflictSevHigh,
			expectedHalted:       true,
			expectedMaxConfidence: 0.70,
		},
		{
			name: "Case 5: Regional Settlement Currency Contradiction (EUR vs USD)",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-105",
				FeatureDesignDoc: "Regional checkout settlement currency: EUR for European storefront",
				JiraDescription:  "Billing cart calculates charges in currency: USD",
			},
			expectedField:        "CurrencySettlement",
			expectedSev:          ConflictSevCritical,
			expectedHalted:       true,
			expectedMaxConfidence: 0.60,
		},
		{
			name: "Case 6: 3D Secure / SCA Payment Verification Contradiction",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-106",
				FeatureDesignDoc: "Payment processing mandates 3DS mandatory challenge flow",
				JiraDescription:  "Payment gateway performs frictionless instant charge without customer challenge",
			},
			expectedField:        "PaymentAuthWorkflow",
			expectedSev:          ConflictSevCritical,
			expectedHalted:       true,
			expectedMaxConfidence: 0.60,
		},
		{
			name: "Case 7: Incomplete Tracking Spec (FDD mandates tracking without schemas)",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-107",
				FeatureDesignDoc: "Telemetry tracking required for conversion funnel drop-off analytics and page tracking",
				AnalyticsEvents:  []AnalyticsEventDef{}, // Empty schema
			},
			expectedField:        "AnalyticsTrackingSpec",
			expectedSev:          ConflictSevMedium,
			expectedHalted:       true,
			expectedMaxConfidence: 0.80,
		},
		{
			name: "Case 8: Multi-Conflict Combined (Modal vs Redirect + Button Copy)",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-108",
				FeatureDesignDoc: "After checkout, system redirects to success portal",
				JiraDescription:  "User clicks Pay button and displays success modal dialog",
				CopyMatrix: map[string]string{
					"submit_button": "Complete Order",
				},
			},
			expectedField:        "NavigationFlow",
			expectedSev:          ConflictSevCritical,
			expectedHalted:       true,
			expectedMaxConfidence: 0.40,
		},
		{
			name: "Case 9: Password Policy + Session Timeout Double Contradiction",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-109",
				FeatureDesignDoc: "Enforce minimum 12 characters and 15 min inactivity timeout",
				JiraDescription:  "Allow minimum 8 characters and 60 min session timeout",
			},
			expectedField:        "PasswordPolicy",
			expectedSev:          ConflictSevHigh,
			expectedHalted:       true,
			expectedMaxConfidence: 0.40,
		},
		{
			name: "Case 10: 3DS Challenge + Currency Contradiction",
			bundle: MultiArtifactBundle{
				TicketID:         "PROJ-110",
				FeatureDesignDoc: "Currency: EUR and 3DS mandatory for SCA compliance",
				JiraDescription:  "Currency: USD and frictionless instant charge bypass",
			},
			expectedField:        "CurrencySettlement",
			expectedSev:          ConflictSevCritical,
			expectedHalted:       true,
			expectedMaxConfidence: 0.20,
		},
	}

	detectedCount := 0
	totalCount := len(testCases)

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			report, err := tc.bundle.ReconcileAndAudit(0.85)
			if err != nil {
				t.Fatalf("unexpected error during reconciliation: %v", err)
			}

			if !report.HaltedForHumanResolution {
				t.Errorf("expected reconciliation to HALT for %s, but it passed", tc.name)
			} else {
				detectedCount++
			}

			if report.CalculatedConfidence > tc.expectedMaxConfidence {
				t.Errorf("expected confidence <= %.2f, got %.2f", tc.expectedMaxConfidence, report.CalculatedConfidence)
			}

			foundExpectedField := false
			for _, c := range report.Conflicts {
				if c.Field == tc.expectedField {
					foundExpectedField = true
					if c.Severity != tc.expectedSev {
						t.Errorf("expected severity %s for field %s, got %s", tc.expectedSev, c.Field, c.Severity)
					}
					break
				}
			}
			if !foundExpectedField {
				t.Errorf("expected conflict report to contain field %q, conflicts: %+v", tc.expectedField, report.Conflicts)
			}
		})
	}

	missRate := float64(totalCount-detectedCount) / float64(totalCount)
	if missRate > 0.0 {
		t.Fatalf("contradiction benchmark failed: detected %d/%d (miss rate: %.2f%%, required: 0.0%%)",
			detectedCount, totalCount, missRate*100)
	}

	t.Logf("Reconciliation Contradiction Benchmark: %d/%d detected, miss rate = 0.0%% (low confidence correctly blocked all runs)",
		detectedCount, totalCount)
}
