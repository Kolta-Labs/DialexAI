package graph

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func newTestStore(t *testing.T) *SQLiteGraphStore {
	t.Helper()
	s, err := OpenSQLite(filepath.Join(t.TempDir(), "g.db"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

func node(id, project, title, content string, now time.Time) *Node {
	return &Node{
		ID: id, ProjectID: project, Type: NodeTypeConcept, Title: title, Content: content,
		Weight: 1.0, DecayHalfLifeSecs: 86400, CreatedAt: now.Unix(), LastAccessedAt: now.Unix(),
	}
}

func TestComputeDecay(t *testing.T) {
	const day = int64(86400)
	cases := []struct {
		name            string
		base            float64
		last, half, now int64
		want            float64
	}{
		{"no time elapsed", 1.0, 100, day, 100, 1.0},
		{"clock went backwards", 1.0, 200, day, 100, 1.0},
		{"one half-life", 1.0, 0, day, day, 0.5},
		{"two half-lives", 1.0, 0, day, 2 * day, 0.25},
		{"scaled base", 0.8, 0, day, day, 0.4},
		{"zero base stays zero", 0, 0, day, day, 0},
		{"negative base clamps to zero", -1, 0, day, day, 0},
		{"zero half-life falls back to 30 days", 1.0, 0, 0, 30 * day, 0.5},
		{"floors to exactly zero when negligible", 1.0, 0, day, 20 * day, 0},
	}
	for _, c := range cases {
		if got := ComputeDecay(c.base, c.last, c.half, c.now); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

func TestReinforceEdgeCreatesWithShortIDs(t *testing.T) {
	s, ctx, now := newTestStore(t), context.Background(), time.Now().UTC()
	// IDs shorter than 8 chars must not panic when the edge does not exist yet.
	s.UpsertNode(ctx, node("a", "p", "A", "alpha", now))
	s.UpsertNode(ctx, node("b", "p", "B", "beta", now))
	if err := s.ReinforceEdge(ctx, "p", "a", "b", RelationSupports, 0.2, now); err != nil {
		t.Fatalf("reinforce create: %v", err)
	}
	g, _ := s.GetActiveGraph(ctx, "p", 0, now)
	if len(g.Edges) != 1 {
		t.Fatalf("want 1 edge created, got %d", len(g.Edges))
	}
	if got := g.Edges[0].Strength; got != 0.6 {
		t.Fatalf("new edge strength = %v, want 0.6 (0.5 + eta*0.5)", got)
	}
}

func TestReinforceEdgeConvergesAndCaps(t *testing.T) {
	s, ctx, now := newTestStore(t), context.Background(), time.Now().UTC()
	s.UpsertNode(ctx, node("n-001", "p", "A", "alpha", now))
	s.UpsertNode(ctx, node("n-002", "p", "B", "beta", now))
	s.UpsertEdge(ctx, &Edge{ID: "e1", ProjectID: "p", SourceID: "n-001", TargetID: "n-002", Relation: RelationSupports, Strength: 0.5})
	prev := 0.5
	for i := 0; i < 50; i++ {
		if err := s.ReinforceEdge(ctx, "p", "n-001", "n-002", RelationSupports, 0.3, now); err != nil {
			t.Fatal(err)
		}
		g, _ := s.GetActiveGraph(ctx, "p", 0, now)
		got := g.Edges[0].Strength
		if got < prev || got > 1.0 {
			t.Fatalf("iteration %d: strength %v not monotonic within [prev=%v, 1.0]", i, got, prev)
		}
		prev = got
	}
	if prev < 0.99 {
		t.Fatalf("expected convergence towards 1.0, got %v", prev)
	}
}

func TestProjectIsolation(t *testing.T) {
	s, ctx, now := newTestStore(t), context.Background(), time.Now().UTC()
	s.UpsertNode(ctx, node("a1", "alpha", "Raft consensus", "leader election quorum", now))
	s.UpsertNode(ctx, node("b1", "beta", "Raft consensus", "leader election quorum", now))

	res, err := s.SearchFTS(ctx, "alpha", "quorum", 10, now)
	if err != nil || len(res) != 1 || res[0].ID != "a1" {
		t.Fatalf("FTS leaked across projects: %v %v", res, err)
	}
	g, _ := s.GetActiveGraph(ctx, "beta", 0, now)
	if len(g.Nodes) != 1 || g.Nodes[0].ID != "b1" {
		t.Fatalf("GetActiveGraph leaked across projects: %+v", g.Nodes)
	}
}

func TestFTSReflectsUpdateAndDelete(t *testing.T) {
	s, ctx, now := newTestStore(t), context.Background(), time.Now().UTC()
	s.UpsertNode(ctx, node("n1", "p", "Caching", "memcached eviction policy", now))

	upd := node("n1", "p", "Caching", "redis persistence snapshots", now)
	if err := s.UpsertNode(ctx, upd); err != nil {
		t.Fatal(err)
	}
	if r, _ := s.SearchFTS(ctx, "p", "memcached", 5, now); len(r) != 0 {
		t.Fatalf("stale FTS entry survived update: %+v", r)
	}
	if r, _ := s.SearchFTS(ctx, "p", "redis", 5, now); len(r) != 1 {
		t.Fatalf("updated content not searchable: %+v", r)
	}
	s.DeleteNode(ctx, "n1")
	if r, _ := s.SearchFTS(ctx, "p", "redis", 5, now); len(r) != 0 {
		t.Fatalf("FTS entry survived delete: %+v", r)
	}
}

func TestDormantNodesExcludedButRecallable(t *testing.T) {
	s, ctx, now := newTestStore(t), context.Background(), time.Now().UTC()
	s.UpsertNode(ctx, node("n1", "p", "Old idea", "sharding strategy", now))
	later := now.Add(10 * 24 * time.Hour) // 10 half-lives, well below 0.1

	g, _ := s.GetActiveGraph(ctx, "p", 0.1, later)
	if len(g.Nodes) != 0 {
		t.Fatalf("dormant node leaked into active graph")
	}
	// Explicit FTS query still finds it (spec: dormant unless queried via FTS5).
	r, err := s.SearchFTS(ctx, "p", "sharding", 5, later)
	if err != nil || len(r) != 1 {
		t.Fatalf("dormant node not recallable via FTS: %v %v", r, err)
	}
}

func TestMaintenanceDecayPrunesByDecayedWeight(t *testing.T) {
	s, ctx, now := newTestStore(t), context.Background(), time.Now().UTC()
	s.UpsertNode(ctx, node("stale", "p", "Stale", "abandoned topic", now))
	fresh := node("fresh", "p", "Fresh", "active topic", now.Add(99*24*time.Hour))
	s.UpsertNode(ctx, fresh)

	later := now.Add(100 * 24 * time.Hour)
	// "stale": stored weight 1.0 but untouched for 100 days at a 1-day half-life: decayed ~0.
	n, err := s.RunMaintenanceDecay(ctx, 0.1, 30*86400, later)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("pruned %d nodes, want 1 (the stale one)", n)
	}
	if _, err := s.GetNode(ctx, "fresh", later); err != nil {
		t.Fatalf("fresh node was pruned: %v", err)
	}
}
