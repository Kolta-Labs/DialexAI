package graph

import (
	"context"
	"time"
)

// NodeType designates the epistemic category of a graph node.
type NodeType string

const (
	NodeTypeConcept     NodeType = "CONCEPT"
	NodeTypeArgument    NodeType = "ARGUMENT"
	NodeTypeConsensus   NodeType = "CONSENSUS"
	NodeTypeTension     NodeType = "TENSION"
	NodeTypeDeliverable NodeType = "DELIVERABLE"
	NodeTypeSource      NodeType = "SOURCE"
)

// RelationType defines the directed semantic link between two nodes.
type RelationType string

const (
	RelationContradicts     RelationType = "CONTRADICTS"
	RelationSupports        RelationType = "SUPPORTS"
	RelationBlendsInto      RelationType = "BLENDS_INTO"
	RelationDerivedFrom     RelationType = "DERIVED_FROM"
	RelationPrerequisiteFor RelationType = "PREREQUISITE_FOR"
)

// Node represents an atomic epistemic unit within a project's knowledge graph.
type Node struct {
	ID                string         `json:"id"`
	ProjectID         string         `json:"project_id"`
	Type              NodeType       `json:"type"`
	Title             string         `json:"title"`
	Content           string         `json:"content"`
	Weight            float64        `json:"weight"`
	CurrentWeight     float64        `json:"current_weight"` // Computed at query time via exponential decay
	DecayHalfLifeSecs int64          `json:"decay_half_life_secs"`
	CreatedAt         int64          `json:"created_at"`
	LastAccessedAt    int64          `json:"last_accessed_at"`
	Metadata          map[string]any `json:"metadata"`
}

// Edge represents a directed relation connecting source to target node.
type Edge struct {
	ID               string       `json:"id"`
	ProjectID        string       `json:"project_id"`
	SourceID         string       `json:"source_id"`
	TargetID         string       `json:"target_id"`
	Relation         RelationType `json:"relation"`
	Strength         float64      `json:"strength"` // [0.0, 1.0]
	CreatedAt        int64        `json:"created_at"`
	LastReinforcedAt int64        `json:"last_reinforced_at"`
}

// Graph contains a set of nodes and their connecting edges.
type Graph struct {
	Nodes []*Node `json:"nodes"`
	Edges []*Edge `json:"edges"`
}

// GraphStore defines the persistence interface for the local-first knowledge graph.
type GraphStore interface {
	// UpsertNode creates or updates a node. When an existing node is updated, its lastAccessedAt is refreshed.
	UpsertNode(ctx context.Context, n *Node) error

	// TouchNode updates last_accessed_at to now and resets W0 to 1.0.
	TouchNode(ctx context.Context, id string, now time.Time) error

	// GetNode retrieves a single node by ID with decayed weight calculated.
	GetNode(ctx context.Context, id string, now time.Time) (*Node, error)

	// DeleteNode removes a node and cascades deletion to all connected edges.
	DeleteNode(ctx context.Context, id string) error

	// UpsertEdge creates or updates a directed edge.
	UpsertEdge(ctx context.Context, e *Edge) error

	// ReinforceEdge strengthens an edge between two nodes via Hebbian update.
	ReinforceEdge(ctx context.Context, projectID, sourceID, targetID string, rel RelationType, eta float64, now time.Time) error

	// SearchFTS performs full-text search with BM25 ranking against title and content.
	SearchFTS(ctx context.Context, projectID, query string, limit int, now time.Time) ([]*Node, error)

	// GetActiveGraph fetches nodes and edges whose computed weight meets or exceeds minWeight at time now.
	GetActiveGraph(ctx context.Context, projectID string, minWeight float64, now time.Time) (*Graph, error)

	// RunMaintenanceDecay prunes or archives nodes below threshold that have not been touched for maxStaleSecs.
	RunMaintenanceDecay(ctx context.Context, minThreshold float64, maxStaleSecs int64, now time.Time) (int64, error)

	// Close releases database resources.
	Close() error
}
