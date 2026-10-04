package optimizer

import (
	"fmt"
	"strings"
)

// DeploymentMode models the target execution infrastructure.
type DeploymentMode string

const (
	DeploymentCloudAPI    DeploymentMode = "cloud_api"      // Commercial APIs (Claude 3.5 Sonnet / GPT-4o)
	DeploymentVLLMCloud   DeploymentMode = "vllm_cloud_gpu" // Dedicated AWS/GCP GPU cluster (g5.12xlarge / p4d.24xlarge)
	DeploymentLocalOllama DeploymentMode = "local_ollama"   // Developer laptop M-series / Workstations
)

// TCOParameters represents input dimensions for enterprise cost modeling.
type TCOParameters struct {
	Engineers           int            `json:"engineers"`
	PRsPerDay           int            `json:"prs_per_day"`
	TestsPerSuite       int            `json:"tests_per_suite"`
	Deployment          DeploymentMode `json:"deployment"`
	WorkingDaysPerMonth int            `json:"working_days_per_month"`
}

// HardwareProfile models inference throughput and developer execution latency.
type HardwareProfile struct {
	HardwareName       string  `json:"hardware_name"`
	TokensPerSecond    float64 `json:"tokens_per_second"`
	AvgRunDurationSec  float64 `json:"avg_run_duration_sec"` // Latency for full ingestion + SDET run
	ThermalThrottle    bool    `json:"thermal_throttle"`
	LatencyWarning     string  `json:"latency_warning,omitempty"`
}

// TCOAssumptions records measured multi-app benchmark inputs and infrastructure sizing.
type TCOAssumptions struct {
	MeasuredTokensLocalPerTest float64 `json:"measured_tokens_local_per_test"` // 6,050 tokens (Medusa + TodoMVC benchmark)
	MeasuredTokensAPIPerTest   float64 `json:"measured_tokens_api_per_test"`   // 5,550 tokens
	MeasuredTokensRawPerTest   float64 `json:"measured_tokens_raw_per_test"`   // 150,000 tokens (raw DOM baseline)
	MeasuredCIMinutesPerRun    float64 `json:"measured_ci_minutes_per_run"`    // 2.5 minutes (smoke suite on 4x runners)
	CostPerMillionAPITokens    float64 `json:"cost_per_million_api_tokens"`    // $5.00 / 1M tokens
	GPUNodesPer100Engineers    int     `json:"gpu_nodes_per_100_engineers"`    // 2x AWS g5.12xlarge (4x A10G 24GB)
	MonthlyGPUCostPer100Devs   float64 `json:"monthly_gpu_cost_per_100_devs"`  // $7,800 / month
	MonthlyMLOpsFTEPer100Devs  float64 `json:"monthly_mlops_fte_per_100_devs"` // $15,000 / month (1.0 FTE base + overhead)
}

// DefaultTCOAssumptions provides measured benchmark constants.
func DefaultTCOAssumptions() TCOAssumptions {
	return TCOAssumptions{
		MeasuredTokensLocalPerTest: 6050.0,
		MeasuredTokensAPIPerTest:   5550.0,
		MeasuredTokensRawPerTest:   150000.0,
		MeasuredCIMinutesPerRun:    2.5,
		CostPerMillionAPITokens:    5.0,
		GPUNodesPer100Engineers:    2,
		MonthlyGPUCostPer100Devs:   7800.0,
		MonthlyMLOpsFTEPer100Devs:  15000.0,
	}
}

// TCOResult breaks down direct and hidden total cost of ownership.
type TCOResult struct {
	MonthlyPRRuns             int               `json:"monthly_pr_runs"`
	MonthlyTokensRaw          float64           `json:"monthly_tokens_raw"`           // Without DOM compression
	MonthlyTokensOptimized    float64           `json:"monthly_tokens_optimized"`     // With Kritix semantic pruning
	MonthlyCloudAPIUnassisted float64           `json:"monthly_cloud_api_unassisted"` // Full raw LLM cost
	MonthlyCloudAPIOptimized  float64           `json:"monthly_cloud_api_optimized"`  // Kritix commercial API bill
	MonthlyGPUInfraCost       float64           `json:"monthly_gpu_infra_cost"`       // AWS A100/A10G cluster reservation
	MonthlyMLOpsFTEBurden     float64           `json:"monthly_mlops_fte_burden"`     // MLOps engineer salary
	TotalMonthlyCost          float64           `json:"total_monthly_cost"`
	AnnualizedTCO             float64           `json:"annualized_tco"`
	NetMonthlySavings         float64           `json:"net_monthly_savings"`
	TotalCIMinutesMonthly     float64           `json:"total_ci_minutes_monthly"`
	Assumptions               TCOAssumptions    `json:"assumptions"`
	HardwareComparison        []HardwareProfile `json:"hardware_comparison"`
	Recommendation            string            `json:"recommendation"`
}

