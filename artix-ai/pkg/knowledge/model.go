package knowledge

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// KnowledgeCategory classifies institutional learnings.
type KnowledgeCategory string

const (
	CategoryArchitecture  KnowledgeCategory = "architecture"
	CategoryDebugging     KnowledgeCategory = "debugging"
	CategoryFlakyTest     KnowledgeCategory = "flaky_test"
	CategoryBuildManifest KnowledgeCategory = "build_manifest"
)

// CurrentSchemaVersion is the latest schema version for KnowledgeItem.
const CurrentSchemaVersion = 2

// KnowledgeItem encapsulates an institutional learning distilled from an engineering run.
type KnowledgeItem struct {
	SchemaVersion   int               `json:"schemaVersion"`
	ID              string            `json:"id"`
	Title           string            `json:"title"`
	Category        KnowledgeCategory `json:"category"`
	Context         string            `json:"context"`
	Breakthrough    string            `json:"breakthrough"`
	AssociatedRules []string          `json:"associatedRules,omitempty"`
	Author          string            `json:"author,omitempty"`
	SourcePR        string            `json:"sourcePr,omitempty"`
	ModelID         string            `json:"modelId,omitempty"`
	Confidence      float64           `json:"confidence"`
	ContentHash     string            `json:"contentHash,omitempty"`
	Status          string            `json:"status,omitempty"` // "proposed", "approved", "deprecated"
	ApprovedBy      string            `json:"approvedBy,omitempty"`
	CreatedAt       time.Time         `json:"createdAt"`
	ExpiresAt       *time.Time        `json:"expiresAt,omitempty"`
}

// IsExpired checks if a knowledge item has exceeded its TTL.
func (ki *KnowledgeItem) IsExpired() bool {
	return ki.ExpiresAt != nil && time.Now().After(*ki.ExpiresAt)
}

// RuleStatus represents the governance state of a synthesized rule.
type RuleStatus string

const (
	RuleStatusProposed RuleStatus = "proposed"
	RuleStatusApproved RuleStatus = "approved"
	RuleStatusRejected RuleStatus = "rejected"
)

// SynthesizedRule represents a steering invariant derived from human review or agent convergence.
type SynthesizedRule struct {
	RuleID      string     `json:"ruleId"`
	Name        string     `json:"name"`
	IsTaboo     bool       `json:"isTaboo"` // true = Taboo Space forbidden argument; false = Heuristic rule
	RuleText    string     `json:"ruleText"`
	Rationale   string     `json:"rationale"`
	TargetRoles []string   `json:"targetRoles"`
	SourcePR    string     `json:"sourcePr,omitempty"`
	Author      string     `json:"author,omitempty"`
	Confidence  float64    `json:"confidence"`
	Status      RuleStatus `json:"status"` // "proposed", "approved", "rejected"
	ApprovedBy  string     `json:"approvedBy,omitempty"`
	CreatedAt   time.Time  `json:"createdAt"`
	ExpiresAt   *time.Time `json:"expiresAt,omitempty"`
}

// Approve promotes a proposed rule to active status. Enforces that the approver is human (not a bot).
func (r *SynthesizedRule) Approve(approver string) error {
	trimmed := strings.TrimSpace(approver)
	if trimmed == "" {
		return errors.New("cannot approve rule: approver identity is required")
	}
	lower := strings.ToLower(trimmed)
	if lower == "artix-agent" || lower == "artix-bot" || lower == "bot" || strings.Contains(lower, "[bot]") {
		return fmt.Errorf("bot identity %q cannot approve synthesized rules; human approval required", trimmed)
	}
	r.Status = RuleStatusApproved
	r.ApprovedBy = trimmed
	return nil
}

// IsActive returns true if the rule is approved and not expired.
func (r *SynthesizedRule) IsActive() bool {
	if r.Status != RuleStatusApproved {
		return false
	}
	if r.ExpiresAt != nil && time.Now().After(*r.ExpiresAt) {
		return false
	}
	return true
}
