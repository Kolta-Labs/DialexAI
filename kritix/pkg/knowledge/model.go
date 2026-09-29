package knowledge

import "time"

// KnowledgeCategory classifies institutional learnings.
type KnowledgeCategory string

const (
	CategoryArchitecture  KnowledgeCategory = "architecture"
	CategoryDebugging     KnowledgeCategory = "debugging"
	CategoryFlakyTest     KnowledgeCategory = "flaky_test"
	CategoryBuildManifest KnowledgeCategory = "build_manifest"
)

// KnowledgeItem encapsulates an institutional learning distilled from an engineering run.
type KnowledgeItem struct {
	ID              string            `json:"id"`
	Title           string            `json:"title"`
	Category        KnowledgeCategory `json:"category"`
	Context         string            `json:"context"`
	Breakthrough    string            `json:"breakthrough"`
	AssociatedRules []string          `json:"associatedRules,omitempty"`
	CreatedAt       time.Time         `json:"createdAt"`
}

// SynthesizedRule represents a steering invariant derived from human review or agent convergence.
type SynthesizedRule struct {
	RuleID      string   `json:"ruleId"`
	Name        string   `json:"name"`
	IsTaboo     bool     `json:"isTaboo"` // true = Taboo Space forbidden argument; false = Heuristic rule
	RuleText    string   `json:"ruleText"`
	Rationale   string   `json:"rationale"`
	TargetRoles []string `json:"targetRoles"`
}
