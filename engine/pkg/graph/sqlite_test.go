package graph

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSQLiteGraphStore(t *testing.T) {
	tmpDir, err := os.MkdirTemp("", "graph-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tmpDir)

	dbPath := filepath.Join(tmpDir, "test_graph.db")
	store, err := OpenSQLite(dbPath)
	if err != nil {
		t.Fatalf("failed to open sqlite graph store: %v", err)
	}
	defer store.Close()

	ctx := context.Background()
	now := time.Now().UTC()

	// 1. Insert Node
	node1 := &Node{
		ID:                "n-001",
		ProjectID:         "p-alpha",
		Type:              NodeTypeConcept,
		Title:             "Vector Clock Consistency",
		Content:           "Vector clocks provide causal order guarantees across distributed nodes.",
		Weight:            1.0,
		DecayHalfLifeSecs: 86400, // 1 day
		CreatedAt:         now.Unix(),
		LastAccessedAt:    now.Unix(),
	}
	if err := store.UpsertNode(ctx, node1); err != nil {
		t.Fatalf("UpsertNode failed: %v", err)
	}

	node2 := &Node{
		ID:                "n-002",
		ProjectID:         "p-alpha",
		Type:              NodeTypeTension,
		Title:             "Eventual Consistency vs ACID",
		Content:           "Conflict between low latency multi-master writes and strict serializability.",
		Weight:            1.0,
		DecayHalfLifeSecs: 86400,
		CreatedAt:         now.Unix(),
		LastAccessedAt:    now.Unix(),
	}
	if err := store.UpsertNode(ctx, node2); err != nil {
		t.Fatalf("UpsertNode failed: %v", err)
	}

	// 2. Insert Edge
	edge1 := &Edge{
		ID:        "e-001",
		ProjectID: "p-alpha",
		SourceID:  "n-001",
		TargetID:  "n-002",
		Relation:  RelationSupports,
		Strength:  0.5,
	}
	if err := store.UpsertEdge(ctx, edge1); err != nil {
		t.Fatalf("UpsertEdge failed: %v", err)
	}

	// 3. Test FTS5 Search
	results, err := store.SearchFTS(ctx, "p-alpha", "distributed causal", 5, now)
	if err != nil {
		t.Fatalf("SearchFTS failed: %v", err)
	}
	if len(results) != 1 || results[0].ID != "n-001" {
		t.Fatalf("Expected n-001 match in FTS, got %+v", results)
	}

	// 4. Test Decay progression
	// After 1 half-life (1 day), current weight should be ~0.5
	future1Day := now.Add(24 * time.Hour)
	fetched, err := store.GetNode(ctx, "n-001", future1Day)
	if err != nil {
		t.Fatalf("GetNode failed: %v", err)
	}
	if fetched.CurrentWeight < 0.48 || fetched.CurrentWeight > 0.52 {
		t.Fatalf("Expected weight ~0.5 after 1 day, got %f", fetched.CurrentWeight)
	}

	// After 4 half-lives (4 days), weight = 1.0 * (1/16) = 0.0625 (< 0.10 dormant)
	future4Days := now.Add(4 * 24 * time.Hour)
	activeGraph, err := store.GetActiveGraph(ctx, "p-alpha", 0.10, future4Days)
	if err != nil {
		t.Fatalf("GetActiveGraph failed: %v", err)
	}
	if len(activeGraph.Nodes) != 0 {
		t.Fatalf("Expected 0 active nodes above 0.10 threshold, got %d", len(activeGraph.Nodes))
	}

	// 5. Test Touch (Resurrection)
	if err := store.TouchNode(ctx, "n-001", future4Days); err != nil {
		t.Fatalf("TouchNode failed: %v", err)
	}
	touched, err := store.GetNode(ctx, "n-001", future4Days)
	if err != nil {
		t.Fatalf("GetNode after touch failed: %v", err)
	}
	if touched.CurrentWeight != 1.0 {
		t.Fatalf("Expected resurrected weight 1.0, got %f", touched.CurrentWeight)
	}

	// 6. Test Edge Reinforcement
	if err := store.ReinforceEdge(ctx, "p-alpha", "n-001", "n-002", RelationSupports, 0.20, future4Days); err != nil {
		t.Fatalf("ReinforceEdge failed: %v", err)
	}
	activeGraph2, err := store.GetActiveGraph(ctx, "p-alpha", 0.05, future4Days)
	if err != nil {
		t.Fatalf("GetActiveGraph failed: %v", err)
	}
	if len(activeGraph2.Edges) != 1 || activeGraph2.Edges[0].Strength <= 0.5 {
		t.Fatalf("Expected reinforced edge strength > 0.5, got %v", activeGraph2.Edges)
	}

	// 7. Test Cascading Delete
	if err := store.DeleteNode(ctx, "n-001"); err != nil {
		t.Fatalf("DeleteNode failed: %v", err)
	}
	activeGraph3, err := store.GetActiveGraph(ctx, "p-alpha", 0.0, future4Days)
	if err != nil {
		t.Fatalf("GetActiveGraph failed: %v", err)
	}
	if len(activeGraph3.Edges) != 0 {
		t.Fatalf("Expected cascade deletion of edges, got %d edges remaining", len(activeGraph3.Edges))
	}
}
