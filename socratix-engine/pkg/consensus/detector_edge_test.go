package consensus

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"unicode/utf8"

	"socratix/pkg/model"
	"socratix/pkg/runner"
)

func msg(p model.Provider, round int, content string) model.DebateMessage {
	return model.DebateMessage{AgentID: p, SeatID: string(p), AuthorDisplayName: string(p), Round: round, Content: content}
}

const longA = "We must choose the synchronous design because durability matters most here."
const longB = "However I disagree, that design cannot hit our latency target at all."

func TestParseTensionResponseVariants(t *testing.T) {
	good := `{"newTensions":[{"underlyingConflict":"A vs B","thesis":{"statement":"x"}}]}`
	for name, in := range map[string]string{
		"bare json":        good,
		"fenced json":      "```json\n" + good + "\n```",
		"fenced no lang":   "```\n" + good + "\n```",
		"prose wrapped":    "Here is the analysis:\n" + good + "\nHope that helps!",
		"leading/trailing": "  \n" + good + "\n  ",
	} {
		r, err := parseTensionResponse(in)
		if err != nil || len(r.NewTensions) != 1 {
			t.Errorf("%s: got %+v, %v", name, r, err)
		}
	}
	for name, in := range map[string]string{"empty": "", "garbage": "no json here", "truncated": `{"newTensions": [`} {
		if _, err := parseTensionResponse(in); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestMergeIgnoresUnknownStatusInsteadOfResolving(t *testing.T) {
	existing := []model.TensionPair{{ID: "t1", UnderlyingConflict: "X vs Y", Status: model.TensionStatusOpen}}
	resp := &tensionJSONResponse{}
	resp.ResolvedTensionUpdates = append(resp.ResolvedTensionUpdates, struct {
		ID                string  `json:"id"`
		Status            string  `json:"status"`
		Synthesis         *string `json:"synthesis,omitempty"`
		TradeOffRationale *string `json:"tradeOffRationale,omitempty"`
	}{ID: "t1", Status: "STILL_OPEN_LOL"})
	out := mergeTensionResults(resp, 2, existing)
	if out[0].Status != model.TensionStatusOpen || out[0].ResolvedInRound != nil {
		t.Fatalf("garbage status must not resolve a tension: %+v", out[0])
	}
}

func TestMergeRecordsResolutionRoundAndNormalizesStatus(t *testing.T) {
	syn := "hybrid"
	existing := []model.TensionPair{
		{ID: "t1", UnderlyingConflict: "A vs B", Status: model.TensionStatusOpen},
		{ID: "t2", UnderlyingConflict: "C vs D", Status: model.TensionStatusOpen},
	}
	resp, err := parseTensionResponse(`{"resolvedTensionUpdates":[
		{"id":"t1","status":" resolved ","synthesis":"hybrid"},
		{"id":"t2","status":"EXPLORED"},
		{"id":"nope","status":"RESOLVED"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	out := mergeTensionResults(resp, 3, existing)
	if len(out) != 2 {
		t.Fatalf("unknown id must not create tensions: %+v", out)
	}
	if out[0].Status != model.TensionStatusResolved || out[0].ResolvedInRound == nil || *out[0].ResolvedInRound != 3 || *out[0].Synthesis != syn {
		t.Errorf("t1 not resolved correctly: %+v", out[0])
	}
	if out[1].Status != model.TensionStatusExplored || out[1].ResolvedInRound != nil {
		t.Errorf("EXPLORED must not set a resolved round: %+v", out[1])
	}
}

func TestMergeNewTensionsValidationAndOrder(t *testing.T) {
	var parts []string
	for i := 0; i < 8; i++ {
		parts = append(parts, fmt.Sprintf(`{"underlyingConflict":"conflict %d","severity":%s,"thesis":{"statement":"s%d"},"antithesis":{"statement":"a%d"}}`, i, []string{"0", "1.5", "0.4"}[i%3], i, i))
	}
	parts = append(parts,
		`{"underlyingConflict":"","thesis":{"statement":"no label"}}`,      // skipped: empty conflict
		`{"underlyingConflict":"no statement","thesis":{"statement":" "}}`, // skipped: empty thesis
		`{"underlyingConflict":"CONFLICT 0","thesis":{"statement":"dup"}}`) // skipped: case-insensitive duplicate
	resp, err := parseTensionResponse(`{"newTensions":[` + strings.Join(parts, ",") + `]}`)
	if err != nil {
		t.Fatal(err)
	}
	for run := 0; run < 20; run++ { // map iteration order is random; output order must not be
		out := mergeTensionResults(resp, 2, nil)
		if len(out) != 8 {
			t.Fatalf("want 8 valid tensions, got %d", len(out))
		}
		for i, tp := range out {
			if want := fmt.Sprintf("conflict %d", i); tp.UnderlyingConflict != want {
				t.Fatalf("run %d: position %d = %q, want %q (non-deterministic order)", run, i, tp.UnderlyingConflict, want)
			}
			if tp.Status != model.TensionStatusOpen || tp.DetectedInRound != 2 {
				t.Fatalf("bad new tension: %+v", tp)
			}
			if tp.Severity <= 0 || tp.Severity > 1 {
				t.Fatalf("severity not clamped: %v", tp.Severity)
			}
		}
	}
}

func TestAnalyzeRoundFallsBackToHeuristicOnRunnerFailure(t *testing.T) {
	d := NewTensionDetector()
	turns := []model.DebateMessage{msg(model.ProviderAnthropic, 1, longA), msg(model.ProviderOpenAI, 1, longB)}
	agent := model.NewAgent(model.ProviderAnthropic, "m")
	for name, m := range map[string]*mockRunner{
		"runner error": {err: errors.New("boom")},
		"bad json":     {reply: runner.AgentReply{Content: "sorry, I can't"}},
	} {
		out, err := d.AnalyzeRound(context.Background(), m, agent, "topic", 1, turns, nil, "")
		if err != nil || len(out) != 1 {
			t.Errorf("%s: want 1 heuristic tension, got %+v, %v", name, out, err)
		}
	}
}

func TestAnalyzeRoundNeedsTwoRealTurns(t *testing.T) {
	d := NewTensionDetector()
	existing := []model.TensionPair{{ID: "keep", Status: model.TensionStatusOpen}}
	sys := msg(model.ProviderOpenAI, 1, longB)
	sys.IsSystem = true
	errMsg := msg(model.ProviderGemini, 1, longB)
	errMsg.IsError = true
	user := msg(model.ProviderOpenAI, 1, longB)
	user.IsUserComment = true
	blank := msg(model.ProviderOpenAI, 1, "   ")
	turns := []model.DebateMessage{msg(model.ProviderAnthropic, 1, longA), sys, errMsg, user, blank}

	out, _ := d.AnalyzeRound(context.Background(), &mockRunner{err: errors.New("must not be called")}, model.NewAgent(model.ProviderAnthropic, "m"), "t", 1, turns, existing, "")
	if len(out) != 1 || out[0].ID != "keep" {
		t.Fatalf("system/error/user/blank messages must not count as agent turns: %+v", out)
	}
}

func TestHeuristicNoTensionFromSameProviderOrShortTurns(t *testing.T) {
	d := NewTensionDetector()
	same := []model.DebateMessage{msg(model.ProviderAnthropic, 1, longA), msg(model.ProviderAnthropic, 1, longB)}
	if got := d.HeuristicAnalyzeRound("t", 1, same, nil); len(got) != 0 {
		t.Errorf("same-provider turns cannot form a cross-agent tension: %+v", got)
	}
	short := []model.DebateMessage{msg(model.ProviderAnthropic, 1, "no"), msg(model.ProviderOpenAI, 1, "however, yes")}
	if got := d.HeuristicAnalyzeRound("t", 1, short, nil); len(got) != 0 {
		t.Errorf("short turns must be ignored: %+v", got)
	}
}

func TestHeuristicStopsAtFourTensions(t *testing.T) {
	d := NewTensionDetector()
	var existing []model.TensionPair
	for i := 0; i < 4; i++ {
		existing = append(existing, model.TensionPair{ID: fmt.Sprintf("t%d", i), UnderlyingConflict: fmt.Sprintf("c%d", i), Status: model.TensionStatusOpen})
	}
	turns := []model.DebateMessage{msg(model.ProviderAnthropic, 2, longA), msg(model.ProviderOpenAI, 2, longB)}
	if got := d.HeuristicAnalyzeRound("t", 2, turns, existing); len(got) != 4 {
		t.Errorf("must not exceed the cap, got %d", len(got))
	}
}

func TestTruncateStatementKeepsValidUTF8(t *testing.T) {
	// 200 three-byte runes: a byte slice at 137 lands mid-rune.
	in := strings.Repeat("决", 200)
	got := truncateStatement(in)
	if !utf8.ValidString(got) {
		t.Fatalf("truncation split a multi-byte rune: %q", got)
	}
	if !strings.HasSuffix(got, "...") {
		t.Errorf("expected ellipsis: %q", got)
	}
	if got := truncateStatement("# Heading\n\n> quoted line that is long enough to be skipped entirely\nThis is the first real sentence of the turn."); got != "This is the first real sentence of the turn." {
		t.Errorf("should skip heading and blockquote: %q", got)
	}
}

type seqRunner struct {
	replies []string
	calls   int
}

func (s *seqRunner) Respond(ctx context.Context, agent model.Agent, topic, cc, ci string, tr []model.DebateMessage, mo string) (runner.AgentReply, error) {
	i := s.calls
	s.calls++
	if i >= len(s.replies) {
		i = len(s.replies) - 1
	}
	return runner.AgentReply{Content: s.replies[i]}, nil
}

func TestTensionDetectorRetriesBadJSONOnceThenRecordsFallback(t *testing.T) {
	msgs := []model.DebateMessage{{Content: "use postgres", Round: 1, AgentID: model.ProviderAnthropic}, {Content: "use sqlite", Round: 1, AgentID: model.ProviderOpenAI}}
	good := `{"newTensions":[],"resolvedTensionUpdates":[]}`

	d := NewTensionDetector()
	r := &seqRunner{replies: []string{"not json at all", good}}
	if _, err := d.AnalyzeRound(context.Background(), r, model.Agent{}, "t", 1, msgs, nil, ""); err != nil {
		t.Fatal(err)
	}
	if r.calls != 2 || d.FallbackRounds != 0 {
		t.Fatalf("a good retry must not count as fallback: calls=%d fallback=%d", r.calls, d.FallbackRounds)
	}

	d2 := NewTensionDetector()
	r2 := &seqRunner{replies: []string{"nope", "still nope"}}
	if _, err := d2.AnalyzeRound(context.Background(), r2, model.Agent{}, "t", 1, msgs, nil, ""); err != nil {
		t.Fatal(err)
	}
	if r2.calls != 2 || d2.FallbackRounds != 1 {
		t.Fatalf("two bad replies must fall back and be recorded: calls=%d fallback=%d", r2.calls, d2.FallbackRounds)
	}
}
