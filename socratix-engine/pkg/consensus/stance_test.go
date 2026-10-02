package consensus

import (
	"testing"

	"socratix/pkg/model"
)

func TestIsTurnAgreed_PrefixRegex(t *testing.T) {
	tests := []struct {
		name    string
		content string
		want    bool
	}{
		{"AGREED with colon", "AGREED:", true},
		{"AGREED with colon and text", "AGREED: Absolutely concur with previous points.", true},
		{"H2 markdown Agreed", "## Agreed", true},
		{"H3 markdown with AGREED", "### AGREED: Makes complete sense.", true},
		{"Bold AGREED markdown", "**AGREED:** I concur.", true},
		{"Consensus Reached bold", "**Consensus Reached:** Core objections addressed.", true},
		{"lowercase i agree", "i agree: moving forward with plan.", true},
		{"Blockquote AGREED", "> AGREED: nothing more to add.", true},
		{"Bracket AGREED", "[AGREED] Fully aligned.", true},
		{"CONCUR", "CONCUR: Valid argument.", true},
		{"UNANIMOUS AGREEMENT", "UNANIMOUS AGREEMENT: All issues resolved.", true},

		{"negation: I do not agree", "I do not agree with the SME proposal.", false},
		{"negation: I cannot agree", "I cannot agree at this stage.", false},
		{"disagree prefix", "disagree: this overlooks major latency pitfalls.", false},
		{"Turn 1", "Turn 1: Here is my thesis.", false},
		{"AGREED but then negation", "AGREED that X is good, but I do not agree with Y.", false},
		{"empty", "", false},
		{"whitespace", "   ", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := IsTurnAgreed(tt.content)
			if got != tt.want {
				t.Errorf("IsTurnAgreed(%q) = %v, want %v", tt.content, got, tt.want)
			}
		})
	}
}

func TestIsTurnAgreed_Concessions(t *testing.T) {
	tests := []struct {
		name    string
		content string
		want    bool
	}{
		{"concede to position", "After reviewing the trade-off matrix, I concede to the position of Agent 1.", true},
		{"nothing left to contest", "There is nothing left to contest in the design. The benchmarks speak for themselves.", true},
		{"align with council", "I align with the council on the proposed architecture and have no further substantive disagreement.", true},
		{"fully concur", "I fully concur with the points raised by Gemini.", true},
		{"no remaining objections", "We have evaluated the edge cases and there are no remaining objections on our side.", true},

		{"negated concede", "I do not concede to this approach; the risks are too great.", false},
		{"still to contest", "There is still much to contest before we finalize.", false},
		{"cannot agree", "I cannot agree with the proposed latency budget.", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := IsTurnAgreed(tt.content)
			if got != tt.want {
				t.Errorf("IsTurnAgreed(%q) = %v, want %v", tt.content, got, tt.want)
			}
		})
	}
}

func TestEvaluateConsensus_UnanimousMode(t *testing.T) {
	agent1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	agent2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   agent1,
		Secondary: &agent2,
		Consensus: &model.ConsensusConfig{
			Mode:                     model.ConsensusModeUnanimous,
			MinRoundsBeforeExit:      2,
			Strategy:                 model.ConsensusStrategyPrefixAndPattern,
			AllowMidRoundTermination: true,
		},
	}

	t.Run("Round 1: Both agree but minRounds=2, NotReady", func(t *testing.T) {
		transcript := []model.DebateMessage{
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Yes", Round: 1},
		}

		result := Evaluate(1, transcript, config)
		if result.NotReady == "" {
			t.Errorf("Expected NotReady at Round 1, got Achieved=%v Ongoing=%v", result.Achieved, result.Ongoing)
		}
	})

	t.Run("Round 2: Only 1 agrees, Ongoing", func(t *testing.T) {
		transcript := []model.DebateMessage{
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Still agree", Round: 2},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "Wait, I have a doubt", Round: 2},
		}

		result := Evaluate(2, transcript, config)
		if !result.Ongoing || result.AgreedCount != 1 {
			t.Errorf("Round 2 partial: want Ongoing with AgreedCount=1, got Achieved=%v Ongoing=%v AgreedCount=%d",
				result.Achieved, result.Ongoing, result.AgreedCount)
		}
	})

	t.Run("Round 2: Both agree, Achieved", func(t *testing.T) {
		transcript := []model.DebateMessage{
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Still agree", Round: 2},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Solved", Round: 2},
		}

		result := Evaluate(2, transcript, config)
		if !result.Achieved || len(result.AgreedSeats) != 2 || result.Ratio != 1.0 {
			t.Errorf("Round 2 full: want Achieved with 2 agreed, got Achieved=%v AgreedSeats=%d Ratio=%.2f",
				result.Achieved, len(result.AgreedSeats), result.Ratio)
		}
	})
}