// CalculateTCO computes honest enterprise cost modeling including infra and MLOps burdens.
func CalculateTCO(params TCOParameters) TCOResult {
	assumptions := DefaultTCOAssumptions()

	if params.WorkingDaysPerMonth <= 0 {
		params.WorkingDaysPerMonth = 22
	}
	if params.Engineers <= 0 {
		params.Engineers = 10
	}
	if params.PRsPerDay <= 0 {
		params.PRsPerDay = params.Engineers * 2 // Standard baseline: ~2 PRs per dev/day
	}
	if params.TestsPerSuite <= 0 {
		params.TestsPerSuite = 15
	}

	monthlyRuns := params.PRsPerDay * params.WorkingDaysPerMonth
	totalCIMinutes := float64(monthlyRuns) * assumptions.MeasuredCIMinutesPerRun

	// Measured token consumption based on real Medusa & TodoMVC benchmark data
	tokensPerRunRaw := float64(params.TestsPerSuite) * assumptions.MeasuredTokensRawPerTest
	tokensPerRunOpt := float64(params.TestsPerSuite) * assumptions.MeasuredTokensAPIPerTest

	totalTokensRaw := float64(monthlyRuns) * tokensPerRunRaw
	totalTokensOpt := float64(monthlyRuns) * tokensPerRunOpt

	// Commercial API blended cost
	apiCostRaw := (totalTokensRaw / 1000000.0) * assumptions.CostPerMillionAPITokens
	apiCostOpt := (totalTokensOpt / 1000000.0) * assumptions.CostPerMillionAPITokens

	gpuInfraCost := 0.0
	mlopsBurden := 0.0
	totalCost := 0.0
	recommendation := ""

	// Hardware execution profiling benchmarks
	hwProfiles := []HardwareProfile{
		{
			HardwareName:      "Dedicated Cloud A100 / H100 (vLLM)",
			TokensPerSecond:   65.0,
			AvgRunDurationSec: 45.0,
			ThermalThrottle:   false,
		},
		{
			HardwareName:      "Developer Laptop (MacBook M-Series / Apple Silicon 32B)",
			TokensPerSecond:   4.0,   // 3-5 tokens/sec for 32B models
			AvgRunDurationSec: 600.0, // 10 minutes (8-12m)
			ThermalThrottle:   true,
			LatencyWarning:    "⚠️ CI KILLER: Local 32B model runs at 3-5 tok/s taking 8-12 min per run; thermal throttling degrades throughput after 1 hour.",
		},
	}

	// Scaling proportional to team size (normalized per 100 engineers)
	scaleFactor := float64(params.Engineers) / 100.0
	if scaleFactor < 0.25 {
		scaleFactor = 0.25 // Minimum 1 GPU slice for smaller teams
	}

	switch params.Deployment {
	case DeploymentVLLMCloud:
		// Dedicated AWS/GCP GPU Cluster hosting for Qwen2.5-Coder-32B
		// Per 100 engineers: 2x AWS g5.12xlarge nodes ($7,800/mo) + 1.0 FTE MLOps Engineer ($15,000/mo)
		gpuInfraCost = assumptions.MonthlyGPUCostPer100Devs * scaleFactor
		mlopsBurden = assumptions.MonthlyMLOpsFTEPer100Devs * scaleFactor
		totalCost = gpuInfraCost + mlopsBurden
		recommendation = fmt.Sprintf("Dedicated Cloud vLLM hosting for %d engineers requires $%.0f/mo infra + $%.0f/mo MLOps FTE ($%.0f/yr). Viable for strict air-gapped compliance.",
			params.Engineers, gpuInfraCost, mlopsBurden, totalCost*12.0)

	case DeploymentLocalOllama:
		gpuInfraCost = 0.0
		mlopsBurden = 2500.0 * scaleFactor // Config sync, quantization packaging, dev troubleshooting
		totalCost = mlopsBurden
		recommendation = "Local Ollama is ideal for offline dev exploration only. 8-12 min run latency makes it infeasible for automated CI PR gates."

	default: // DeploymentCloudAPI
		totalCost = apiCostOpt
		gpuInfraCost = 0.0
		mlopsBurden = 0.0
		recommendation = "RECOMMENDED: Commercial Cloud API paired with Kritix Go deterministic pruning yields lowest TCO with zero GPU infra or MLOps headcount."
	}

	netSavings := apiCostRaw - totalCost
	annualizedTCO := totalCost * 12.0

	return TCOResult{
		MonthlyPRRuns:             monthlyRuns,
		MonthlyTokensRaw:          totalTokensRaw,
		MonthlyTokensOptimized:    totalTokensOpt,
		MonthlyCloudAPIUnassisted: apiCostRaw,
		MonthlyCloudAPIOptimized:  apiCostOpt,
		MonthlyGPUInfraCost:       gpuInfraCost,
		MonthlyMLOpsFTEBurden:     mlopsBurden,
		TotalMonthlyCost:          totalCost,
		AnnualizedTCO:             annualizedTCO,
		NetMonthlySavings:         netSavings,
		TotalCIMinutesMonthly:     totalCIMinutes,
		Assumptions:               assumptions,
		HardwareComparison:        hwProfiles,
		Recommendation:            recommendation,
	}
}

