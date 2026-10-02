// Package runner runs an agent's turn through a direct provider API (ApiAgentRunner) or a local CLI (CliAgentRunner).
package runner

import (
	"context"

	"socratix/pkg/model"
)

// AgentReply is one turn's reply. Token counts are best-effort — API providers report them
// in the response, CLI subprocesses generally don't expose them at all, so both are nil
// there.
type AgentReply struct {
	Content          string
	TokensIn         *int
	TokensOut        *int
	TokensCached     *int
	GroundingSources []string
}

// AgentRunner produces one turn's reply for an agent given the debate so far.
type AgentRunner interface {
	// ModelOverride swaps just the model for this one call (e.g. compaction summaries use a
	// cheap model regardless of what the user picked for the agent). Empty string = use
	// agent.Model / the configured CLI command as-is.
	Respond(
		ctx context.Context,
		agent model.Agent,
		topic string,
		commonContext string,
		commonInstructions string,
		transcript []model.DebateMessage,
		modelOverride string,
	) (AgentReply, error)
}

func intPtr(i int) *int { return &i }

// isOwnTurn reports whether a transcript message was written by this agent seat. Seats are
// matched by ID so several personas on one provider (one API key) are told apart; the
// provider is only the fallback for old transcripts that carry no seat ID.
func isOwnTurn(m model.DebateMessage, a model.Agent) bool {
	if m.SeatID != "" && a.ID != "" {
		return m.SeatID == a.ID
	}
	return m.AgentID == a.Provider
}

// speakerLabel is the name a transcript line is attributed to: the seat's display name when
// known, else the provider.
func speakerLabel(m model.DebateMessage) string {
	if m.AuthorDisplayName != "" {
		return m.AuthorDisplayName
	}
	return string(m.AgentID)
}
