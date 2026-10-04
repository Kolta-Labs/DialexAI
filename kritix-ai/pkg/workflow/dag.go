package workflow

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
)

// PipelineTier models the canonical CI/CD execution gate.
type PipelineTier string

const (
	Tier1PRGate    PipelineTier = "tier1_pr_gate"   // Hard 2-minute budget, Zero-LLM, deterministic only
	Tier2MergeGate PipelineTier = "tier2_merge_gate" // Hard 8-minute budget, smoke/generated test execution
	Tier3Nightly   PipelineTier = "tier3_nightly"   // 60-minute budget, full doc ingestion, fuzzing, perf
)

// TierBudgets maps canonical tiers to their hard framework-enforced time budgets.
var TierBudgets = map[PipelineTier]time.Duration{
	Tier1PRGate:    120 * time.Second,
	Tier2MergeGate: 8 * time.Minute,
	Tier3Nightly:   60 * time.Minute,
}

var (
	ErrBudgetExceeded               = errors.New("pipeline tier time budget exceeded: execution aborted to protect CI developer cycle times")
	ErrTierMismatch                 = errors.New("blueprint tier mismatch: running Tier 3 blueprints in Tier 1 PR gates is strictly prohibited")
	ErrSharedInfraFuzzingProhibited = errors.New("autonomous fuzzing prohibited: target environment shares infrastructure or database clusters with production (SOC 2 Type II violation)")
)

// Node wraps a Block with its dependencies and execution conditions.
type Node struct {
	ID        string   `json:"id"`
	Block     Block    `json:"-"`
	DependsOn []string `json:"depends_on,omitempty"`
	Condition string   `json:"condition,omitempty"` // e.g. "failures == 0", "always"
}

// DAG represents a directed acyclic workflow of testing blocks.
type DAG struct {
	Name          string           `json:"name"`
	Description   string           `json:"description"`
	Nodes         map[string]*Node `json:"nodes"`
	Tier          PipelineTier     `json:"tier,omitempty"`
	MaxTimeBudget time.Duration    `json:"max_time_budget,omitempty"`
}

// NewDAG creates an empty DAG.
func NewDAG(name, desc string) *DAG {
	return &DAG{
		Name:        name,
		Description: desc,
		Nodes:       make(map[string]*Node),
	}
}

// SetTier assigns a pipeline tier and sets its enforced maximum time budget.
func (d *DAG) SetTier(tier PipelineTier) *DAG {
	d.Tier = tier
	if budget, ok := TierBudgets[tier]; ok {
		d.MaxTimeBudget = budget
	}
	return d
}

// SetMaxTimeBudget manually configures a hard execution timeout budget.
func (d *DAG) SetMaxTimeBudget(budget time.Duration) *DAG {
	d.MaxTimeBudget = budget
	return d
}

// AddNode adds a block node to the DAG.
func (d *DAG) AddNode(id string, blk Block, dependsOn ...string) *DAG {
	d.Nodes[id] = &Node{
		ID:        id,
		Block:     blk,
		DependsOn: dependsOn,
	}
	return d
}

// DAGResult records the complete outcome of a DAG pipeline execution.
type DAGResult struct {
	DAGName                string                 `json:"dag_name"`
	Success                bool                   `json:"success"`
	Duration               time.Duration          `json:"duration"`
	NodeResults            map[string]BlockResult `json:"node_results"`
	SimulatedComponents    []string               `json:"simulated_components,omitempty"`
	CacheHitRate           float64                `json:"cache_hit_rate"`
	CachedNodes            int                    `json:"cached_nodes"`
	TotalNodes             int                    `json:"total_nodes"`
	PassedNodes            int                    `json:"passed_nodes"`
	PassedWithHealingNodes int                    `json:"passed_with_healing_nodes"`
	FailedNodes            int                    `json:"failed_nodes"`
	QuarantinedNodes       int                    `json:"quarantined_nodes"`
	SkippedNodes           int                    `json:"skipped_nodes"`
	Context                *Context               `json:"context"`
}

