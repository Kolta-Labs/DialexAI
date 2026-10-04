package optimizer

import (
	"strings"
	"testing"
)

func TestCalculateTCO(t *testing.T) {
	params := TCOParameters{
		Engineers:           50,
		PRsPerDay:           100,
		TestsPerSuite:       15,
		Deployment:          DeploymentCloudAPI,
		WorkingDaysPerMonth: 22,
	}

	result := CalculateTCO(params)

	if result.MonthlyPRRuns != 2200 {
		t.Errorf("expected 2200 monthly runs, got %d", result.MonthlyPRRuns)
	}

	if result.NetMonthlySavings <= 0 {
		t.Errorf("expected positive savings from 90%% token pruning")
	}

	if result.MonthlyCloudAPIOptimized >= result.MonthlyCloudAPIUnassisted {
		t.Errorf("optimized cost must be lower than unassisted cost")
	}

	breakdown := result.FormatBreakdown()
	if !strings.Contains(breakdown, "KRITIX AI ENTERPRISE TCO") {
		t.Errorf("missing header in breakdown")
	}

	// Test GPU mode includes infra cost
	paramsGPU := params
	paramsGPU.Deployment = DeploymentVLLMCloud
	resGPU := CalculateTCO(paramsGPU)
	if resGPU.MonthlyGPUInfraCost <= 0 || resGPU.MonthlyMLOpsFTEBurden <= 0 {
		t.Errorf("expected GPU infra and MLOps burden in vLLM cloud deployment")
	}
	if resGPU.AnnualizedTCO != resGPU.TotalMonthlyCost*12.0 {
		t.Errorf("expected annualized TCO to be 12x monthly cost")
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
