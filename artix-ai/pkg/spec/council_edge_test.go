package spec

import (
	"context"
	"strings"
	"sync"
	"testing"

	"artix/pkg/persona"
	"artix/pkg/repo"
)

func plan(t *testing.T, prompt string, rc *repo.RepositoryContext, style StyleVector) *StorySpec {
	t.Helper()
	s, err := NewCouncil(persona.NewRegistry("")).Plan(context.Background(), &PlanningContext{StoryPrompt: prompt, RepoContext: rc, Style: style})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestPlanRejectsBlankPrompt(t *testing.T) {
	c := NewCouncil(persona.NewRegistry(""))
	for _, p := range []*PlanningContext{nil, {}, {StoryPrompt: "  \n"}} {
		if _, err := c.Plan(context.Background(), p); err == nil {
			t.Errorf("%+v must be rejected", p)
		}
	}
}

func TestPlanIDsNeverCollide(t *testing.T) {
	c := NewCouncil(persona.NewRegistry(""))
	var mu sync.Mutex
	seen := map[string]bool{}
	var wg sync.WaitGroup
	for i := 0; i < 200; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			s, _ := c.Plan(context.Background(), &PlanningContext{StoryPrompt: "add login"})
			mu.Lock()
			defer mu.Unlock()
			if seen[s.ID] {
				t.Errorf("duplicate spec id %s: two plans would overwrite the same docs/specs file", s.ID)
			}
			seen[s.ID] = true
		}()
	}
	wg.Wait()
}

func TestPlanTestCommandsFollowDetectedEcosystems(t *testing.T) {
	rc := &repo.RepositoryContext{DetectedEcos: []repo.Ecosystem{repo.EcosystemGo, repo.EcosystemNode, repo.EcosystemPython}}
	got := strings.Join(plan(t, "x", rc, "").TestCommands, "|")
	for _, want := range []string{"go test -v ./...", "npm test", "pytest"} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %q in %q", want, got)
		}
	}
	if kmp := plan(t, "x", &repo.RepositoryContext{DetectedEcos: []repo.Ecosystem{repo.EcosystemGradleKMP}}, ""); len(kmp.TestCommands) != 2 {
		t.Errorf("KMP: %v", kmp.TestCommands)
	}
	for name, rc := range map[string]*repo.RepositoryContext{"nil context": nil, "unknown": {DetectedEcos: []repo.Ecosystem{repo.EcosystemUnknown}}} {
		if cmds := plan(t, "x", rc, "").TestCommands; len(cmds) != 1 || cmds[0] != "go test ./..." {
			t.Errorf("%s must fall back to a default test command, got %v", name, cmds)
		}
	}
}

func TestPlanStylesAndTitle(t *testing.T) {
	if s := plan(t, "add login with oauth and refresh tokens to the api", nil, ""); !strings.HasSuffix(s.Title, "...") || len(strings.Fields(strings.TrimSuffix(s.Title, "..."))) != 6 {
		t.Errorf("long prompts truncate to 6 words: %q", s.Title)
	}
	if s := plan(t, "short prompt", nil, ""); s.Title != "short prompt" {
		t.Errorf("short title: %q", s.Title)
	}
	if s := plan(t, "add login", nil, StyleCaveman); !strings.HasPrefix(s.UserStory, "Build ") {
		t.Errorf("caveman: %q", s.UserStory)
	}
	if s := plan(t, "add login", nil, StylePonytail); !strings.HasPrefix(s.UserStory, "Executive Summary") {
		t.Errorf("ponytail: %q", s.UserStory)
	}
	s := plan(t, "add login", nil, "")
	if s.RawMarkdown == "" || len(s.AcceptanceCriteria) == 0 {
		t.Errorf("draft must be renderable with acceptance criteria: %+v", s)
	}
}
