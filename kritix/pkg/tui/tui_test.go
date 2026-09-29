package tui

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"kritix/pkg/persona"
)

func TestMentionResolver_ResolveAll(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix-tui-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	// Create test file
	_ = os.WriteFile(filepath.Join(tempDir, "sample.go"), []byte("package sample\nfunc Run(){}"), 0644)

	// Create test spec
	_ = os.MkdirAll(filepath.Join(tempDir, "docs", "specs"), 0755)
	_ = os.WriteFile(filepath.Join(tempDir, "docs", "specs", "STORY-101.md"), []byte("# Spec 101\n"), 0644)

	resolver := NewMentionResolver(tempDir)
	prompt := "Check @file:sample.go and verify against @spec:STORY-101 and symbol #MyFunction"

	cleaned, mentions := resolver.ResolveAll(prompt)
	if cleaned != prompt {
		t.Errorf("expected cleaned to preserve prompt text")
	}

	if len(mentions) != 3 {
		t.Fatalf("expected 3 mentions, got %d", len(mentions))
	}

	var hasFile, hasSpec, hasSymbol bool
	for _, m := range mentions {
		if m.Type == MentionFile && m.Target == "sample.go" && strings.Contains(m.Content, "package sample") {
			hasFile = true
		}
		if m.Type == MentionSpec && m.Target == "STORY-101" && strings.Contains(m.Content, "# Spec 101") {
			hasSpec = true
		}
		if m.Type == MentionSymbol && m.Target == "MyFunction" {
			hasSymbol = true
		}
	}

	if !hasFile || !hasSpec || !hasSymbol {
		t.Errorf("missing expected mentions: %+v", mentions)
	}
}

func TestAlignmentInterview_GenerateQuestions(t *testing.T) {
	reg := persona.NewRegistry("")
	ai := NewAlignmentInterview(reg)

	questions := ai.GenerateQuestions("OAuth2 Login Flow")
	if len(questions) != 3 {
		t.Fatalf("expected 3 questions, got %d", len(questions))
	}

	roles := map[string]bool{}
	for _, q := range questions {
		roles[q.Role] = true
		if q.Question == "" || q.Guidance == "" {
			t.Errorf("incomplete question item: %+v", q)
		}
	}

	if !roles["Product Owner"] || !roles["Senior Architect"] || !roles["QA Lead"] {
		t.Errorf("expected PO, Architect, and QA questions, got: %+v", roles)
	}
}

func TestREPL_InteractiveSession(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix-repl-test-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	reg := persona.NewRegistry(tempDir)

	// Simulate user input sequence: /help -> /grill-me Auth -> /compact -> /exit
	input := "/help\n/grill-me Auth\n/compact\n/exit\n"
	in := strings.NewReader(input)
	out := &bytes.Buffer{}

	repl := NewREPL(tempDir, reg, in, out)
	ctx := context.Background()

	if err := repl.Run(ctx); err != nil {
		t.Fatalf("repl run failed: %v", err)
	}

	output := out.String()
	if !strings.Contains(output, "Kritix AI Shell") {
		t.Errorf("expected banner in output")
	}
	if !strings.Contains(output, "Conduct Socratic alignment interview") {
		t.Errorf("expected help text in output")
	}
	if !strings.Contains(output, "STAKEHOLDER COUNCIL ALIGNMENT INTERVIEW") {
		t.Errorf("expected alignment interview output")
	}
	if !strings.Contains(output, "Compacting context history") {
		t.Errorf("expected compact output")
	}
	if !strings.Contains(output, "Goodbye!") {
		t.Errorf("expected goodbye in output")
	}
}

func TestREPL_ReviewAndUndo(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix-repl-undo-*")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	reg := persona.NewRegistry(tempDir)
	input := "/review\n/undo\n/exit\n"
	in := strings.NewReader(input)
	out := &bytes.Buffer{}

	repl := NewREPL(tempDir, reg, in, out)
	ctx := context.Background()

	if err := repl.Run(ctx); err != nil {
		t.Fatalf("repl run failed: %v", err)
	}

	output := out.String()
	if !strings.Contains(output, "zero uncommitted diffs") {
		t.Errorf("expected clean review output")
	}
	if !strings.Contains(output, "No active diff to undo") {
		t.Errorf("expected no diff to undo output")
	}
}
