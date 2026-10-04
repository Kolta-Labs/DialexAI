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

	if result.NetMonthlySavings <= 0 {
		t.Errorf("expected positive savings from token pruning")
	}

	if result.MonthlyCloudAPIOptimized >= result.MonthlyCloudAPIUnassisted {
		t.Errorf("optimized cost must be lower than unassisted cost")
	}

	// Verify measured assumptions are populated
	if result.Assumptions.MeasuredTokensAPIPerTest <= 0 || result.Assumptions.MeasuredCIMinutesPerRun <= 0 {
		t.Errorf("expected populated measured assumptions")
	}

	breakdown := result.FormatBreakdown()
	if !strings.Contains(breakdown, "MEASURED BENCHMARK ASSUMPTIONS") {
		t.Errorf("missing assumptions section in breakdown: %s", breakdown)
	}

	// Test GPU mode for 100 engineers
	paramsGPU := params
	paramsGPU.Deployment = DeploymentVLLMCloud
	resGPU := CalculateTCO(paramsGPU)

	// Sizing for 100 engineers: 2 nodes ($7,800) + 1 MLOps FTE ($15,000) = $22,800/mo
	if resGPU.MonthlyGPUInfraCost != 7800.0 {
		t.Errorf("expected $7,800 monthly GPU infra cost for 100 engineers, got $%.2f", resGPU.MonthlyGPUInfraCost)
	}
	if resGPU.MonthlyMLOpsFTEBurden != 15000.0 {
		t.Errorf("expected $15,000 monthly MLOps FTE burden for 100 engineers, got $%.2f", resGPU.MonthlyMLOpsFTEBurden)
	}
	if resGPU.TotalMonthlyCost != 22800.0 {
		t.Errorf("expected $22,800 total monthly cost for 100 engineers, got $%.2f", resGPU.TotalMonthlyCost)
	}
	if resGPU.AnnualizedTCO != 22800.0*12.0 {
		t.Errorf("expected $273,600 annualized TCO for 100 engineers, got $%.2f", resGPU.AnnualizedTCO)
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
