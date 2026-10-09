package coder

import (
	"context"
	"strings"
	"testing"
	"time"

	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
)

func TestBoard3_LoopBudgetLimits_Tokens(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	reg := persona.NewRegistry("")
	dc, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	coord := NewCoordinator(dc, rev, driver, sandbox.NewSandbox(tempDir))
	s := &spec.StorySpec{ID: "B3-1", Title: "Budget test", TestCommands: []string{"grep '2' counter.txt"}}
	rc := &repo.RepositoryContext{RootDir: tempDir}

	// Token usage simulator tracking usage > MaxTokens
	opts := &LoopOptions{
		MaxRounds: 5,
		MaxTokens: 500, // hard cap 500 tokens
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
		CoderUsageTracker: func() *ProviderUsage {
			return &ProviderUsage{PromptTokens: 300, CompletionTokens: 300, TotalTokens: 600}
		},
	}

	res := coord.Run(context.Background(), s, rc, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected loop to abort when MaxTokens exceeded, got success")
	}
	if !strings.Contains(res.Error, "budget") || !strings.Contains(res.Error, "token") {
		t.Fatalf("expected budget token error, got: %s", res.Error)
	}
}

func TestBoard3_LoopBudgetLimits_USD(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	reg := persona.NewRegistry("")
	dc, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	coord := NewCoordinator(dc, rev, driver, sandbox.NewSandbox(tempDir))
	s := &spec.StorySpec{ID: "B3-2", Title: "USD budget test", TestCommands: []string{"grep '2' counter.txt"}}
	rc := &repo.RepositoryContext{RootDir: tempDir}

	opts := &LoopOptions{
		MaxRounds: 5,
		MaxUSD:    0.05, // hard cap $0.05
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
		CoderUsageTracker: func() *ProviderUsage {
			return &ProviderUsage{PromptTokens: 10000, CompletionTokens: 5000, TotalTokens: 15000} // $0.10+
		},
	}

	res := coord.Run(context.Background(), s, rc, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected loop to abort when MaxUSD exceeded, got success")
	}
	if !strings.Contains(res.Error, "budget") || !strings.Contains(res.Error, "USD") {
		t.Fatalf("expected budget USD error, got: %s", res.Error)
	}
}

func TestBoard3_LoopBudgetLimits_WallClock(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	reg := persona.NewRegistry("")
	dc, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	coord := NewCoordinator(dc, rev, driver, sandbox.NewSandbox(tempDir))
	s := &spec.StorySpec{ID: "B3-3", Title: "Wall clock test", TestCommands: []string{"grep '2' counter.txt"}}
	rc := &repo.RepositoryContext{RootDir: tempDir}

	opts := &LoopOptions{
		MaxRounds: 5,
		MaxWall:   10 * time.Millisecond, // very short timeout
		MockPatchGen: func(round int, feedback string) string {
			time.Sleep(25 * time.Millisecond)
			return "diff --git a/counter.txt b/counter.txt\n--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
	}

	res := coord.Run(context.Background(), s, rc, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected loop to abort when MaxWall exceeded, got success")
	}
	if !strings.Contains(res.Error, "budget") || !strings.Contains(res.Error, "wall clock") {
		t.Fatalf("expected budget wall clock error, got: %s", res.Error)
	}
}

func TestBoard3_DisjointCoderAndCriticModelFamily(t *testing.T) {
	reg := persona.NewRegistry("")
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCoderFamily("openai")

	// 1. Same family -> must be rejected
	critic := func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	}
	errSame := rev.SetCriticWithFamily(critic, "openai")
	if errSame == nil {
		t.Errorf("expected SetCriticWithFamily to reject same model family (openai == openai)")
	}

	// 2. Disjoint family -> must be accepted
	errDiff := rev.SetCriticWithFamily(critic, "anthropic")
	if errDiff != nil {
		t.Errorf("expected SetCriticWithFamily to accept disjoint model family (openai != anthropic), got: %v", errDiff)
	}
}

func TestBoard3_Reviewer_TestDiff_Integrity(t *testing.T) {
	reg := persona.NewRegistry("")
	rev := reviewer.NewAdversarialReviewer(reg)

	// 1. Assertion removal -> must be rejected
	removalDiff := `diff --git a/app_test.go b/app_test.go
--- a/app_test.go
+++ b/app_test.go
@@ -10,2 +10,1 @@
-	if got != want { t.Fatalf("expected %d got %d", want, got) }
+	_ = got
`
	v1 := rev.Evaluate(&reviewer.ReviewContext{
		Diff: removalDiff,
	})
	if v1.Approved || len(v1.BlockingIssues) == 0 {
		t.Errorf("expected Reviewer to reject assertion removal in test diff, got verdict: %+v", v1)
	}

	// 2. Skip addition -> must be rejected
	skipDiff := `diff --git a/app_test.go b/app_test.go
--- a/app_test.go
+++ b/app_test.go
@@ -5,1 +5,2 @@
+	t.Skip("skipping flaky test")
 	assert.Equal(t, 1, 1)
`
	v2 := rev.Evaluate(&reviewer.ReviewContext{
		Diff: skipDiff,
	})
	if v2.Approved || len(v2.BlockingIssues) == 0 {
		t.Errorf("expected Reviewer to reject t.Skip addition in test diff, got verdict: %+v", v2)
	}
}
