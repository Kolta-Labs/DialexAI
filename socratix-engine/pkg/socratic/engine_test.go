package socratic

import (
	"context"
	"strings"
	"testing"

	"socratix/pkg/graph"
	"socratix/pkg/model"
)

func TestBuildSocraticSystemPrompt_AllStances(t *testing.T) {
	agent := model.Agent{
		DisplayName: "The Risk Analyst",
		Role:        "Adversary",
	}

	for _, stance := range model.AllSocraticStances {
		prompt := BuildSocraticSystemPrompt(agent, stance, model.StageHypothesisExtraction, "Postgres vs Redis Locks")
		if !strings.Contains(prompt, "BREVIS INTERROGATIO") {
			t.Errorf("prompt missing Brevis Interrogatio protocol for stance %s", stance)
		}
		if !strings.Contains(prompt, string(stance)) {
			t.Errorf("prompt missing stance identifier for %s", stance)
		}
	}
}

func TestHeuristicSocraticTurn_StanceVariations(t *testing.T) {
	topic := "Migrating Monolith to Event-Driven Microservices"
	msg := "We will use Kafka with at-least-once delivery."

	for _, stance := range model.AllSocraticStances {
		resp := HeuristicSocraticTurn(topic, msg, stance, model.StageHypothesisExtraction, 1)

		if strings.TrimSpace(resp.ProbeQuestion) == "" {
			t.Errorf("empty probe question for stance %s", stance)
		}
		if len(resp.LedgerUpdates) == 0 {
			t.Errorf("expected at least 1 ledger item for stance %s", stance)
		}
		if resp.NewStage == "" {
			t.Errorf("expected non-empty stage for stance %s", stance)
		}
	}
}

func TestHeuristicSocraticTurn_ConcedeAndDefend(t *testing.T) {
	topic := "Distributed DB Locking"

	// Conceding message
	concedeResp := HeuristicSocraticTurn(topic, "I concede that this constraint won't work under partition", model.StanceRuthlessElenchus, model.StageElenchusStressTesting, 3)
	hasConceded := false
	for _, l := range concedeResp.LedgerUpdates {
		if l.Type == model.LedgerItemConceded {
			hasConceded = true
			break
		}
	}
	if !hasConceded {
		t.Errorf("expected CONCEDED ledger item when user admits constraint failure")
	}

	// Defending message
	defendResp := HeuristicSocraticTurn(topic, "We guarantee transactional invariants using Raft log replication", model.StanceMaieuticArchitect, model.StageElenchusStressTesting, 4)
	hasHardened := false
	for _, l := range defendResp.LedgerUpdates {
		if l.Type == model.LedgerItemHardened {
			hasHardened = true
			break
		}
	}
	if !hasHardened {
		t.Errorf("expected HARDENED ledger item when user defends invariants")
	}
}

func TestHeuristicDigest_Structure(t *testing.T) {
	topic := "PostgreSQL Advisory Locks for Payment Gateways"
	transcript := []model.DebateMessage{
		{AuthorDisplayName: "User", Content: "We propose advisory locks for mutual exclusion."},
		{AuthorDisplayName: "Socrates", Content: "What happens if a worker terminates without releasing the lock?"},
	}
	ledger := []model.SocraticLedgerItem{
		{
			ID:        "ax_1",
			Type:      model.LedgerItemHardened,
			Statement: "Session-level lock bound to connection lifetime",
			Turn:      1,
		},
		{
			ID:        "ax_2",
			Type:      model.LedgerItemConceded,
			Statement: "Connection pool exhaustion under transaction timeouts",
			Turn:      2,
		},
	}

	digest := HeuristicDigest(topic, transcript, ledger)

	if digest.InitialHypothesis != topic {
		t.Errorf("expected initial hypothesis %s, got %s", topic, digest.InitialHypothesis)
	}
	if len(digest.DefendedInvariants) == 0 {
		t.Errorf("expected defended invariants to be populated")
	}
	if len(digest.ExposedBlindSpots) == 0 {
		t.Errorf("expected exposed blind spots to be populated")
	}
	if len(digest.ResidualTensions) == 0 {
		t.Errorf("expected residual tensions to be populated")
	}
	if !strings.Contains(digest.HardenedThesis, "Hardened Architectural Thesis") {
		t.Errorf("expected hardened thesis to contain header")
	}
}

// MockGraphStore for testing Knowledge Graph auto-sync
type mockGraphStore struct {
	nodes []*graph.Node
	edges []*graph.Edge
}

func (m *mockGraphStore) UpsertNode(ctx context.Context, n *graph.Node) error {
	m.nodes = append(m.nodes, n)
	return nil
}

func (m *mockGraphStore) UpsertEdge(ctx context.Context, e *graph.Edge) error {
	m.edges = append(m.edges, e)
	return nil
}

func (m *mockGraphStore) TouchNode(ctx context.Context, id string, now func() int64) error { return nil }
func (m *mockGraphStore) GetNode(ctx context.Context, id string, now func() int64) (*graph.Node, error) {
	return nil, nil
}
func (m *mockGraphStore) DeleteNode(ctx context.Context, id string) error { return nil }
func (m *mockGraphStore) ReinforceEdge(ctx context.Context, projectID, sourceID, targetID string, rel graph.RelationType, eta float64, now func() int64) error {
	return nil
}
func (m *mockGraphStore) SearchFTS(ctx context.Context, projectID, query string, limit int, now func() int64) ([]*graph.Node, error) {
	return nil, nil
}
func (m *mockGraphStore) GetActiveGraph(ctx context.Context, projectID string, minWeight float64, now func() int64) (*graph.Graph, error) {
	return &graph.Graph{Nodes: m.nodes, Edges: m.edges}, nil
}
func (m *mockGraphStore) RunMaintenanceDecay(ctx context.Context, minThreshold float64, maxStaleSecs int64, now func() int64) (int64, error) {
	return 0, nil
}
func (m *mockGraphStore) Close() error { return nil }

func TestSyncDigestToKnowledgeGraph(t *testing.T) {
	gs := &graph.SQLiteGraphStore{} // Test with nil or mock
	digest := &model.SocraticDigest{
		InitialHypothesis:  "Zero-Trust Service Mesh",
		DefendedInvariants: []string{"mTLS with SPIFFE/SPIRE identity"},
		ExposedBlindSpots:  []string{"Certificate rotation failure cascade"},
		HardenedThesis:     "Hardened SPIRE zero-trust gateway architecture",
		ResidualTensions:   []string{"Handshake latency vs Zero-Trust Strict Validation"},
	}

	// Should not panic with nil store
	err := SyncDigestToKnowledgeGraph(context.Background(), nil, "proj_1", "disc_1", digest)
	if err != nil {
		t.Errorf("expected no error with nil graph store, got %v", err)
	}
	_ = gs
}
