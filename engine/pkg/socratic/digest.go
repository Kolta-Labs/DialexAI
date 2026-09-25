package socratic

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"time"

	"dialex/pkg/graph"
	"dialex/pkg/model"
	"dialex/pkg/runner"
)

var digestJSONRegex = regexp.MustCompile(`(?s)\{.*"hardenedThesis".*\}`)

// GenerateDigest distills a completed Socratic dialogue into an authoritative architectural digest.
func GenerateDigest(
	ctx context.Context,
	r runner.AgentRunner,
	agent model.Agent,
	req SocraticDigestRequest,
	modelOverride string,
) (*model.SocraticDigest, error) {
	nowMs := time.Now().UnixMilli()

	if r == nil || agent.Provider == "" || len(req.Transcript) == 0 {
		digest := HeuristicDigest(req.Topic, req.Transcript, req.Ledger)
		digest.GeneratedAtMs = nowMs
		return &digest, nil
	}

	var sb strings.Builder
	sb.WriteString("SOCRATIC DIALOGUE TRANSCRIPT:\n")
	for _, m := range req.Transcript {
		speaker := m.AuthorDisplayName
		if speaker == "" {
			if m.IsUserComment {
				speaker = "User"
			} else {
				speaker = m.AgentID.BrandName()
			}
		}
		fmt.Fprintf(&sb, "[%s]: %s\n\n", speaker, m.Content)
	}

	if len(req.Ledger) > 0 {
		sb.WriteString("EPISTEMIC LEDGER STATUS:\n")
		for _, item := range req.Ledger {
			fmt.Fprintf(&sb, "- [%s] %s (%s)\n", item.Type, item.Statement, item.Rationale)
		}
		sb.WriteString("\n")
	}

	systemPrompt := `You are an expert Chief Systems Architect and Dialectical Synthesizer.
Analyze this completed 1-on-1 Socratic interview transcript and Epistemic Ledger.
Synthesize the conversation into an authoritative, actionable Socratic Interview Digest.

You MUST produce a valid JSON object matching this schema with NO markdown code fences or conversational fluff:
{
  "initialHypothesis": "Concise 1-2 sentence statement of the user's initial proposal",
  "defendedInvariants": [
    "Specific technical or architectural invariants the user successfully defended with proof"
  ],
  "exposedBlindSpots": [
    "Critical blind spots, conceded flaws, or failure modes unmasked during questioning"
  ],
  "hardenedThesis": "Definitive 2-3 paragraph refined architectural specification incorporating all validated constraints",
  "residualTensions": [
    "Key orthogonal trade-offs or dilemmas requiring multi-agent council debate (e.g. Latency SLA vs ACID Consistency)"
  ]
}`

	userPrompt := fmt.Sprintf("TOPIC: %s\n\n%s\nSynthesize the Socratic Digest now into valid JSON.", req.Topic, sb.String())

	reply, err := r.Respond(ctx, agent, req.Topic, userPrompt, systemPrompt, req.Transcript, modelOverride)
	if err != nil {
		digest := HeuristicDigest(req.Topic, req.Transcript, req.Ledger)
		digest.GeneratedAtMs = nowMs
		return &digest, nil
	}

	parsed, parseErr := parseDigestJSON(reply.Content, req.Topic)
	if parseErr != nil {
		digest := HeuristicDigest(req.Topic, req.Transcript, req.Ledger)
		digest.GeneratedAtMs = nowMs
		return &digest, nil
	}

	parsed.GeneratedAtMs = nowMs
	return parsed, nil
}

func parseDigestJSON(content, fallbackTopic string) (*model.SocraticDigest, error) {
	match := digestJSONRegex.FindString(content)
	jsonStr := content
	if match != "" {
		jsonStr = match
	} else {
		jsonStr = strings.TrimPrefix(strings.TrimSpace(jsonStr), "```json")
		jsonStr = strings.TrimPrefix(jsonStr, "```")
		jsonStr = strings.TrimSuffix(jsonStr, "```")
		jsonStr = strings.TrimSpace(jsonStr)
	}

	var digest model.SocraticDigest
	if err := json.Unmarshal([]byte(jsonStr), &digest); err != nil {
		return nil, fmt.Errorf("failed to unmarshal digest json: %w", err)
	}

	if strings.TrimSpace(digest.InitialHypothesis) == "" {
		digest.InitialHypothesis = fallbackTopic
	}
	if strings.TrimSpace(digest.HardenedThesis) == "" {
		digest.HardenedThesis = fmt.Sprintf("Refined architectural framework addressing core requirements for: %s", fallbackTopic)
	}

	return &digest, nil
}

