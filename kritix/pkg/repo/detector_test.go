package repo

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDetectContext(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_repo_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	// Create fake .git dir and a build manifest
	_ = os.Mkdir(filepath.Join(tempDir, ".git"), 0755)
	_ = os.WriteFile(filepath.Join(tempDir, "go.mod"), []byte("module test"), 0644)
	_ = os.WriteFile(filepath.Join(tempDir, "main.go"), []byte("package main\n\nfunc main() {}\n"), 0644)

	ctx, err := DetectContext(tempDir)
	if err != nil {
		t.Fatalf("DetectContext failed: %v", err)
	}

	if !ctx.IsGit {
		t.Errorf("expected isGit=true")
	}
	if ctx.RootDir != tempDir {
		t.Errorf("expected rootDir=%s, got %s", tempDir, ctx.RootDir)
	}
	if !containsEcosystem(ctx.DetectedEcos, EcosystemGo) {
		t.Errorf("expected go ecosystem to be detected")
	}

	// Test Tree Scan
	tree, err := ScanTree(tempDir, 100)
	if err != nil {
		t.Fatalf("ScanTree failed: %v", err)
	}
	if len(tree.Files) == 0 {
		t.Errorf("expected files in tree")
	}

	// Test Snippet Reader
	snippet, err := ReadFileSnippet(filepath.Join(tempDir, "main.go"), 1, 2)
	if err != nil {
		t.Fatalf("ReadFileSnippet failed: %v", err)
	}
	if len(snippet) == 0 {
		t.Errorf("expected non-empty snippet")
	}
}
