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

	_ = os.WriteFile(filepath.Join(tempDir, ".gitignore"), []byte(".artix/\n.kritix/\n"), 0644)
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
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
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

func TestNoModelPathYieldsUnreviewedAndBlocksAutoCommit(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	reg := persona.NewRegistry("")
	coder, _ := NewDomainCoder("backend_engineer", reg)
	// Reviewer without critic
	rev := reviewer.NewAdversarialReviewer(reg)
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(coder, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "STORY-NO-MODEL",
		Title:        "No model critic test",
		TestCommands: []string{"grep '1' counter.txt"},
	}

	repoCtx := &repo.RepositoryContext{RootDir: tempDir}
	opts := &LoopOptions{
		MaxRounds: 1,
		Autonomy:  AutonomyAutonomous,
		MockPatchGen: func(round int, feedback string) string {
			return `--- a/counter.txt
+++ b/counter.txt
@@ -1 +1 @@
-0
+1
`
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected loop to fail due to unreviewed status, got success")
	}
	if res.CommitHash != "" {
		t.Fatalf("autonomous commit must be blocked without model review")
	}
	if res.FinalVerdict == nil || res.FinalVerdict.Status != reviewer.StatusUnreviewed {
		t.Fatalf("expected status=unreviewed, got: %+v", res.FinalVerdict)
	}
	if !strings.Contains(res.Error, "unreviewed") {
		t.Fatalf("expected 'unreviewed' in loop error, got: %s", res.Error)
	}
}

func TestAutonomousModeBlockedInEnterpriseWithoutOptIn(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	file := filepath.Join(tempDir, "counter.txt")
	_ = os.WriteFile(file, []byte("0\n"), 0644)
	_, _ = driver.CommitAll("init counter")

	t.Setenv("ARTIX_ENTERPRISE", "1")
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "")

	reg := persona.NewRegistry("")
	coder, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(coder, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "STORY-ENT-AUTONOMOUS",
		Title:        "Enterprise Autonomous Test",
		TestCommands: []string{"grep '1' counter.txt"},
	}

	validPatch := `--- a/counter.txt
+++ b/counter.txt
@@ -1 +1 @@
-0
+1
`

	repoCtx := &repo.RepositoryContext{RootDir: tempDir}
	opts := &LoopOptions{
		MaxRounds: 1,
		Autonomy:  AutonomyAutonomous,
		MockPatchGen: func(round int, feedback string) string {
			return validPatch
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, nil, nil, opts)
	if res.CommitHash != "" || !strings.Contains(res.Error, "autonomous commit blocked") {
		t.Fatalf("expected autonomous commit to be blocked in enterprise mode, got: %+v", res)
	}

	// Now with explicit opt-in ARTIX_ALLOW_AUTONOMOUS=1
	t.Setenv("ARTIX_ALLOW_AUTONOMOUS", "1")
	_ = os.WriteFile(file, []byte("0\n"), 0644)
	_, _ = driver.CommitAll("reset counter")
	resWithOptIn := coord.Run(context.Background(), storySpec, repoCtx, nil, nil, opts)
	if !resWithOptIn.Success || resWithOptIn.CommitHash == "" {
		t.Fatalf("expected successful auto-commit with explicit ARTIX_ALLOW_AUTONOMOUS=1, got: %+v", resWithOptIn)
	}
}

func TestTokenBudgetExhaustionMidLoop(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	file := filepath.Join(tempDir, "counter.txt")
	_ = os.WriteFile(file, []byte("0\n"), 0644)
	_, _ = driver.CommitAll("init counter")

	reg := persona.NewRegistry("")
	coder, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(coder, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "STORY-BUDGET-01",
		Title:        "Cost Governor Budget Test",
		TestCommands: []string{"grep '1' counter.txt"},
	}

	validPatch := `--- a/counter.txt
+++ b/counter.txt
@@ -1 +1 @@
-0
+1
`

	repoCtx := &repo.RepositoryContext{RootDir: tempDir}
	// Very small story budget that gets exhausted immediately
	opts := &LoopOptions{
		MaxRounds: 3,
		Budget: &TokenBudget{
			MaxStoryTokens: 20, // 20 tokens cap is much lower than prompt + patch tokens
		},
		MockPatchGen: func(round int, feedback string) string {
			return validPatch
		},
	}

	res := coord.Run(context.Background(), storySpec, repoCtx, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected loop to fail due to budget exhaustion, but succeeded: %+v", res)
	}
	if res.CostReport == nil || !res.CostReport.Exhausted {
		t.Fatalf("expected CostReport.Exhausted=true, got: %+v", res.CostReport)
	}
	if !strings.Contains(res.Error, "budget exhausted") {
		t.Fatalf("expected 'budget exhausted' in error, got: %s", res.Error)
	}
	if res.CostReport.TotalTokens <= 0 {
		t.Fatalf("expected TotalTokens > 0 in cost report, got: %d", res.CostReport.TotalTokens)
	}
	if len(res.CostReport.Rounds) == 0 {
		t.Fatalf("expected round metrics in cost report")
	}

	// Verify working tree was rolled back cleanly
	data, _ := os.ReadFile(file)
	if strings.TrimSpace(string(data)) != "0" {
		t.Fatalf("expected file to be rolled back to '0', got: %q", string(data))
	}
}

func TestCompileDNALayersTaskFiltering(t *testing.T) {
	dna := &model.PersonaDNA{
		CoreIdentity: model.CoreIdentity{
			DomainAuthority: "Security & Vulnerability Analysis",
		},
		EpistemicBias: model.EpistemicBias{
			PrimaryMode: model.ReasoningEmpiricalStatistical,
		},
		CommunicationVector: model.CommunicationVector{
			Tone: "FORMAL_CRITICAL",
		},
		TabooSpace: model.TabooSpace{
			ForbiddenArguments: []string{"No unchecked buffer copies"},
		},
		AdversarialPosture: model.AdversarialPosture{
			Stance:        model.StanceAnalyticalDeconstructor,
			TenacityScore: 0.95,
		},
		SynthesisPreference: model.SynthesisPreference{
			Style: model.SynthesisSeekSynthesis,
		},
	}

	// Patch review: includes posture & taboos, omits communication tone
	reviewPrompt := CompileDNALayers(dna, TaskPatchReview)
	if !strings.Contains(reviewPrompt, "Security & Vulnerability Analysis") {
		t.Errorf("expected domain authority in review prompt")
	}
	if !strings.Contains(reviewPrompt, "No unchecked buffer copies") {
		t.Errorf("expected taboo in review prompt")
	}
	if !strings.Contains(reviewPrompt, "Reviewer Posture") {
		t.Errorf("expected reviewer posture in review prompt")
	}
	if strings.Contains(reviewPrompt, "FORMAL_CRITICAL") {
		t.Errorf("review prompt should omit communication tone, got: %s", reviewPrompt)
	}

	// Spec deliberation: includes communication tone and epistemic reasoning mode
	delibPrompt := CompileDNALayers(dna, TaskSpecDeliberation)
	if !strings.Contains(delibPrompt, "FORMAL_CRITICAL") {
		t.Errorf("expected communication tone in deliberation prompt")
	}
	if !strings.Contains(delibPrompt, string(model.ReasoningEmpiricalStatistical)) {
		t.Errorf("expected reasoning mode in deliberation prompt")
	}
}

