package knowledge

import (
	"os"
	"testing"
	"time"

	"kritix/pkg/coder"
	"kritix/pkg/spec"
)

func TestKnowledgeStore_CRUD(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix-ki-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	store := NewStore(tempDir)

	ki := &KnowledgeItem{
		ID:           "ki-test-01",
		Title:        "Handling KMP Coroutines Timeout",
		Category:     CategoryDebugging,
		Context:      "Using withTimeoutOrNull in Kotlin Multiplatform",
		Breakthrough: "Always cancel child scopes to prevent lingering background coroutines",
		CreatedAt:    time.Now(),
	}

	if err := store.Save(ki); err != nil {
		t.Fatalf("failed to save knowledge item: %v", err)
	}

	retrieved, err := store.Get("ki-test-01")
	if err != nil {
		t.Fatalf("failed to get knowledge item: %v", err)
	}
	if retrieved.Title != ki.Title || retrieved.Category != CategoryDebugging {
		t.Errorf("mismatched retrieved item: %+v", retrieved)
	}

	list, err := store.List()
	if err != nil || len(list) != 1 {
		t.Fatalf("unexpected list: %v (len %d)", list, len(list))
	}

	if err := store.Delete("ki-test-01"); err != nil {
		t.Fatalf("failed to delete item: %v", err)
	}

	listAfter, _ := store.List()
	if len(listAfter) != 0 {
		t.Errorf("expected empty list after deletion, got %d", len(listAfter))
	}
}

func TestLearningExtractor_ExtractFromConvergence(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix-extractor-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	store := NewStore(tempDir)
	extractor := NewLearningExtractor(store)

	storySpec := &spec.StorySpec{
		ID:           "STORY-202",
		Title:        "Implement Redis Cache",
		UserStory:    "Cache hot database reads",
		TestCommands: []string{"go test -v ./..."},
	}

	loopResult := &coder.LoopResult{
		Success:   true,
		RoundsRun: 3, // Multi-round convergence
	}

	ki, err := extractor.ExtractFromConvergence(storySpec, loopResult)
	if err != nil {
		t.Fatalf("failed to extract knowledge item: %v", err)
	}
	if ki == nil {
		t.Fatalf("expected extracted knowledge item, got nil")
	}

	if ki.Category != CategoryDebugging || ki.Title == "" {
		t.Errorf("unexpected knowledge item: %+v", ki)
	}
}

func TestRuleSynthesizer_FromReviewComment(t *testing.T) {
	syn := NewRuleSynthesizer()

	// Taboo rule test
	comment := "Never allow direct sqlite imports in the Android UI layer"
	rule, err := syn.SynthesizeFromComment(comment)
	if err != nil {
		t.Fatalf("failed to synthesize rule: %v", err)
	}

	if !rule.IsTaboo {
		t.Errorf("expected taboo rule for 'never' comment")
	}

	hasAndroid := false
	for _, role := range rule.TargetRoles {
		if role == "android_engineer" {
			hasAndroid = true
		}
	}
	if !hasAndroid {
		t.Errorf("expected android_engineer role in targets: %+v", rule.TargetRoles)
	}

	// Heuristic rule test
	heuristicComment := "Always verify timeout boundaries on remote auth API calls"
	hRule, err := syn.SynthesizeFromComment(heuristicComment)
	if err != nil {
		t.Fatalf("failed to synthesize heuristic rule: %v", err)
	}
	if hRule.IsTaboo {
		t.Errorf("expected non-taboo heuristic rule")
	}
}
