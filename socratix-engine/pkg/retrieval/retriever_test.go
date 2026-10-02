package retrieval

import (
	"context"
	"strings"
	"testing"
	"time"

	"dialex/pkg/graph"
	"dialex/pkg/model"
)

func TestHeuristicExtractQueries(t *testing.T) {
	retriever := NewDynamicRetriever(nil)

	messages := []model.DebateMessage{
		{
			Round:   1,
			Content: "I assert that SQLite in WAL mode achieves higher benchmark throughput than PostgreSQL for single-node deployments.",
		},
		{
			Round:   1,
			Content: "I strongly disagree. PostgreSQL concurrency with Raft consensus handles replication and latency far better under lock contention.",
		},
	}

	queries := retriever.HeuristicExtractQueries("Database Architecture", messages)
	if len(queries) == 0 {
		t.Fatalf("expected extracted queries, got 0")
	}

	foundRelevant := false
	for _, q := range queries {
		lower := strings.ToLower(q)
		if strings.Contains(lower, "sqlite") || strings.Contains(lower, "benchmark") || strings.Contains(lower, "postgres") || strings.Contains(lower, "concurrency") {
			foundRelevant = true
			break
		}
	}

	if !foundRelevant {
		t.Errorf("expected relevant technical queries, got: %v", queries)
	}
}

func TestRetrieveForRound_AttachedFiles(t *testing.T) {
	retriever := NewDynamicRetriever(nil)

	attachedDoc := model.AttachedFile{
		ID:   "doc_benchmarks",
		Name: "benchmark_results.md",
		Content: "Executive Summary:\n\n" +
			"PostgreSQL Benchmark Results:\n" +
			"Under heavy concurrency, PostgreSQL sustained 14,200 write operations per second with average latency of 3.8ms.\n\n" +
			"SQLite Benchmark Results:\n" +
			"SQLite in WAL mode achieved 8,500 writes/sec with lower memory overhead.",
	}

	messages := []model.DebateMessage{
		{
			Round:   1,
			Content: "What are the exact benchmark throughput and latency numbers for PostgreSQL under heavy concurrency?",
		},
	}

	ctx := context.Background()
	roundEvidence, err := retriever.RetrieveForRound(
		ctx,
		nil,
		model.Agent{},
		"proj_test",
		"Storage Architecture",
		1,
		messages,
		[]model.AttachedFile{attachedDoc},
		nil,
	)

	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if roundEvidence.Round != 2 {
		t.Errorf("expected target round 2, got %d", roundEvidence.Round)
	}

	if len(roundEvidence.Items) == 0 {
		t.Fatalf("expected retrieved items from attached doc, got 0")
	}

	topItem := roundEvidence.Items[0]
	if topItem.SourceType != model.EvidenceSourceAttachedFile {
		t.Errorf("expected source ATTACHED_FILE, got %s", topItem.SourceType)
	}
	if !strings.Contains(topItem.Snippet, "PostgreSQL sustained 14,200") && !strings.Contains(topItem.Snippet, "Benchmark Results") {
		t.Errorf("expected relevant snippet content, got: %s", topItem.Snippet)
	}

	if !strings.Contains(roundEvidence.SummaryContext, "[DYNAMIC GROUNDING EVIDENCE FOR ROUND 2]") {
		t.Errorf("expected summary context header, got: %s", roundEvidence.SummaryContext)
	}
}

func TestRetrieveForRound_KnowledgeGraphAndDeduplication(t *testing.T) {
	store, err := graph.OpenSQLite(":memory:")
	if err != nil {
		t.Fatalf("failed to open memory sqlite: %v", err)
	}
	defer store.Close()

	ctx := context.Background()
	now := time.Now().UTC()

	// Seed knowledge graph node
	node := &graph.Node{
		ID:             "node_sqlite_wal",
		ProjectID:      "proj_123",
		Type:           graph.NodeTypeConcept,
		Title:          "SQLite WAL Concurrency",
		Content:        "Write-Ahead Logging (WAL) permits concurrent readers while a single writer operates. Readers never block writers.",
		Weight:         1.0,
		CurrentWeight:  1.0,
		CreatedAt:      now.Unix(),
		LastAccessedAt: now.Unix(),
	}
	if err := store.UpsertNode(ctx, node); err != nil {
		t.Fatalf("failed to insert graph node: %v", err)
	}

	retriever := NewDynamicRetriever(store)

	messagesRound1 := []model.DebateMessage{
		{
			Round:   1,
			Content: "SQLite concurrency locking prevents readers from accessing the database while writing.",
		},
	}

	// First round retrieval for Round 2
	evidenceR2, err := retriever.RetrieveForRound(
		ctx,
		nil,
		model.Agent{},
		"proj_123",
		"Database Engine Dilemma",
		1,
		messagesRound1,
		nil,
		nil,
	)
	if err != nil {
		t.Fatalf("failed round 1 retrieval: %v", err)
	}

	if len(evidenceR2.Items) == 0 {
		t.Fatalf("expected evidence item from graph, got 0")
	}

	if evidenceR2.Items[0].SourceID != "node_sqlite_wal" {
		t.Errorf("expected source ID node_sqlite_wal, got %s", evidenceR2.Items[0].SourceID)
	}

	// Second round retrieval for Round 3: pass existing evidence from Round 2
	messagesRound2 := []model.DebateMessage{
		{
			Round:   2,
			Content: "Even with SQLite concurrency, what about scaling writes?",
		},
	}

	existingEvidence := []model.RoundEvidence{evidenceR2}
	evidenceR3, err := retriever.RetrieveForRound(
		ctx,
		nil,
		model.Agent{},
		"proj_123",
		"Database Engine Dilemma",
		2,
		messagesRound2,
		nil,
		existingEvidence,
	)
	if err != nil {
		t.Fatalf("failed round 2 retrieval: %v", err)
	}

	// Verify deduplication: node_sqlite_wal should NOT be re-retrieved in Round 3!
	for _, item := range evidenceR3.Items {
		if item.SourceID == "node_sqlite_wal" {
			t.Errorf("deduplication violation: node_sqlite_wal re-retrieved in Round 3")
		}
	}
}
