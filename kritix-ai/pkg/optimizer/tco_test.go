package optimizer

import (
	"strings"
	"testing"
)

func TestCalculateTCO(t *testing.T) {
	params := TCOParameters{
		Engineers:           100,
		PRsPerDay:           200,
		TestsPerSuite:       15,
		Deployment:          DeploymentCloudAPI,
		WorkingDaysPerMonth: 22,
	}

	result := CalculateTCO(params)

	if result.MonthlyPRRuns != 4400 {
		t.Errorf("expected 4400 monthly runs for 100 engineers, got %d", result.MonthlyPRRuns)
	}

	// In corpus mode with unmeasured production latency, headline must be blocked
	if !result.IsHeadlineBlocked {
		t.Errorf("expected headline ROI to be blocked when multi-app latency is not measured")
	}

	breakdown := result.FormatBreakdown()
	if !strings.Contains(breakdown, "HEADLINE ROI: [BLOCKED") {
		t.Errorf("expected breakdown to show headline blocked warning: %s", breakdown)
	}

	if !strings.Contains(breakdown, "DEDICATED GPU HOSTING (AWS A10G") {
		t.Errorf("missing dedicated GPU hosting breakdown: %s", breakdown)
	}

	// 100 Engineers: 2 nodes * $5.672 * 730h * 0.70 util = $5,797/mo infra + $15,000 MLOps = $20,797/mo
	if result.GPU100MonthlyInfra <= 5000 || result.GPU100MonthlyInfra >= 6500 {
		t.Errorf("unexpected 100-engineer GPU infra cost: $%.2f", result.GPU100MonthlyInfra)
	}
	if result.GPU100MLOpsBurden != 15000.0 {
		t.Errorf("expected $15,000 MLOps burden for 100 engineers, got $%.2f", result.GPU100MLOpsBurden)
	}

	// 500 Engineers: 8 nodes * $5.672 * 730h * 0.70 util = $23,187/mo infra + $30,000 MLOps = $53,187/mo
	if result.GPU500MonthlyInfra <= 20000 || result.GPU500MonthlyInfra >= 25000 {
		t.Errorf("unexpected 500-engineer GPU infra cost: $%.2f", result.GPU500MonthlyInfra)
	}
	if result.GPU500MLOpsBurden != 30000.0 {
		t.Errorf("expected $30,000 MLOps burden for 500 engineers, got $%.2f", result.GPU500MLOpsBurden)
	}

	if len(result.HardwareComparison) != 2 {
		t.Fatalf("expected 2 hardware profiles, got %d", len(result.HardwareComparison))
	}

	// Verify MacBook M-Series latency warning
	macProfile := result.HardwareComparison[1]
	if !macProfile.ThermalThrottle || !strings.Contains(macProfile.LatencyWarning, "CI KILLER") {
		t.Errorf("expected thermal throttle warning for local developer laptop: %+v", macProfile)
	}
}
