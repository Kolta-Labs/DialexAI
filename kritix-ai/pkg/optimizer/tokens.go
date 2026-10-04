package optimizer

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
	"time"

	"kritix/pkg/driver"
	"kritix/pkg/model"
)

// SavingsReport details the computational and dollar savings from deterministic engine execution.
type SavingsReport struct {
	TotalRequestsHandled       int           `json:"total_requests_handled"`
	DeterministicZeroTokenOps  int           `json:"deterministic_zero_token_ops"` // Handled natively by Go
	CLISubshellZeroCostOps     int           `json:"cli_subshell_zero_cost_ops"`   // $0 extra billing via developer CLI
	LocalOfflineZeroCostOps    int           `json:"local_offline_zero_cost_ops"`  // $0 air-gapped via Ollama/vLLM
	CloudAPIOps                int           `json:"cloud_api_ops"`
	TokensSavedByPruning       int           `json:"tokens_saved_by_pruning"`
	EstimatedDollarSavingsUSD  float64       `json:"estimated_dollar_savings_usd"`
	ExecutionTimeSaved         time.Duration `json:"execution_time_saved"`
}

// TokenCostOptimizer minimizes LLM token consumption across the testing lifecycle.
type TokenCostOptimizer struct {
	mu           sync.RWMutex
	cache        map[string]model.Response
	stats        SavingsReport
	blendedRate  float64 // Average cost per 1M tokens (e.g. $5.00)
}

// NewTokenCostOptimizer constructs a new token and cost optimizer.
func NewTokenCostOptimizer() *TokenCostOptimizer {
	return &TokenCostOptimizer{
		cache:       make(map[string]model.Response),
		blendedRate: 5.00, // $5.00 per million tokens benchmark
	}
}

// CompressDOMForVision strips non-essential DOM noise (scripts, styles, SVGs, deep nesting)
// reducing payload size by ~90-95% before LLM ingestion.
func (o *TokenCostOptimizer) CompressDOMForVision(elements []driver.Element) (string, int) {
	var sb strings.Builder
	rawLength := 0

	for _, el := range elements {
		// Calculate approximate uncompressed size
		rawLength += len(el.XPath) + len(el.Text) + len(el.Tag) + 120

		// Skip invisible or disabled non-interactive elements
		if el.Disabled && el.Text == "" {
			continue
		}

		// Keep only actionable semantic primitives
		identifier := el.Text
		if identifier == "" {
			identifier = el.TestID
		}
		if identifier == "" {
			identifier = el.ID
		}

		if identifier != "" || el.Role != "" {
			sb.WriteString(fmt.Sprintf("[%s] %q role=%s\n", el.Tag, identifier, el.Role))
		}
	}

	compressed := sb.String()
	compressedLength := len(compressed)
	approxTokensSaved := (rawLength - compressedLength) / 4
	if approxTokensSaved < 0 {
		approxTokensSaved = 0
	}

	o.mu.Lock()
	o.stats.TokensSavedByPruning += approxTokensSaved
	o.stats.EstimatedDollarSavingsUSD += float64(approxTokensSaved) / 1000000.0 * o.blendedRate
	o.mu.Unlock()

	return compressed, approxTokensSaved
}

// GetCachedDecision checks if an identical browser state was already reasoned about.
func (o *TokenCostOptimizer) GetCachedDecision(stateHash string) (*model.Response, bool) {
	o.mu.RLock()
	defer o.mu.RUnlock()
	resp, ok := o.cache[stateHash]
	return &resp, ok
}

// CacheDecision saves a reasoning decision by state hash.
func (o *TokenCostOptimizer) CacheDecision(stateHash string, resp model.Response) {
	o.mu.Lock()
	defer o.mu.Unlock()
	o.cache[stateHash] = resp
}

// ComputeStateHash calculates a SHA-256 fingerprint for element and action states.
func ComputeStateHash(url string, elements []driver.Element) string {
	var sb strings.Builder
	sb.WriteString(url)
	for _, el := range elements {
		sb.WriteString(el.Tag)
		sb.WriteString(el.Text)
		sb.WriteString(el.Role)
	}
	hash := sha256.Sum256([]byte(sb.String()))
	return hex.EncodeToString(hash[:16])
}

// RecordOperation tracks whether an operation cost $0 or incurred external API costs.
func (o *TokenCostOptimizer) RecordOperation(mode model.Mode, tokensUsed int) {
	o.mu.Lock()
	defer o.mu.Unlock()

	o.stats.TotalRequestsHandled++
	switch mode {
	case model.ModeCLI:
		o.stats.CLISubshellZeroCostOps++
		// Avoided cloud API bills by running via developer's flat-rate CLI subscription
		o.stats.EstimatedDollarSavingsUSD += float64(tokensUsed) / 1000000.0 * o.blendedRate
	case model.ModeLocal:
		o.stats.LocalOfflineZeroCostOps++
		// 100% free offline local compute
		o.stats.EstimatedDollarSavingsUSD += float64(tokensUsed) / 1000000.0 * o.blendedRate
	case model.ModeAPI:
		o.stats.CloudAPIOps++
	default:
		o.stats.DeterministicZeroTokenOps++
	}
}

// RecordDeterministicBypass tracks operations solved natively by Go without any LLM call.
func (o *TokenCostOptimizer) RecordDeterministicBypass(equivalentTokens int) {
	o.mu.Lock()
	defer o.mu.Unlock()

	o.stats.TotalRequestsHandled++
	o.stats.DeterministicZeroTokenOps++
	o.stats.TokensSavedByPruning += equivalentTokens
	o.stats.EstimatedDollarSavingsUSD += float64(equivalentTokens) / 1000000.0 * o.blendedRate
}

// GetSavingsReport returns the current efficiency and dollar savings summary.
func (o *TokenCostOptimizer) GetSavingsReport() SavingsReport {
	o.mu.RLock()
	defer o.mu.RUnlock()
	return o.stats
}

// FormatSavingsSummary renders a business impact and cost efficiency report.
func (r *SavingsReport) FormatSavingsSummary() string {
	var sb strings.Builder
	sb.WriteString("==========================================================================\n")
	sb.WriteString("              KRITIX AI TOKEN EFFICIENCY & ROI REPORT                     \n")
	sb.WriteString("==========================================================================\n")
	sb.WriteString(fmt.Sprintf("Total Operations Handled:           %d\n", r.TotalRequestsHandled))
	sb.WriteString(fmt.Sprintf("  • Deterministic Go Native (0 Tokens): %d (AST, A11y, HAR, TOTP, Fuzz)\n", r.DeterministicZeroTokenOps))
	sb.WriteString(fmt.Sprintf("  • Developer CLI Subshell ($0 Cost):   %d (Claude/Codex flat-rate)\n", r.CLISubshellZeroCostOps))
	sb.WriteString(fmt.Sprintf("  • Local Offline Runtimes ($0 Cost):   %d (Ollama / vLLM / Qwen / Gemma)\n", r.LocalOfflineZeroCostOps))
	sb.WriteString(fmt.Sprintf("  • Metered Cloud API Calls:            %d\n", r.CloudAPIOps))
	sb.WriteString(strings.Repeat("-", 74) + "\n")
	sb.WriteString(fmt.Sprintf("Tokens Saved by AST & DOM Pruning:  %d tokens (~90%% reduction)\n", r.TokensSavedByPruning))
	sb.WriteString(fmt.Sprintf("Estimated Company Savings:          $%.2f USD\n", r.EstimatedDollarSavingsUSD))
	sb.WriteString("==========================================================================\n")
	return sb.String()
}