// SatisfiesPRMergeGate enforces that PASSED_WITH_HEALING never passes PR gates without an explicit override.
func (r *DAGResult) SatisfiesPRMergeGate(allowHealedOverride bool) (bool, string) {
	if r.FailedNodes > 0 {
		return false, fmt.Sprintf("PR gate failed: %d failed test blocks detected", r.FailedNodes)
	}
	if r.PassedWithHealingNodes > 0 && !allowHealedOverride {
		return false, fmt.Sprintf("PR gate blocked: %d tests completed via PASSED_WITH_HEALING (self-healed locators). Explicit SDET sign-off or --allow-healed-override required.", r.PassedWithHealingNodes)
	}
	return true, "PR merge gate satisfied: all tests passed clean"
}

// Execute sorts nodes topologically and runs them respecting dependencies and tier time budgets.
func (d *DAG) Execute(ctx context.Context, bCtx *Context) (*DAGResult, error) {
	start := time.Now()

	budget := d.MaxTimeBudget
	if budget <= 0 && d.Tier != "" {
		budget = TierBudgets[d.Tier]
	}

	execCtx := ctx
	var cancel context.CancelFunc
	if budget > 0 {
		execCtx, cancel = context.WithTimeout(ctx, budget)
		defer cancel()
	}

	// 1. Topological sort with cycle detection
	order, err := d.topologicalSort()
	if err != nil {
		return nil, fmt.Errorf("invalid dag topology: %w", err)
	}

	results := make(map[string]BlockResult)
	passedCount := 0
	healedCount := 0
	failedCount := 0
	quarantinedCount := 0
	skippedCount := 0

	for _, nodeID := range order {
		// Check tier time budget
		if execCtx.Err() != nil {
			if errors.Is(execCtx.Err(), context.DeadlineExceeded) {
				return nil, fmt.Errorf("%w: execution exceeded tier %q time budget (%v)", ErrBudgetExceeded, d.Tier, budget)
			}
			return nil, execCtx.Err()
		}

		node := d.Nodes[nodeID]

		// Check if any prerequisite dependency failed
		depFailed := false
		for _, dep := range node.DependsOn {
			if node.Condition == "always" {
				break
			}
			if res, ok := results[dep]; ok && res.Status == StatusFailed {
				depFailed = true
				break
			}
		}

		if depFailed {
			results[nodeID] = BlockResult{
				BlockID:  nodeID,
				Status:   StatusSkipped,
				Message:  "Skipped due to upstream dependency failure",
				Duration: 0,
			}
			skippedCount++
			bCtx.AddLog(fmt.Sprintf("Node %q skipped (upstream failure)", nodeID))
			continue
		}

		// Execute the node's block
		nodeStart := time.Now()
		bCtx.AddLog(fmt.Sprintf("Executing node %q...", nodeID))
		res, err := node.Block.Execute(execCtx, bCtx)
		if err != nil {
			if errors.Is(err, context.DeadlineExceeded) || errors.Is(execCtx.Err(), context.DeadlineExceeded) {
				return nil, fmt.Errorf("%w: execution exceeded tier %q time budget (%v)", ErrBudgetExceeded, d.Tier, budget)
			}
			failedCount++
			results[nodeID] = BlockResult{
				BlockID:  nodeID,
				Status:   StatusFailed,
				Message:  err.Error(),
				Duration: time.Since(nodeStart),
				Error:    err,
			}
			bCtx.AddFailure(fmt.Sprintf("Node %s failed: %v", nodeID, err))
			continue
		}

		res.Duration = time.Since(nodeStart)
		results[nodeID] = *res
		switch res.Status {
		case StatusPassed:
			passedCount++
		case StatusPassedWithHealing:
			healedCount++
		case StatusFailed:
			failedCount++
		case StatusQuarantined:
			quarantinedCount++
		case StatusSkipped:
			skippedCount++
		case StatusSimulated:
			// Simulated component executed safely without side effects
		}
	}

	totalDuration := time.Since(start)
	isSuccess := failedCount == 0

	var simulatedComponents []string
	for id, node := range d.Nodes {
		res, ok := results[id]
		if ok && (res.Simulated || res.Status == StatusSimulated || (node.Block != nil && node.Block.Descriptor().Simulated)) {
			simulatedComponents = append(simulatedComponents, node.Block.Descriptor().ID)
		}
	}

	cachedCount := 0
	for _, res := range results {
		if strings.Contains(strings.ToLower(res.Message), "cache hit") {
			cachedCount++
		}
	}
	cacheHitRate := 0.0
	if len(order) > 0 && cachedCount > 0 {
		cacheHitRate = (float64(cachedCount) / float64(len(order))) * 100.0
	}

	return &DAGResult{
		DAGName:                d.Name,
		Success:                isSuccess,
		Duration:               totalDuration,
		NodeResults:            results,
		SimulatedComponents:    simulatedComponents,
		CacheHitRate:           cacheHitRate,
		CachedNodes:            cachedCount,
		TotalNodes:             len(order),
		PassedNodes:            passedCount,
		PassedWithHealingNodes: healedCount,
		FailedNodes:            failedCount,
		QuarantinedNodes:       quarantinedCount,
		SkippedNodes:           skippedCount,
		Context:                bCtx,
	}, nil
}