func TestEvaluateConsensus_SupermajorityMode(t *testing.T) {
	a1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	a2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}
	a3 := model.Agent{ID: "seat_3", Provider: model.ProviderOpenAI}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   a1,
		Secondary: &a2,
		Tertiary:  &a3,
		Consensus: &model.ConsensusConfig{
			Mode:                     model.ConsensusModeSupermajority,
			ConsensusThreshold:       0.66,
			MinRoundsBeforeExit:      1,
			Strategy:                 model.ConsensusStrategyPrefixAndPattern,
			AllowMidRoundTermination: true,
		},
	}

	t.Run("2 of 3 agree (66.6% >= 66%), Achieved", func(t *testing.T) {
		transcript := []model.DebateMessage{
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_3", Provider: model.ProviderOpenAI, Content: "Disagree strongly", Round: 1},
		}

		result := Evaluate(1, transcript, config)
		if !result.Achieved || len(result.AgreedSeats) != 2 {
			t.Errorf("Supermajority: want Achieved with 2 of 3, got Achieved=%v AgreedSeats=%d",
				result.Achieved, len(result.AgreedSeats))
		}
	})
}

func TestEvaluateConsensus_DisabledMode(t *testing.T) {
	agent1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	agent2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   agent1,
		Secondary: &agent2,
		Consensus: &model.ConsensusConfig{
			Mode: model.ConsensusModeDisabled,
		},
	}

	transcript := []model.DebateMessage{
		{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
		{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Yes", Round: 1},
	}

	result := Evaluate(1, transcript, config)
	if result.NotReady == "" || result.Achieved {
		t.Errorf("Disabled mode: want NotReady, got Achieved=%v NotReady=%q", result.Achieved, result.NotReady)
	}
}

func TestEvaluateConsensus_OngoingWhenAgentHasntSpoken(t *testing.T) {
	agent1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	agent2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   agent1,
		Secondary: &agent2,
		Consensus: &model.ConsensusConfig{
			Mode:                model.ConsensusModeUnanimous,
			MinRoundsBeforeExit: 1,
			Strategy:            model.ConsensusStrategyPrefixAndPattern,
		},
	}

	transcript := []model.DebateMessage{
		{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
		// agent2 hasn't spoken yet
	}

	result := Evaluate(1, transcript, config)
	if !result.Ongoing || result.AgreedCount != 1 || result.TotalCount != 2 {
		t.Errorf("Ongoing: want Ongoing=true AgreedCount=1 TotalCount=2, got Ongoing=%v AgreedCount=%d TotalCount=%d",
			result.Ongoing, result.AgreedCount, result.TotalCount)
	}
}

func TestEvaluateConsensus_UserCommentResets(t *testing.T) {
	agent1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	agent2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   agent1,
		Secondary: &agent2,
		Consensus: &model.ConsensusConfig{
			Mode:                model.ConsensusModeUnanimous,
			MinRoundsBeforeExit: 1,
			Strategy:            model.ConsensusStrategyPrefixAndPattern,
		},
	}

	// Both agree in round 1, then user comment, then agent1 disagrees in round 2
	// Only post-user-comment turns should be evaluated
	transcript := []model.DebateMessage{
		{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
		{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Yes", Round: 1},
		{SeatID: "", Content: "User's new concern", IsUserComment: true, Round: 1},
		{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "Actually, I disagree now", Round: 2},
		{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Still agree", Round: 2},
	}

	result := Evaluate(2, transcript, config)
	// Only the post-user-comment turns count: agent1 disagrees, agent2 agrees
	// So should be Ongoing, not Achieved
	if result.Achieved {
		t.Errorf("User comment reset: want Ongoing (1 agreed), got Achieved=%v AgreedCount=%d",
			result.Achieved, result.AgreedCount)
	}
}

func TestEvaluateConsensus_SimpleMajorityMode(t *testing.T) {
	a1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	a2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}
	a3 := model.Agent{ID: "seat_3", Provider: model.ProviderOpenAI}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   a1,
		Secondary: &a2,
		Tertiary:  &a3,
		Consensus: &model.ConsensusConfig{
			Mode:                model.ConsensusModeSimpleMajority,
			MinRoundsBeforeExit: 1,
			Strategy:            model.ConsensusStrategyPrefixAndPattern,
		},
	}

	t.Run("2 of 3 (66.6% > 50%), Achieved", func(t *testing.T) {
		transcript := []model.DebateMessage{
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_3", Provider: model.ProviderOpenAI, Content: "No", Round: 1},
		}

		result := Evaluate(1, transcript, config)
		if !result.Achieved {
			t.Errorf("Simple majority 2/3: want Achieved, got Achieved=%v", result.Achieved)
		}
	})

	t.Run("1 of 3 (33% not > 50%), Ongoing", func(t *testing.T) {
		transcript := []model.DebateMessage{
			{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
			{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "No", Round: 1},
			{SeatID: "seat_3", Provider: model.ProviderOpenAI, Content: "No", Round: 1},
		}

		result := Evaluate(1, transcript, config)
		if result.Achieved {
			t.Errorf("Simple majority 1/3: want Ongoing, got Achieved=%v", result.Achieved)
		}
	})
}

func TestEvaluateConsensus_ConsensusTolerance(t *testing.T) {
	agent1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	agent2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}

	// UNANIMOUS mode with ConsensusTolerance in [0.5, 0.999] should degrade to SUPERMAJORITY
	config := model.DebateConfig{
		Topic:              "test",
		Primary:            agent1,
		Secondary:          &agent2,
		ConsensusTolerance: 0.75, // triggers UNANIMOUS -> SUPERMAJORITY downgrade
		Consensus: &model.ConsensusConfig{
			Mode:                model.ConsensusModeUnanimous,
			MinRoundsBeforeExit: 1,
			Strategy:            model.ConsensusStrategyPrefixAndPattern,
		},
	}

	// With tolerance 0.75 and SUPERMAJORITY, 1 of 2 (50%) should NOT be achieved
	transcript := []model.DebateMessage{
		{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
		{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "No", Round: 1},
	}

	result := Evaluate(1, transcript, config)
	if result.Achieved {
		t.Errorf("Tolerance downgrade: want Ongoing (1 of 2 < 0.75), got Achieved=%v Ratio=%.2f", result.Achieved, result.Ratio)
	}
}

func TestEvaluateConsensus_Concessions(t *testing.T) {
	agent1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	agent2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   agent1,
		Secondary: &agent2,
		Consensus: &model.ConsensusConfig{
			Mode:                model.ConsensusModeUnanimous,
			MinRoundsBeforeExit: 1,
			Strategy:            model.ConsensusStrategyHeuristicHybrid,
		},
	}

	// agent1 says AGREED, agent2 says concession phrase
	transcript := []model.DebateMessage{
		{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
		{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "I fully concur with the points raised by Claude.", Round: 1},
	}

	result := Evaluate(1, transcript, config)
	if !result.Achieved || len(result.AgreedSeats) != 2 {
		t.Errorf("Concession phrases: want Achieved with 2 agreed, got Achieved=%v AgreedSeats=%d",
			result.Achieved, len(result.AgreedSeats))
	}
}

func TestEvaluateConsensus_NegatedConcession(t *testing.T) {
	agent1 := model.Agent{ID: "seat_1", Provider: model.ProviderAnthropic}
	agent2 := model.Agent{ID: "seat_2", Provider: model.ProviderGemini}

	config := model.DebateConfig{
		Topic:     "test",
		Primary:   agent1,
		Secondary: &agent2,
		Consensus: &model.ConsensusConfig{
			Mode:                model.ConsensusModeUnanimous,
			MinRoundsBeforeExit: 1,
			Strategy:            model.ConsensusStrategyHeuristicHybrid,
		},
	}

	// agent2 says a concession phrase but negates it
	transcript := []model.DebateMessage{
		{SeatID: "seat_1", Provider: model.ProviderAnthropic, Content: "AGREED: Yes", Round: 1},
		{SeatID: "seat_2", Provider: model.ProviderGemini, Content: "I do not concede to this approach; the risks are too great.", Round: 1},
	}

	result := Evaluate(1, transcript, config)
	if result.Achieved || result.AgreedCount != 1 {
		t.Errorf("Negated concession: want Ongoing (1 agreed), got Achieved=%v AgreedCount=%d",
			result.Achieved, result.AgreedCount)
	}
}