// FormatBreakdown renders a clear comparison table.
func (r *TCOResult) FormatBreakdown() string {
	var sb strings.Builder
	sb.WriteString("==========================================================================\n")
	sb.WriteString("               KRITIX AI ENTERPRISE TCO & ROI COST MODEL                  \n")
	sb.WriteString("==========================================================================\n")
	sb.WriteString(fmt.Sprintf("Monthly PR Pipeline Runs:      %d runs/month\n", r.MonthlyPRRuns))
	sb.WriteString(fmt.Sprintf("Estimated CI Pipeline Minutes: %.0f minutes/month (%.1f min/run avg)\n", r.TotalCIMinutesMonthly, r.Assumptions.MeasuredCIMinutesPerRun))
	sb.WriteString(fmt.Sprintf("Raw Unassisted Token Volume:   %.1fM tokens/month (150k tok/test)\n", r.MonthlyTokensRaw/1000000.0))
	sb.WriteString(fmt.Sprintf("Kritix Pruned Token Volume:    %.1fM tokens/month (5.5k tok/test, -96.3%% reduction)\n", r.MonthlyTokensOptimized/1000000.0))
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString(fmt.Sprintf("Unassisted Cloud API Cost:     $%10.2f / month (Baseline)\n", r.MonthlyCloudAPIUnassisted))
	sb.WriteString(fmt.Sprintf("Kritix Cloud API Cost:         $%10.2f / month\n", r.MonthlyCloudAPIOptimized))
	if r.MonthlyGPUInfraCost > 0 {
		sb.WriteString(fmt.Sprintf("Dedicated Cloud GPU Infra:     $%10.2f / month (AWS A100/A10G Cluster)\n", r.MonthlyGPUInfraCost))
		sb.WriteString(fmt.Sprintf("MLOps Engineering Headcount:   $%10.2f / month (vLLM / Model Ops FTE)\n", r.MonthlyMLOpsFTEBurden))
	}
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString(fmt.Sprintf("Total Evaluated Monthly Cost:  $%10.2f / month\n", r.TotalMonthlyCost))
	sb.WriteString(fmt.Sprintf("Annualized Projected TCO:      $%10.2f / year\n", r.AnnualizedTCO))
	sb.WriteString(fmt.Sprintf("Net Monthly Dollar Savings:    $%10.2f / month (%.1f%% ROI)\n",
		r.NetMonthlySavings, (r.NetMonthlySavings/r.MonthlyCloudAPIUnassisted)*100.0))
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString("📋 MEASURED BENCHMARK ASSUMPTIONS (Medusa Storefront + TodoMVC):\n")
	sb.WriteString(fmt.Sprintf("- Tokens/Test (Local Qwen-32B): %.0f tokens | Tokens/Test (API): %.0f tokens\n",
		r.Assumptions.MeasuredTokensLocalPerTest, r.Assumptions.MeasuredTokensAPIPerTest))
	sb.WriteString(fmt.Sprintf("- Local Model Hosting (100 Devs): 2x g5.12xlarge ($%.0f/mo) + 1 FTE MLOps ($%.0f/mo) = $%.0f/mo\n",
		r.Assumptions.MonthlyGPUCostPer100Devs, r.Assumptions.MonthlyMLOpsFTEPer100Devs,
		r.Assumptions.MonthlyGPUCostPer100Devs+r.Assumptions.MonthlyMLOpsFTEPer100Devs))
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString("⚡ HARDWARE RUNTIME BENCHMARK COMPARISON:\n")
	for _, hw := range r.HardwareComparison {
		sb.WriteString(fmt.Sprintf("- %s: %.0f tok/s | Avg Run: %.0fs\n", hw.HardwareName, hw.TokensPerSecond, hw.AvgRunDurationSec))
		if hw.LatencyWarning != "" {
			sb.WriteString(fmt.Sprintf("  %s\n", hw.LatencyWarning))
		}
	}
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString(fmt.Sprintf("Strategic Recommendation:\n%s\n", r.Recommendation))
	sb.WriteString("==========================================================================\n")
	return sb.String()
}
