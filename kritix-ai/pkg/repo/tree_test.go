package repo

import (
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

func write(t *testing.T, root, rel, content string) {
	t.Helper()
	p := filepath.Join(root, rel)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func scanPaths(t *testing.T, root string, max int) []string {
	t.Helper()
	idx, err := ScanTree(root, max)
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, f := range idx.Files {
		out = append(out, filepath.ToSlash(f.RelPath))
	}
	sort.Strings(out)
	return out
}

func has(paths []string, want string) bool {
	for _, p := range paths {
		if p == want {
			return true
		}
	}
	return false
}

func TestScanTreeSkipsDefaultIgnoredAndHidden(t *testing.T) {
	root := t.TempDir()
	write(t, root, "main.go", "package main")
	write(t, root, "node_modules/x/index.js", "x")
	write(t, root, "build/out.bin", "x")
	write(t, root, ".env", "SECRET=1")
	write(t, root, ".gitignore", "x")

	got := scanPaths(t, root, 0)
	for _, bad := range []string{"node_modules/x/index.js", "build/out.bin", ".env"} {
		if has(got, bad) {
			t.Errorf("%s must not be indexed: %v", bad, got)
		}
	}
	if !has(got, "main.go") || !has(got, ".gitignore") {
		t.Errorf("expected main.go and .gitignore: %v", got)
	}
}

func TestScanTreeHonorsGitignore(t *testing.T) {
	if _, err := exec.LookPath("git"); err != nil {
		t.Skip("git not installed")
	}
	root := t.TempDir()
	if out, err := exec.Command("git", "-C", root, "init", "-q").CombinedOutput(); err != nil {
		t.Fatalf("git init: %v %s", err, out)
	}
	write(t, root, ".gitignore", "out/\nsecrets.json\n*.log\n")
	write(t, root, "src/app.go", "package app")
	write(t, root, "out/gen.go", "package out")
	write(t, root, "secrets.json", "{}")
	write(t, root, "src/debug.log", "x")

	got := scanPaths(t, root, 0)
	for _, bad := range []string{"out/gen.go", "secrets.json", "src/debug.log"} {
		if has(got, bad) {
			t.Errorf("gitignored %s must not be indexed (it could be sent to an LLM): %v", bad, got)
		}
	}
	if !has(got, "src/app.go") {
		t.Errorf("tracked-by-rule file missing: %v", got)
	}
}

func TestScanTreeKeepsGithubDir(t *testing.T) {
	root := t.TempDir()
	write(t, root, ".github/workflows/ci.yml", "name: ci")
	if got := scanPaths(t, root, 0); !has(got, ".github/workflows/ci.yml") {
		t.Errorf("CI config should be visible to reviewers: %v", got)
	}
}

func TestScanTreeRespectsMaxFiles(t *testing.T) {
	root := t.TempDir()
	for _, n := range []string{"a.go", "b.go", "c.go", "d.go"} {
		write(t, root, n, "x")
	}
	if got := scanPaths(t, root, 2); len(got) != 2 {
		t.Errorf("want 2 files, got %v", got)
	}
}

func TestDetectLanguage(t *testing.T) {
	for name, want := range map[string]string{
		"a.go": "go", "B.KT": "kotlin", "x.kts": "kotlin", "a.tsx": "typescript",
		"m.yml": "yaml", "README": "text", "a.unknown": "text",
	} {
		if got := detectLanguage(name); got != want {
			t.Errorf("detectLanguage(%q)=%q want %q", name, got, want)
		}
	}
}

func TestReadFileSnippet(t *testing.T) {
	root := t.TempDir()
	write(t, root, "f.txt", "l1\nl2\nl3\nl4\nl5\n")
	p := filepath.Join(root, "f.txt")
	cases := []struct {
		start, end int
		want       string
	}{
		{2, 3, "l2\nl3\n"},
		{0, 2, "l1\nl2\n"},
		{4, 0, "l4\nl5\n"},
		{0, 0, "l1\nl2\nl3\nl4\nl5\n"},
		{9, 12, ""},
	}
	for _, c := range cases {
		got, err := ReadFileSnippet(p, c.start, c.end)
		if err != nil || got != c.want {
			t.Errorf("snippet(%d,%d)=%q,%v want %q", c.start, c.end, got, err, c.want)
		}
	}
	if _, err := ReadFileSnippet(filepath.Join(root, "missing"), 1, 2); err == nil {
		t.Error("expected error for missing file")
	}
}

func TestReadFileSnippetHandlesVeryLongLines(t *testing.T) {
	root := t.TempDir()
	// Minified JS / lockfiles routinely exceed bufio.Scanner's 64KB default token limit.
	write(t, root, "min.js", strings.Repeat("a", 200_000)+"\nsecond\n")
	got, err := ReadFileSnippet(filepath.Join(root, "min.js"), 2, 2)
	if err != nil || got != "second\n" {
		t.Fatalf("long line broke snippet read: %q, %v", got, err)
	}
}
