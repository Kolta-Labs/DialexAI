package optimizer

import (
	"fmt"
	"strings"
)

// DeploymentMode models the target execution infrastructure.
type DeploymentMode string

const (
	DeploymentCloudAPI    DeploymentMode = "cloud_api"     // Commercial APIs (OpenAI/Anthropic/Gemini)
	DeploymentVLLMCloud   DeploymentMode = "vllm_cloud_gpu"// AWS/GCP GPU cluster (2-4x A10G / A100)
	DeploymentLocalOllama DeploymentMode = "local_ollama"  // Developer laptop M-series / Workstations
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

// TCOResult breaks down direct and hidden total cost of ownership.
type TCOResult struct {
	MonthlyPRRuns             int               `json:"monthly_pr_runs"`
	MonthlyTokensRaw          float64           `json:"monthly_tokens_raw"`           // Without DOM compression
	MonthlyTokensOptimized    float64           `json:"monthly_tokens_optimized"`     // With Kritix 90% compression
	MonthlyCloudAPIUnassisted float64           `json:"monthly_cloud_api_unassisted"` // Full raw LLM cost
	MonthlyCloudAPIOptimized  float64           `json:"monthly_cloud_api_optimized"`  // Kritix commercial API bill
	MonthlyGPUInfraCost       float64           `json:"monthly_gpu_infra_cost"`       // AWS A100/H100 or g5.12xlarge reservation
	MonthlyMLOpsFTEBurden     float64           `json:"monthly_mlops_fte_burden"`     // MLOps engineer salary
	TotalMonthlyCost          float64           `json:"total_monthly_cost"`
	AnnualizedTCO             float64           `json:"annualized_tco"`
	NetMonthlySavings         float64           `json:"net_monthly_savings"`
	HardwareComparison        []HardwareProfile `json:"hardware_comparison"`
	Recommendation            string            `json:"recommendation"`
}

// CalculateTCO computes honest enterprise cost modeling including infra and MLOps burdens.
func CalculateTCO(params TCOParameters) TCOResult {
	if params.WorkingDaysPerMonth <= 0 {
		params.WorkingDaysPerMonth = 22
	}
	if params.Engineers <= 0 {
		params.Engineers = 10
	}
	if params.PRsPerDay <= 0 {
		params.PRsPerDay = params.Engineers * 2 // Standard industry baseline: ~2 PRs per dev/day
	}
	if params.TestsPerSuite <= 0 {
		params.TestsPerSuite = 15
	}

	monthlyRuns := params.PRsPerDay * params.WorkingDaysPerMonth

	// Tokens per test run:
	// Unoptimized: Full HTML DOM (120k tokens) + network traces + prompts = ~150,000 tokens/run
	// Optimized: Semantic interactive AXTree (6k tokens) + state diffs = ~15,000 tokens/run (90% reduction)
	tokensPerRunRaw := float64(params.TestsPerSuite) * 150000.0
	tokensPerRunOpt := float64(params.TestsPerSuite) * 15000.0

	totalTokensRaw := float64(monthlyRuns) * tokensPerRunRaw
	totalTokensOpt := float64(monthlyRuns) * tokensPerRunOpt

	// Commercial API blended cost (GPT-4o / Claude 3.5 Sonnet: ~$5 per 1M tokens)
	costPerMillion := 5.0
	apiCostRaw := (totalTokensRaw / 1000000.0) * costPerMillion
	apiCostOpt := (totalTokensOpt / 1000000.0) * costPerMillion

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

	switch params.Deployment {
	case DeploymentVLLMCloud:
		// Sizing: For teams of 50 SDETs running concurrent crawls (8-16 GPU-hrs/day):
		// 1 dedicated AWS g5.12xlarge / p4d.24xlarge node costs $2-$4/hr = $5,800 - $23,200/mo.
		// Baseline 1-cluster sizing with failover replica & EBS storage: ~$6,500/month.
		gpuInfraCost = 6500.0
		if params.Engineers > 50 {
			gpuInfraCost += float64((params.Engineers-50)/25) * 2800.0
		}
		// Minimum 0.5 to 1.0 FTE MLOps Engineer burden ($180k/yr base + overhead) = $7,500-$15,000/month
		// for vLLM scaling, quantization tuning, and biannual foundation model upgrades.
		mlopsBurden = 7500.0
		if params.Engineers >= 100 {
			mlopsBurden = 15000.0 // 1 full dedicated MLOps FTE
		}
		totalCost = gpuInfraCost + mlopsBurden
		recommendation = "Dedicated Cloud vLLM is viable only for strict air-gapped compliance; annual infra+MLOps budget is $168K-$258K/year."

	case DeploymentLocalOllama:
		gpuInfraCost = 0.0
		mlopsBurden = 2500.0 // Local config sync, quantization packaging, and developer troubleshooting
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
	sb.WriteString(fmt.Sprintf("Raw Unassisted Token Volume:   %.1fM tokens/month\n", r.MonthlyTokensRaw/1000000.0))
	sb.WriteString(fmt.Sprintf("Kritix Pruned Token Volume:    %.1fM tokens/month (-90%% reduction)\n", r.MonthlyTokensOptimized/1000000.0))
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString(fmt.Sprintf("Unassisted Cloud API Cost:     $%10.2f / month (Baseline)\n", r.MonthlyCloudAPIUnassisted))
	sb.WriteString(fmt.Sprintf("Kritix Cloud API Cost:         $%10.2f / month\n", r.MonthlyCloudAPIOptimized))
	if r.MonthlyGPUInfraCost > 0 {
		sb.WriteString(fmt.Sprintf("Dedicated Cloud GPU Infra:     $%10.2f / month (AWS A100/A10G Cluster)\n", r.MonthlyGPUInfraCost))
		sb.WriteString(fmt.Sprintf("MLOps Engineering Headcount:   $%10.2f / month (vLLM / Model Ops)\n", r.MonthlyMLOpsFTEBurden))
	}
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString(fmt.Sprintf("Total Evaluated Monthly Cost:  $%10.2f / month\n", r.TotalMonthlyCost))
	sb.WriteString(fmt.Sprintf("Annualized Projected TCO:      $%10.2f / year\n", r.AnnualizedTCO))
	sb.WriteString(fmt.Sprintf("Net Monthly Dollar Savings:    $%10.2f / month (%.1f%% ROI)\n",
		r.NetMonthlySavings, (r.NetMonthlySavings/r.MonthlyCloudAPIUnassisted)*100.0))
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
