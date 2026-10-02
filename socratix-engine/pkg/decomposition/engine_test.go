package decomposition

import (
	"context"
	"testing"

	"socratix/pkg/model"
	"socratix/pkg/runner"
)

type mockRunner struct {
	reply runner.AgentReply
	err   error
}

func (m *mockRunner) Respond(
	ctx context.Context,
	agent model.Agent,
	topic string,
	commonContext string,
	commonInstructions string,
	transcript []model.DebateMessage,
	modelOverride string,
) (runner.AgentReply, error) {
	return m.reply, m.err
}

func TestHeuristicDecomposition(t *testing.T) {
	topic := "Should we rewrite the payment engine in Rust or stay in Go?"
	userContext := "Multi-region fintech service processing 50k req/sec."

	res := GenerateHeuristicDecomposition(topic, userContext)

	if res.Topic != topic {
		t.Fatalf("expected topic %q, got %q", topic, res.Topic)
	}
	if len(res.PerspectiveA.Axes) < 3 {
		t.Fatalf("expected at least 3 axes for Perspective A, got %d", len(res.PerspectiveA.Axes))
	}
	if len(res.PerspectiveB.Axes) < 3 {
		t.Fatalf("expected at least 3 axes for Perspective B, got %d", len(res.PerspectiveB.Axes))
	}

	for _, a := range res.PerspectiveA.Axes {
		if a.Title == "" || a.Thesis == "" || a.Antithesis == "" || len(a.KeyQuestions) == 0 {
			t.Fatalf("axis in Perspective A has empty fields: %+v", a)
		}
		if a.Weight <= 0 || a.Weight > 1.0 {
			t.Fatalf("axis weight out of bounds: %f", a.Weight)
		}
	}

	for _, b := range res.PerspectiveB.Axes {
		if b.Title == "" || b.Thesis == "" || b.Antithesis == "" || len(b.KeyQuestions) == 0 {
			t.Fatalf("axis in Perspective B has empty fields: %+v", b)
		}
	}
}

func TestDecomposeProblem_WithMockRunner(t *testing.T) {
	mockJSON := `{
		"topic": "Microservices vs Modular Monolith",
		"perspectiveA": {
			"id": "technical_structural",
			"name": "Structural Integrity",
			"lensDescription": "System bounds and network overhead.",
			"axes": [
				{
					"id": "axis_network",
					"title": "Network Boundaries & Serialization",
					"thesis": "In-process function calls provide zero-latency type safety.",
					"antithesis": "Network-separated services enforce hard isolation.",
					"keyQuestions": ["What is serialization penalty?"],
					"weight": 0.9,
					"selected": true
				}
			]
		},
		"perspectiveB": {
			"id": "product_operational",
			"name": "Team Autonomy",
			"lensDescription": "Deployment cadences.",
			"axes": [
				{
					"id": "axis_deploy",
					"title": "Independent Deployability",
					"thesis": "Teams should deploy independently without coordinating.",
					"antithesis": "Distributed deployments risk runtime version mismatch.",
					"keyQuestions": ["How to coordinate schema migrations?"],
					"weight": 0.85,
					"selected": true
				}
			]
		}
	}`

	mock := &mockRunner{
		reply: runner.AgentReply{Content: "```json\n" + mockJSON + "\n```"},
	}

	agent := model.Agent{
		Provider: model.ProviderAnthropic,
		Model:    "claude-sonnet-5",
	}

	res, err := DecomposeProblem(
		context.Background(),
		mock,
		agent,
		"Microservices vs Modular Monolith",
		"Team of 30 engineers",
		"",
	)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if res.Topic != "Microservices vs Modular Monolith" {
		t.Fatalf("unexpected topic: %s", res.Topic)
	}
	if len(res.PerspectiveA.Axes) != 1 || res.PerspectiveA.Axes[0].Title != "Network Boundaries & Serialization" {
		t.Fatalf("unexpected axes in perspective A: %+v", res.PerspectiveA.Axes)
	}
	if len(res.PerspectiveB.Axes) != 1 || res.PerspectiveB.Axes[0].Title != "Independent Deployability" {
		t.Fatalf("unexpected axes in perspective B: %+v", res.PerspectiveB.Axes)
	}
}