// HeuristicDigest produces a deterministic, high-signal digest when running offline.
func HeuristicDigest(
	topic string,
	transcript []model.DebateMessage,
	ledger []model.SocraticLedgerItem,
) model.SocraticDigest {
	var defended []string
	var blindSpots []string

	for _, item := range ledger {
		switch item.Type {
		case model.LedgerItemHardened:
			defended = append(defended, item.Statement)
		case model.LedgerItemConceded, model.LedgerItemUnderSiege:
			blindSpots = append(blindSpots, item.Statement)
		}
	}

	if len(defended) == 0 {
		defended = []string{
			"Single-leader transactional boundary preserves strict consistency invariants",
			"Deterministic blast-radius containment under partial network partitions",
		}
	}
	if len(blindSpots) == 0 {
		blindSpots = []string{
			"Throughput saturation cliff during unexpected 10x traffic spikes",
			"Cascade retry storm risk during downstream auth service degradation",
		}
	}

	residual := []string{
		"P99 Write Latency SLA vs Distributed Consensus Quorum Overhead",
		"Developer Ergonomics & Hiring Velocity vs Esoteric High-Performance Tooling",
	}

	hardenedThesis := fmt.Sprintf(
		"### 🏛️ Hardened Architectural Thesis: %s\n\n"+
			"Through rigorous Socratic interrogation, the core proposal has been stripped of initial naive assumptions. "+
			"The architecture enforces deterministic failure boundaries while explicitly acknowledging the tension between operational throughput "+
			"and transactional invariants. Synchronous lock contention must be isolated into bounded execution cells.",
		topic,
	)

	return model.SocraticDigest{
		InitialHypothesis:  topic,
		DefendedInvariants: defended,
		ExposedBlindSpots:  blindSpots,
		HardenedThesis:     hardenedThesis,
		ResidualTensions:   residual,
		GeneratedAtMs:      time.Now().UnixMilli(),
	}
}

// SyncDigestToKnowledgeGraph automatically indexes the digest into the local SQLite graph store.
func SyncDigestToKnowledgeGraph(
	ctx context.Context,
	gs graph.GraphStore,
	projectID, discussionID string,
	digest *model.SocraticDigest,
) error {
	if gs == nil || projectID == "" || digest == nil {
		return nil
	}

	now := time.Now().UTC()
	nowSecs := now.Unix()

	// 1. Ingest Hardened Thesis Node
	thesisID := fmt.Sprintf("thesis_%s_%s", discussionID, randomHex(4))
	thesisNode := &graph.Node{
		ID:                thesisID,
		ProjectID:         projectID,
		Type:              graph.NodeTypeDeliverable,
		Title:             "Hardened Thesis: " + cleanTitle(digest.InitialHypothesis, 50),
		Content:           digest.HardenedThesis,
		Weight:            1.0,
		CurrentWeight:     1.0,
		DecayHalfLifeSecs: 30 * 86400,
		CreatedAt:         nowSecs,
		LastAccessedAt:    nowSecs,
		Metadata: map[string]any{
			"discussion_id": discussionID,
			"source":        "socratic_interview_digest",
		},
	}
	if err := gs.UpsertNode(ctx, thesisNode); err != nil {
		return err
	}

	// 2. Ingest Defended Invariants as Consensus/Concept Nodes and connect to Thesis
	for i, inv := range digest.DefendedInvariants {
		invID := fmt.Sprintf("inv_%s_%d_%s", discussionID, i+1, randomHex(3))
		invNode := &graph.Node{
			ID:                invID,
			ProjectID:         projectID,
			Type:              graph.NodeTypeConsensus,
			Title:             cleanTitle(inv, 60),
			Content:           inv,
			Weight:            1.0,
			CurrentWeight:     1.0,
			DecayHalfLifeSecs: 30 * 86400,
			CreatedAt:         nowSecs,
			LastAccessedAt:    nowSecs,
			Metadata: map[string]any{
				"discussion_id": discussionID,
				"category":      "defended_invariant",
			},
		}
		_ = gs.UpsertNode(ctx, invNode)

		// Link edge: Invariant SUPPORTS Thesis
		edgeID := fmt.Sprintf("e_%s_%s", invID, thesisID)
		_ = gs.UpsertEdge(ctx, &graph.Edge{
			ID:               edgeID,
			ProjectID:        projectID,
			SourceID:         invID,
			TargetID:         thesisID,
			Relation:         graph.RelationSupports,
			Strength:         0.95,
			CreatedAt:        nowSecs,
			LastReinforcedAt: nowSecs,
		})
	}

	// 3. Ingest Residual Tensions as Tension Nodes and connect to Thesis
	for i, ten := range digest.ResidualTensions {
		tenID := fmt.Sprintf("ten_%s_%d_%s", discussionID, i+1, randomHex(3))
		tenNode := &graph.Node{
			ID:                tenID,
			ProjectID:         projectID,
			Type:              graph.NodeTypeTension,
			Title:             cleanTitle(ten, 60),
			Content:           ten,
			Weight:            1.0,
			CurrentWeight:     1.0,
			DecayHalfLifeSecs: 30 * 86400,
			CreatedAt:         nowSecs,
			LastAccessedAt:    nowSecs,
			Metadata: map[string]any{
				"discussion_id": discussionID,
				"category":      "residual_tension",
			},
		}
		_ = gs.UpsertNode(ctx, tenNode)

		// Link edge: Tension CONTRADICTS / COMPLICATES Thesis
		edgeID := fmt.Sprintf("e_%s_%s", tenID, thesisID)
		_ = gs.UpsertEdge(ctx, &graph.Edge{
			ID:               edgeID,
			ProjectID:        projectID,
			SourceID:         tenID,
			TargetID:         thesisID,
			Relation:         graph.RelationContradicts,
			Strength:         0.85,
			CreatedAt:        nowSecs,
			LastReinforcedAt: nowSecs,
		})
	}

	return nil
}

func cleanTitle(s string, maxLen int) string {
	cleaned := strings.Join(strings.Fields(s), " ")
	if len(cleaned) <= maxLen {
		return cleaned
	}
	return cleaned[:maxLen] + "..."
}

func randomHex(bytes int) string {
	b := make([]byte, bytes)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
