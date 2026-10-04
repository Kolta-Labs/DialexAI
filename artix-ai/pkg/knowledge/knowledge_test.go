package knowledge

import (
	"os"
	"testing"
	"time"

	"artix/pkg/coder"
	"artix/pkg/spec"
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

func TestKnowledgeStore_EnterpriseIsolationAndSchemaEvolution(t *testing.T) {
	tempProject := t.TempDir()

	// 1. Enterprise mode disables global store contamination
	store := NewStore(tempProject)
	store.SetEnterpriseMode(true)

	if !store.IsEnterpriseMode() {
		t.Fatal("expected enterprise mode to be active")
	}

	ki1 := &KnowledgeItem{
		ID:           "ki-ent-01",
		Title:        "Enterprise Isolation Rule",
		Category:     CategoryArchitecture,
		Context:      "Tenant data separation",
		Breakthrough: "Strict project-level scoping",
	}

	if err := store.Save(ki1); err != nil {
		t.Fatalf("failed to save in enterprise mode: %v", err)
	}

	retrieved, err := store.Get("ki-ent-01")
	if err != nil {
		t.Fatalf("failed to get item: %v", err)
	}
	if retrieved.SchemaVersion != CurrentSchemaVersion {
		t.Errorf("expected schema version %d, got %d", CurrentSchemaVersion, retrieved.SchemaVersion)
	}
	if retrieved.ContentHash == "" {
		t.Errorf("expected computed content hash, got empty")
	}

	// 2. Deduplication check
	kiDuplicate := &KnowledgeItem{
		ID:           "ki-ent-02",
		Title:        "Enterprise Isolation Rule",
		Category:     CategoryArchitecture,
		Context:      "Tenant data separation",
		Breakthrough: "Strict project-level scoping",
	}
	if err := store.Save(kiDuplicate); err != nil {
		t.Fatalf("failed to save duplicate: %v", err)
	}

	list, err := store.List()
	if err != nil {
		t.Fatalf("failed to list items: %v", err)
	}
	// Content duplicate is deduplicated in list
	if len(list) != 1 {
		t.Errorf("expected 1 deduplicated item, got %d", len(list))
	}
}

func TestKnowledgeStore_PruneTTL(t *testing.T) {
	tempProject := t.TempDir()
	store := NewStore(tempProject)

	oldItem := &KnowledgeItem{
		ID:           "ki-old-01",
		Title:        "Old Outdated Knowledge",
		Category:     CategoryDebugging,
		Context:      "Legacy JDK 8 quirk",
		Breakthrough: "Upgrade compiler",
		CreatedAt:    time.Now().Add(-48 * time.Hour),
	}
	newItem := &KnowledgeItem{
		ID:           "ki-new-01",
		Title:        "Recent Architecture Learning",
		Category:     CategoryArchitecture,
		Context:      "KMP memory model",
		Breakthrough: "Use atomic references",
		CreatedAt:    time.Now(),
	}

	if err := store.Save(oldItem); err != nil {
		t.Fatalf("failed to save old item: %v", err)
	}
	if err := store.Save(newItem); err != nil {
		t.Fatalf("failed to save new item: %v", err)
	}

	pruned, err := store.Prune(24 * time.Hour)
	if err != nil {
		t.Fatalf("Prune failed: %v", err)
	}
	if pruned != 1 {
		t.Errorf("expected 1 item pruned, got %d", pruned)
	}

	list, err := store.List()
	if err != nil {
		t.Fatalf("List failed: %v", err)
	}
	if len(list) != 1 || list[0].ID != "ki-new-01" {
		t.Errorf("expected only new item to remain, got %+v", list)
	}
}

func TestComputeHash_NoDelimiterCollision(t *testing.T) {
	// Item A: Category="auth|jwt", Title="fix"
	itemA := &KnowledgeItem{
		Category:     KnowledgeCategory("auth|jwt"),
		Title:        "fix",
		Context:      "c",
		Breakthrough: "b",
	}

	// Item B: Category="auth", Title="jwt|fix"
	itemB := &KnowledgeItem{
		Category:     KnowledgeCategory("auth"),
		Title:        "jwt|fix",
		Context:      "c",
		Breakthrough: "b",
	}

	hashA := computeHash(itemA)
	hashB := computeHash(itemB)

	if hashA == hashB {
		t.Fatalf("hash collision detected across pipe delimiter: hashA=%s, hashB=%s", hashA, hashB)
	}
}

