package steering

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAggregatorLocalRules(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_steering_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	// Create root CLAUDE.md
	claudePath := filepath.Join(tempDir, "CLAUDE.md")
	_ = os.WriteFile(claudePath, []byte("# General Standards\n- Always write unit tests.\n- Never bypass repository layer.\n"), 0644)

	// Create .artix/steering/arch.md
	archDir := filepath.Join(tempDir, ".artix", "steering")
	_ = os.MkdirAll(archDir, 0755)
	_ = os.WriteFile(filepath.Join(archDir, "arch.md"), []byte("# Architecture\n- Prefer Result<T> over exceptions.\n- Do not use raw SQLite in ViewModel.\n"), 0644)

	agg := NewAggregator(tempDir)
	rules, err := agg.CollectLocalRules()
	if err != nil {
		t.Fatalf("CollectLocalRules failed: %v", err)
	}

	if len(rules) < 2 {
		t.Errorf("expected at least 2 rules, got %d", len(rules))
	}
}

func TestBinderPersonaConstraints(t *testing.T) {
	rules := []RuleFile{
		{
			ID:      "kotlin_std",
			Name:    "kotlin-conventions.md",
			Content: "# Kotlin Rules\n- Always use Kotlin Flow for reactive streams.\n- Never block the main Android thread.\n- Prefer val over var.\n",
		},
		{
			ID:      "sec_std",
			Name:    "security.md",
			Content: "# Security\n- Never log authorization tokens.\n- Always sanitize SQL inputs.\n",
		},
	}

	config := SteeringConfig{
		GlobalRules: []string{"kotlin_std"},
		Bindings: map[string][]string{
			"adversarial_code_reviewer": {"sec_std"},
		},
	}

	binder := NewBinder(config, rules)

	// 1. Check Coder (Gets global rules only)
	coderCtx := binder.CompilePersonaSteering("android_engineer")
	if len(coderCtx.BoundRules) != 1 {
		t.Errorf("expected 1 rule bound to coder, got %d", len(coderCtx.BoundRules))
	}
	if len(coderCtx.Taboos.ForbiddenArguments) != 1 {
		t.Errorf("expected 1 taboo for coder, got %d", len(coderCtx.Taboos.ForbiddenArguments))
	}
	if len(coderCtx.Heuristics) != 2 {
		t.Errorf("expected 2 heuristics for coder, got %d", len(coderCtx.Heuristics))
	}

	// 2. Check Reviewer (Gets global + sec_std)
	revCtx := binder.CompilePersonaSteering("adversarial_code_reviewer")
	if len(revCtx.BoundRules) != 2 {
		t.Errorf("expected 2 rules bound to reviewer, got %d", len(revCtx.BoundRules))
	}
	if len(revCtx.Taboos.ForbiddenArguments) != 2 {
		t.Errorf("expected 2 taboos for reviewer, got %d", len(revCtx.Taboos.ForbiddenArguments))
	}
}

func TestRemoteSyncerSibling(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_sibling_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	_ = os.WriteFile(filepath.Join(tempDir, "shared-standard.md"), []byte("# Shared\n- Always follow ISO 8601.\n"), 0644)

	syncer := NewRemoteSyncer()
	src := ExternalSource{
		ID:   "sibling_standards",
		Name: "Sibling Standards",
		Type: SourceLocalSibling,
		Path: tempDir,
	}

	rules, err := syncer.SyncSource(src)
	if err != nil {
		t.Fatalf("SyncSource failed: %v", err)
	}

	if len(rules) != 1 {
		t.Fatalf("expected 1 rule, got %d", len(rules))
	}
	if rules[0].Name != "shared-standard.md" {
		t.Errorf("unexpected rule name: %s", rules[0].Name)
	}
}

func TestSteeringManager_BindAndUnbind(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix-mgr-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	mgr := NewManager(tempDir)
	cfg, err := mgr.LoadConfig()
	if err != nil {
		t.Fatalf("failed to load initial config: %v", err)
	}
	if len(cfg.Bindings) != 0 {
		t.Errorf("expected empty bindings")
	}

	// Bind
	if err := mgr.BindRule("android_engineer", "taboo-sqlite"); err != nil {
		t.Fatalf("failed to bind rule: %v", err)
	}

	cfg, _ = mgr.LoadConfig()
	if len(cfg.Bindings["android_engineer"]) != 1 || cfg.Bindings["android_engineer"][0] != "taboo-sqlite" {
		t.Errorf("unexpected bindings after bind: %+v", cfg.Bindings)
	}

	// Unbind
	if err := mgr.UnbindRule("android_engineer", "taboo-sqlite"); err != nil {
		t.Fatalf("failed to unbind rule: %v", err)
	}

	cfg, _ = mgr.LoadConfig()
	if len(cfg.Bindings["android_engineer"]) != 0 {
		t.Errorf("unexpected bindings after unbind: %+v", cfg.Bindings)
	}
}
