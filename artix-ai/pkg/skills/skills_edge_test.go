package skills

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func project(t *testing.T, files map[string]string) *Loader {
	t.Helper()
	root := t.TempDir()
	for rel, content := range files {
		p := filepath.Join(root, ".artix", "skills", rel)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(p, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return &Loader{projectDir: root} // no global dir: isolate from the real ~/.artix
}

// Argument values can come from an LLM or a user. They must reach the command as data,
// never be parsed by the shell.
func TestExecuteDoesNotLetArgsInjectShellCommands(t *testing.T) {
	l := project(t, nil)
	marker := filepath.Join(l.projectDir, "pwned")
	skill := &Skill{Name: "echoer", Command: `echo "target={{target}}"`}

	for _, evil := range []string{
		`x"; touch ` + marker + `; echo "`,
		"x; touch " + marker,
		"$(touch " + marker + ")",
		"`touch " + marker + "`",
		"x\ntouch " + marker,
	} {
		res, err := l.Execute(context.Background(), skill, map[string]string{"target": evil})
		if err != nil {
			t.Fatal(err)
		}
		if _, statErr := os.Stat(marker); statErr == nil {
			t.Fatalf("argument %q executed as a shell command (result %+v)", evil, res)
		}
		if !strings.Contains(res.Stdout, "target=") {
			t.Errorf("skill did not run normally for %q: %+v", evil, res)
		}
	}
}

func TestExecuteSubstitutesPlainValuesAndValidatesCommand(t *testing.T) {
	l := project(t, nil)
	res, err := l.Execute(context.Background(), &Skill{Name: "s", Command: `echo "{{a}}-{{b}}"`}, map[string]string{"a": "hello world", "b": "x"})
	if err != nil || res.Stdout != "hello world-x" {
		t.Fatalf("got %+v, %v", res, err)
	}
	if _, err := l.Execute(context.Background(), &Skill{Name: "empty"}, nil); err == nil {
		t.Error("a skill without a command must error")
	}
}

func TestLoadAllIsSortedAndProjectOverridesNothingGlobalHere(t *testing.T) {
	l := project(t, map[string]string{
		"zeta.md":  "---\nname: zeta\ndescription: z\ncommand: echo z\n---\n",
		"alpha.md": "---\nname: alpha\ndescription: a\ncommand: echo a\n---\n",
		"mid.json": `{"name":"mid","command":"echo m"}`,
	})
	for run := 0; run < 15; run++ {
		got, err := l.LoadAll()
		if err != nil {
			t.Fatal(err)
		}
		var names []string
		for _, s := range got {
			names = append(names, s.Name)
		}
		if strings.Join(names, ",") != "alpha,mid,zeta" {
			t.Fatalf("run %d: %v (must be sorted for stable UI/listing)", run, names)
		}
	}
}

func TestMarkdownBodyMayContainHorizontalRulesAndLongLines(t *testing.T) {
	long := strings.Repeat("x", 100_000)
	l := project(t, map[string]string{"doc.md": "---\nname: doc\ncommand: echo hi\n---\nFirst paragraph.\n\n---\n\nSecond paragraph after a rule.\n" + long + "\nEnd.\n"})
	got, _ := l.LoadAll()
	if len(got) != 1 {
		t.Fatalf("got %d skills", len(got))
	}
	body := got[0].PromptTemplate
	for _, want := range []string{"First paragraph.", "Second paragraph after a rule.", "End."} {
		if !strings.Contains(body, want) {
			t.Errorf("body lost %q (horizontal rule mistaken for frontmatter, or long line dropped)", want)
		}
	}
	if !strings.Contains(body, long) {
		t.Error("a 100KB line was truncated")
	}
}

func TestNameFallbacksAndQuotes(t *testing.T) {
	l := project(t, map[string]string{
		"quoted.md":    "---\nname: \"Quoted Name\"\ndescription: 'single'\n---\n",
		"noname.md":    "---\ncommand: echo x\n---\nJust a prompt.",
		"dir/SKILL.md": "---\ndescription: from dir\n---\n",
		"broken.json":  "{not json",
		"ignored.txt":  "nope",
	})
	got, _ := l.LoadAll()
	byName := map[string]Skill{}
	for _, s := range got {
		byName[s.Name] = s
	}
	if byName["Quoted Name"].Description != "single" {
		t.Errorf("quotes not stripped: %+v", byName["Quoted Name"])
	}
	if s, ok := byName["noname"]; !ok || s.Description != "Just a prompt." {
		t.Errorf("file name fallback / description-from-body failed: %+v", byName)
	}
	if _, ok := byName["dir"]; !ok {
		t.Errorf("SKILL.md must be named after its directory: %+v", byName)
	}
	if len(byName) != 3 {
		t.Errorf("broken.json and .txt must be ignored, got %d skills: %v", len(byName), byName)
	}
}
