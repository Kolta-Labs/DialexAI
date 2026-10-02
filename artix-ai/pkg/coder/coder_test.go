package coder

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"artix/pkg/git"
	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
	"artix/pkg/steering"
	"socratix/pkg/model"
)

func TestDomainCoderPromptCompilation(t *testing.T) {
	reg := persona.NewRegistry("")
	coder, err := NewDomainCoder("android_engineer", reg)
	if err != nil {
		t.Fatalf("NewDomainCoder failed: %v", err)
	}

	pCtx := &PromptContext{
		Spec: &spec.StorySpec{
			ID:        "STORY-101",
			Title:     "Offline Cart Sync",
			UserStory: "Sync cart offline with Room DB",
			AcceptanceCriteria: []spec.Scenario{
				{Name: "Offline Cart", Given: "no network", When: "item added", Then: "saved in room"},
			},
		},
		SteeringContext: &steering.PersonaSteeringContext{
			Taboos: model.TabooSpace{
				ForbiddenArguments: []string{"No blocking main thread"},
			},
			Heuristics: []model.HeuristicRule{
				{FormulaOrMaxime: "Always use Kotlin Flow"},
			},
		},
	}

	sys, user := coder.CompilePrompt(pCtx)

	if !strings.Contains(sys, "No blocking main thread") {
		t.Errorf("expected taboo in system prompt")
	}
	if !strings.Contains(sys, "Always use Kotlin Flow") {
		t.Errorf("expected heuristic in system prompt")
	}
	if !strings.Contains(user, "Offline Cart Sync") {
		t.Errorf("expected story title in user prompt")
	}
}

func setupTestRepo(t *testing.T) (string, *git.Driver) {
	tempDir, err := os.MkdirTemp("", "kritix_loop_test")
	if err != nil {
		t.Fatalf("failed to create temp dir: %v", err)
	}

	_ = exec.Command("git", "init", tempDir).Run()
	_ = exec.Command("git", "-C", tempDir, "config", "user.email", "test@kritix.ai").Run()
	_ = exec.Command("git", "-C", tempDir, "config", "user.name", "Kritix Test").Run()

	file := filepath.Join(tempDir, "counter.txt")
	_ = os.WriteFile(file, []byte("0\n"), 0644)

	driver := git.NewDriver(tempDir)
	_, _ = driver.CommitAll("initial commit")
	return tempDir, driver
}

func TestConvergenceCoordinatorLoop(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	reg := persona.NewRegistry("")
	coder, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	box := sandbox.NewSandbox(tempDir)

	coord := NewCoordinator(coder, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "STORY-LOOP-01",
		Title:        "Update counter",
		TestCommands: []string{"grep '2' counter.txt"}, // only passes when counter is 2
	}

	repoCtx := &repo.RepositoryContext{
		RootDir: tempDir,
	}

	// Mock patch generator: Round 1 fails (counter=1), Round 2 succeeds (counter=2)
	patchRound1 := `--- a/counter.txt
+++ b/counter.txt
@@ -1 +1 @@
-0
+1
`
	patchRound2 := `--- a/counter.txt
+++ b/counter.txt
@@ -1 +1 @@
-0
+2
`

	opts := &LoopOptions{
		MaxRounds: 3,
		Autonomy:  AutonomyAutonomous,
		MockPatchGen: func(round int, feedback string) string {
			if round == 1 {
				return patchRound1
			}
			return patchRound2
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, nil, nil, opts)

	if !res.Success {
		t.Fatalf("expected loop to succeed, failed with: %s", res.Error)
	}
	if res.RoundsRun != 2 {
		t.Errorf("expected 2 rounds, got %d", res.RoundsRun)
	}
	if res.CommitHash == "" {
		t.Errorf("expected auto-commit hash in autonomous mode")
	}

	// Verify working tree is committed and counter is 2
	status, _ := driver.Status()
	if !status.IsClean {
		t.Errorf("expected clean git status after autonomous commit")
	}
}
