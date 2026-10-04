package workflow

import (
	"sync"
)

// BlueprintDescriptor outlines metadata for a prebuilt workflow.
type BlueprintDescriptor struct {
	ID             string       `json:"id"`
	Name           string       `json:"name"`
	Category       string       `json:"category"` // "feature", "ci", "api", "chaos", "a11y", "maintenance"
	Tier           PipelineTier `json:"tier"`     // Canonical tier: Tier1PRGate, Tier2MergeGate, Tier3Nightly
	Description    string       `json:"description"`
	TargetAudience string       `json:"target_audience"`
	DefaultTimeout string       `json:"default_timeout"`
	ZeroLLM        bool         `json:"zero_llm"`  // True if pipeline executes 100% deterministically without LLM calls
	FastPath       bool         `json:"fast_path"` // True if optimized for PR checks under 120s
}

// Blueprint defines a factory creating an executable DAG for a standard QA pattern.
type Blueprint interface {
	Descriptor() BlueprintDescriptor
	BuildDAG() (*DAG, error)
}

// BlueprintRegistry maintains all available prebuilt workflows.
type BlueprintRegistry struct {
	mu         sync.RWMutex
	blueprints map[string]Blueprint
}

var defaultRegistry = &BlueprintRegistry{
	blueprints: make(map[string]Blueprint),
}

// RegisterBlueprint registers a prebuilt workflow blueprint.
func RegisterBlueprint(bp Blueprint) {
	defaultRegistry.mu.Lock()
	defer defaultRegistry.mu.Unlock()
	defaultRegistry.blueprints[bp.Descriptor().ID] = bp
}

// GetBlueprint retrieves a blueprint by ID.
func GetBlueprint(id string) (Blueprint, bool) {
	defaultRegistry.mu.RLock()
	defer defaultRegistry.mu.RUnlock()
	bp, ok := defaultRegistry.blueprints[id]
	return bp, ok
}

// ListBlueprints returns metadata descriptors for all registered blueprints.
func ListBlueprints() []BlueprintDescriptor {
	defaultRegistry.mu.RLock()
	defer defaultRegistry.mu.RUnlock()
	var list []BlueprintDescriptor
	for _, bp := range defaultRegistry.blueprints {
		list = append(list, bp.Descriptor())
	}
	return list
}
