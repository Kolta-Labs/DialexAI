package retrieval

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"dialex/pkg/graph"
	"dialex/pkg/model"
)

func TestCleanSnippetKeepsValidUTF8AndCollapsesWhitespace(t *testing.T) {
	if got := cleanSnippet("a  b\n\n c\t d", 100); got != "a b c d" {
		t.Errorf("whitespace not collapsed: %q", got)
	}
	got := cleanSnippet(strings.Repeat("决", 200), 280) // byte cut at 280 lands mid-rune
	if !utf8.ValidString(got) {
		t.Fatalf("snippet split a multi-byte rune: %q", got)
	}
	if !strings.HasSuffix(got, "...") {
		t.Errorf("truncated snippet needs an ellipsis: %q", got)
	}
}

func TestSplitIntoPassages(t *testing.T) {
	if got := splitIntoPassages("  \n\n \n\n", 100); len(got) != 0 {
		t.Errorf("blank text must yield no passages: %q", got)
	}
	text := strings.Join([]string{strings.Repeat("a", 60), strings.Repeat("b", 60), strings.Repeat("c", 60)}, "\n\n")
	got := splitIntoPassages(text, 100)
	if len(got) != 3 {
		t.Fatalf("each 60-char paragraph exceeds the 100 budget when combined: got %d passages", len(got))
	}
	small := splitIntoPassages("x\n\ny\n\nz", 100)
	if len(small) != 1 || small[0] != "x\n\ny\n\nz" {
		t.Errorf("small paragraphs should pack together: %q", small)
	}
}

func TestTokenizeDropsStopWordsAndPunctuation(t *testing.T) {
	got := tokenize(`The "Raft" protocol, and (Paxos)! is it?`)
	want := map[string]bool{"raft": true, "protocol": true, "paxos": true}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for _, tok := range got {
		if !want[tok] {
			t.Errorf("unexpected token %q in %v", tok, got)
		}
	}
}

func TestComputePassageScore(t *testing.T) {
	if computePassageScore("anything", nil) != 0 {
		t.Error("no query tokens must score 0")
	}
	if got := computePassageScore("raft and paxos", []string{"raft", "paxos"}); got != 1.0 {
		t.Errorf("full match = %v, want 1.0", got)
	}
	if got := computePassageScore("only raft", []string{"raft", "paxos"}); got < 0.5 || got > 0.6 {
		t.Errorf("half match = %v", got)
	}
	if got := computePassageScore("nothing relevant", []string{"raft"}); got != 0.05 {
		t.Errorf("no hit = %v, want the 0.05 floor", got)
	}
}

func TestFormatEvidenceContext(t *testing.T) {
	if FormatEvidenceContext(2, nil) != "" {
		t.Error("no items must produce no context block")
	}
	out := FormatEvidenceContext(3, []model.EvidenceItem{{AttributionBadge: "[Evidence: File \"a.md\"]", Snippet: "s", Score: 0.9}})
	for _, want := range []string{"ROUND 3", "[Evidence: File \"a.md\"]", "0.90", "MANDATE"} {
		if !strings.Contains(out, want) {
			t.Errorf("missing %q in %q", want, out)
		}
	}
}

func fileWith(id, name, text string) model.AttachedFile {
	return model.AttachedFile{ID: id, Name: name, Content: text}
}

func msgs(content string) []model.DebateMessage {
	return []model.DebateMessage{{Round: 1, Content: content}}
}

