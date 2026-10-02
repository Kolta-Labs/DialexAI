package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"socratix/pkg/consensus"
	"socratix/pkg/model"
)

// runConsensus evaluates consensus on a debate transcript.
// Usage: socratix consensus <transcript.json> [--config cfg.json] [--round N]
//
// transcript.json is a JSON array of DebateMessage.
// If no config is given, derives agents from distinct seatId values in the transcript.
func runConsensus(args []string) {
	fs := flag.NewFlagSet("consensus", flag.ExitOnError)
	configFlag := fs.String("config", "", "debate config JSON file (optional; derives agents from transcript if omitted)")
	roundFlag := fs.Int("round", 0, "evaluate consensus at this round (optional; defaults to max round in transcript)")
	_ = fs.Parse(args)

	positional := fs.Args()
	if len(positional) < 1 {
		fail("usage: socratix consensus <transcript.json> [--config cfg.json] [--round N]")
	}

	transcriptFile := positional[0]

	// Load transcript
	transcriptData, err := os.ReadFile(transcriptFile)
	if err != nil {
		fail("failed to read transcript file: %v", err)
	}

	var transcript []model.DebateMessage
	if err := json.Unmarshal(transcriptData, &transcript); err != nil {
		fail("failed to parse transcript JSON: %v", err)
	}

	// Load or derive config
	var cfg model.DebateConfig
	if *configFlag != "" {
		configData, err := os.ReadFile(*configFlag)
		if err != nil {
			fail("failed to read config file: %v", err)
		}
		if err := json.Unmarshal(configData, &cfg); err != nil {
			fail("failed to parse config JSON: %v", err)
		}
	} else {
		// Derive agents from distinct seatId values in transcript
		cfg = deriveConfigFromTranscript(transcript)
	}

	// Determine round
	currentRound := *roundFlag
	if currentRound <= 0 {
		// Default to max round among non-error/non-user/non-system messages
		for _, msg := range transcript {
			if !msg.IsError && !msg.IsUserComment && !msg.IsSystem {
				if msg.Round > currentRound {
					currentRound = msg.Round
				}
			}
		}
		if currentRound == 0 {
			currentRound = 1 // fallback
		}
	}

	// Evaluate consensus
	result := consensus.Evaluate(currentRound, transcript, cfg)

	// Print result as indented JSON
	resultJSON, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		fail("failed to marshal result: %v", err)
	}
	fmt.Println(string(resultJSON))
}

// deriveConfigFromTranscript builds a minimal config from the transcript by extracting unique seat IDs.
// Creates a primary agent and adds others to secondary/tertiary/quaternary based on distinct seat IDs.
func deriveConfigFromTranscript(transcript []model.DebateMessage) model.DebateConfig {
	seatMap := make(map[string]bool)
	var seatOrder []string

	for _, msg := range transcript {
		if msg.SeatID != "" && !seatMap[msg.SeatID] {
			seatMap[msg.SeatID] = true
			seatOrder = append(seatOrder, msg.SeatID)
		}
	}

	cfg := model.DebateConfig{
		Topic:     "Debate",
		RoundMode: model.RoundModeFixed,
		MaxRounds: 2,
		Primary:   model.NewAgent(model.ProviderAnthropic, ""),
	}

	// Set primary to first seat ID if available
	if len(seatOrder) > 0 {
		cfg.Primary.ID = seatOrder[0]
	}

	// Add other seats to secondary, tertiary, quaternary
	for i := 1; i < len(seatOrder); i++ {
		a := model.NewAgent(model.ProviderAnthropic, "")
		a.ID = seatOrder[i]
		if i == 1 {
			cfg.Secondary = &a
		} else if i == 2 {
			cfg.Tertiary = &a
		} else if i == 3 {
			cfg.Quaternary = &a
		}
	}

	return cfg
}
