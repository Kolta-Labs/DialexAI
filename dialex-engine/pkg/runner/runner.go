// Package runner is the Go port of Kotlin's runner package (ApiAgentRunner, CliAgentRunner).
package runner

import (
	"context"

	"dialex/pkg/model"
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