// topologicalSort computes valid execution order or returns error on cycles.
func (d *DAG) topologicalSort() ([]string, error) {
	inDegree := make(map[string]int)
	adj := make(map[string][]string)

	for id := range d.Nodes {
		inDegree[id] = 0
		adj[id] = make([]string, 0)
	}

	for id, node := range d.Nodes {
		for _, dep := range node.DependsOn {
			if _, exists := d.Nodes[dep]; !exists {
				return nil, fmt.Errorf("node %q references unknown dependency %q", id, dep)
			}
			adj[dep] = append(adj[dep], id)
			inDegree[id]++
		}
	}

	var queue []string
	for id, deg := range inDegree {
		if deg == 0 {
			queue = append(queue, id)
		}
	}

	var order []string
	for len(queue) > 0 {
		curr := queue[0]
		queue = queue[1:]
		order = append(order, curr)

		for _, neighbor := range adj[curr] {
			inDegree[neighbor]--
			if inDegree[neighbor] == 0 {
				queue = append(queue, neighbor)
			}
		}
	}

	if len(order) != len(d.Nodes) {
		return nil, fmt.Errorf("cycle detected in workflow DAG (resolved %d of %d nodes)", len(order), len(d.Nodes))
	}

	return order, nil
}

// PrintSummary renders an audit table of node execution statuses.
func (r *DAGResult) PrintSummary() string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("Workflow: %s | Result: %s | Duration: %s | Cache Hit Rate: %.1f%%\n",
		r.DAGName, formatStatus(r.Success), r.Duration.Round(time.Millisecond), r.CacheHitRate))
	sb.WriteString(strings.Repeat("-", 60) + "\n")
	for nodeID, res := range r.NodeResults {
		sb.WriteString(fmt.Sprintf("%-24s [%-7s] %s (%s)\n",
			nodeID, res.Status, res.Message, res.Duration.Round(time.Millisecond)))
	}
	sb.WriteString(strings.Repeat("-", 60) + "\n")
	return sb.String()
}

func formatStatus(ok bool) string {
	if ok {
		return "SUCCESS"
	}
	return "FAILED"
}

// ValidateBlueprintForTier ensures blueprints match their allowed pipeline tier.
func ValidateBlueprintForTier(desc BlueprintDescriptor, tier PipelineTier) error {
	// Tier 3 blueprints (council debate, ingestion, fuzzing) never run in a blocking tier.
	if desc.Tier == Tier3Nightly && tier != Tier3Nightly {
		return fmt.Errorf("%w: blueprint %q is Tier 3 and must run with --tier nightly", ErrTierMismatch, desc.ID)
	}
	if tier == Tier1PRGate {
		if !desc.ZeroLLM || !desc.FastPath {
			return fmt.Errorf("%w: blueprint %q cannot run in Tier 1 PR gate. PR gate is restricted to deterministic Zero-LLM checks (<120s)", ErrTierMismatch, desc.ID)
		}
	}
	return nil
}

// VerifyEnvironmentIsolation guards against fuzzing shared or production infrastructure.
func VerifyEnvironmentIsolation(targetURL string, isSharedWithProd bool) error {
	if isSharedWithProd {
		return fmt.Errorf("%w: target %q shares database or infrastructure with production. Autonomous fuzzing halted to prevent SOC 2 Type II breach",
			ErrSharedInfraFuzzingProhibited, targetURL)
	}
	urlLower := strings.ToLower(targetURL)
	if strings.Contains(urlLower, "prod.") || strings.Contains(urlLower, "production.") || strings.Contains(urlLower, "-prod") {
		return fmt.Errorf("%w: target %q appears to be a production environment. Fuzzing prohibited", ErrSharedInfraFuzzingProhibited, targetURL)
	}
	return nil
}