func TestRetrieveCapsAtThreeAndIsDeterministic(t *testing.T) {
	var files []model.AttachedFile
	for i := 0; i < 8; i++ { // identical content => identical scores => tie-break decides
		files = append(files, fileWith(fmt.Sprintf("f%d", i), fmt.Sprintf("doc%d.md", i), "Kafka partitions guarantee ordering within a partition only."))
	}
	var first []string
	for run := 0; run < 15; run++ {
		ev, err := NewDynamicRetriever(nil).RetrieveForRound(context.Background(), nil, model.Agent{}, "", "Kafka ordering",
			1, msgs("Kafka partitions guarantee ordering within a partition."), files, nil)
		if err != nil {
			t.Fatal(err)
		}
		if len(ev.Items) != 3 {
			t.Fatalf("want top-3 cap, got %d", len(ev.Items))
		}
		var ids []string
		for _, it := range ev.Items {
			ids = append(ids, it.SourceID)
		}
		if run == 0 {
			first = ids
		} else if strings.Join(ids, ",") != strings.Join(first, ",") {
			t.Fatalf("run %d selected %v, run 0 selected %v: tie-breaking is nondeterministic", run, ids, first)
		}
	}
}

func TestRetrieveSkipsBlankFilesAndAlreadySeenPassages(t *testing.T) {
	files := []model.AttachedFile{fileWith("blank", "blank.md", "   "), fileWith("f1", "a.md", "Raft elects a leader using randomized election timeouts.")}
	r := NewDynamicRetriever(nil)
	ev, _ := r.RetrieveForRound(context.Background(), nil, model.Agent{}, "", "Raft", 1, msgs("Raft leader election timeouts matter."), files, nil)
	if len(ev.Items) != 1 || ev.Items[0].SourceID != "f1_p0" {
		t.Fatalf("got %+v", ev.Items)
	}
	again, _ := r.RetrieveForRound(context.Background(), nil, model.Agent{}, "", "Raft", 2, msgs("Raft leader election timeouts matter."), files, []model.RoundEvidence{ev})
	if len(again.Items) != 0 {
		t.Fatalf("passage already cited in an earlier round must not repeat: %+v", again.Items)
	}
	if again.Round != 3 {
		t.Errorf("evidence is for the NEXT round, want 3 got %d", again.Round)
	}
}

func TestRetrieveNoQueriesReturnsEmptyEvidenceForNextRound(t *testing.T) {
	ev, err := NewDynamicRetriever(nil).RetrieveForRound(context.Background(), nil, model.Agent{}, "", "", 4, nil, nil, nil)
	if err != nil || len(ev.Items) != 0 || ev.Round != 5 {
		t.Fatalf("got %+v, %v", ev, err)
	}
}

// A node that has decayed to ~0 was last touched long ago; it must not outrank an active node
// just because a zero weight was replaced by a generous fallback score.
func TestDecayedGraphNodeDoesNotOutrankActiveNode(t *testing.T) {
	store, err := graph.OpenSQLite(":memory:")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	ctx, now := context.Background(), time.Now().UTC()
	old := now.Add(-400 * 24 * time.Hour)
	store.UpsertNode(ctx, &graph.Node{ID: "stale", ProjectID: "p", Type: graph.NodeTypeConcept, Title: "Stale", Content: "kubernetes scheduling affinity rules",
		Weight: 1, DecayHalfLifeSecs: 86400, CreatedAt: old.Unix(), LastAccessedAt: old.Unix()})
	store.UpsertNode(ctx, &graph.Node{ID: "fresh", ProjectID: "p", Type: graph.NodeTypeConcept, Title: "Fresh", Content: "kubernetes scheduling taints tolerations",
		Weight: 0.3, DecayHalfLifeSecs: 86400 * 365, CreatedAt: now.Unix(), LastAccessedAt: now.Unix()})

	ev, _ := NewDynamicRetriever(store).RetrieveForRound(ctx, nil, model.Agent{}, "p", "kubernetes", 1, msgs("kubernetes scheduling is the question"), nil, nil)
	if len(ev.Items) != 2 {
		t.Fatalf("both nodes should be recalled, got %+v", ev.Items)
	}
	if ev.Items[0].SourceID != "fresh" {
		t.Fatalf("active node must rank first, got order %s then %s", ev.Items[0].SourceID, ev.Items[1].SourceID)
	}
}
