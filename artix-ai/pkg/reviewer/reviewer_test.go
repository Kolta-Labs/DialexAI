package reviewer

import (
	"context"
	"errors"
	"strings"
	"testing"

	"artix/pkg/persona"
	"artix/pkg/sandbox"
	"artix/pkg/steering"
	"socratix/pkg/model"
)

func TestReviewerEvaluatesSuccess(t *testing.T) {
	reg := persona.NewRegistry("")
	rev := NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})

	ctx := &ReviewContext{
		Diff: "--- a/file.go\n+++ b/file.go\n@@ -1 +1 @@\n-old\n+new",
		TestResults: []*sandbox.ExecResult{
			{
				Command:  "go test ./...",
				ExitCode: 0,
			},
		},
	}

	verdict := rev.Evaluate(ctx)
	if !verdict.Approved || verdict.Status != StatusApproved {
		t.Fatalf("expected approved=true and status=approved, got: %+v", verdict)
	}
}

func TestReviewerRejectsFailedTests(t *testing.T) {
	reg := persona.NewRegistry("")
	rev := NewAdversarialReviewer(reg)

	ctx := &ReviewContext{
		Diff: "--- a/file.go\n+++ b/file.go\n@@ -1 +1 @@\n-old\n+new",
		TestResults: []*sandbox.ExecResult{
			{
				Command:  "go test ./...",
				ExitCode: 1,
				Stderr:   "FAIL: TestAuthHandler",
			},
		},
	}

	verdict := rev.Evaluate(ctx)
	if verdict.Approved || verdict.Status != StatusRejected {
		t.Fatalf("expected approved=false and status=rejected on test failure")
	}
	if len(verdict.BlockingIssues) == 0 {
		t.Errorf("expected blocking issues to be reported")
	}
}

func TestReviewerEnforcesTaboo(t *testing.T) {
	reg := persona.NewRegistry("")
	rev := NewAdversarialReviewer(reg)

	ctx := &ReviewContext{
		Diff: "+ import android.database.sqlite.SQLiteDatabase",
		TestResults: []*sandbox.ExecResult{
			{Command: "test", ExitCode: 0},
		},
		SteeringContext: &steering.PersonaSteeringContext{
			Taboos: model.TabooSpace{
				ForbiddenArguments: []string{"No raw SQLite in ViewModel"},
			},
		},
	}

	verdict := rev.Evaluate(ctx)
	if verdict.Approved || verdict.Status != StatusRejected {
		t.Errorf("expected approved=false due to taboo violation, got: %+v", verdict)
	}
}

func TestGlobalTabooAppliesAcrossPersonas(t *testing.T) {
	reg := persona.NewRegistry("")
	rev := NewAdversarialReviewer(reg)
	rev.SetGlobalTaboos(steering.GlobalTabooSpace{
		ForbiddenArguments: []string{"eval(payload)", "hardcoded_secret_key"},
	})

	ctx := &ReviewContext{
		Diff: "+ const token = 'hardcoded_secret_key'",
		TestResults: []*sandbox.ExecResult{
			{Command: "test", ExitCode: 0},
		},
		// Persona steering context has no persona-specific taboos
		SteeringContext: &steering.PersonaSteeringContext{},
	}

	verdict := rev.Evaluate(ctx)
	if verdict.Approved || verdict.Status != StatusRejected {
		t.Fatalf("expected rejection due to global taboo violation, got: %+v", verdict)
	}
	foundGlobalTaboo := false
	for _, bi := range verdict.BlockingIssues {
		if strings.Contains(bi, "hardcoded_secret_key") {
			foundGlobalTaboo = true
			break
		}
	}
	if !foundGlobalTaboo {
		t.Errorf("expected global taboo violation in blocking issues: %v", verdict.BlockingIssues)
	}
}

func TestAnalyzerFindingsFeedReviewer(t *testing.T) {
	reg := persona.NewRegistry("")
	rev := NewAdversarialReviewer(reg)

	ctx := &ReviewContext{
		Diff: "--- a/User.kt\n+++ b/User.kt\n@@ -1 +1 @@\n+var count: Int = 0",
		TestResults: []*sandbox.ExecResult{
			{Command: "test", ExitCode: 0},
		},
		AnalyzerFindings: []AnalyzerFinding{
			{
				Tool:     "Konsist",
				Severity: "ERROR",
				Message:  "Classes extending ViewModel must not expose mutable properties (count)",
			},
		},
	}

	verdict := rev.Evaluate(ctx)
	if verdict.Approved || verdict.Status != StatusRejected {
		t.Fatalf("expected rejection due to static analyzer error, got: %+v", verdict)
	}
	foundAnalyzer := false
	for _, bi := range verdict.BlockingIssues {
		if strings.Contains(bi, "Konsist") && strings.Contains(bi, "mutable properties") {
			foundAnalyzer = true
			break
		}
	}
	if !foundAnalyzer {
		t.Errorf("expected Konsist violation in blocking issues: %v", verdict.BlockingIssues)
	}
}

func TestNoCriticYieldsUnreviewedStatus(t *testing.T) {
	rev := NewAdversarialReviewer(persona.NewRegistry(""))
	v := rev.Evaluate(criticCtx())
	if v.Approved || v.Status != StatusUnreviewed {
		t.Fatalf("expected Approved=false and Status=unreviewed when no Critic is set, got: %+v", v)
	}
	if !strings.Contains(v.Summary, "UNREVIEWED") {
		t.Errorf("expected UNREVIEWED in summary, got: %s", v.Summary)
	}
}

func criticCtx() *ReviewContext {
	return &ReviewContext{
		Diff:        "--- a/f.go\n+++ b/f.go\n@@ -1 +1 @@\n-a\n+b",
		TestResults: []*sandbox.ExecResult{{Command: "go test", ExitCode: 0}},
		Criteria:    []string{"login: Given a user, When they log in, Then a token is issued"},
	}
}

func criticReviewer(reply string, err error) *AdversarialReviewer {
	rev := NewAdversarialReviewer(persona.NewRegistry(""))
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) { return reply, err })
	return rev
}

func TestCriticApprovesOnlyWhenRulesAndModelAgree(t *testing.T) {
	v := criticReviewer("```json\n{\"approved\":true,\"blocking\":[],\"warnings\":[\"no test for empty password\"]}\n```", nil).Evaluate(criticCtx())
	if !v.Approved || v.Status != StatusApproved || !strings.Contains(v.Summary, "model review") || len(v.Warnings) == 0 {
		t.Fatalf("expected approval with model note, got %+v", v)
	}

	rc := criticCtx()
	rc.TestResults = []*sandbox.ExecResult{{Command: "go test", ExitCode: 1}}
	if criticReviewer(`{"approved":true}`, nil).Evaluate(rc).Approved {
		t.Fatal("a failing test must reject even if the critic approves")
	}
}

func TestCriticRejectionSurfacesBlockingIssues(t *testing.T) {
	v := criticReviewer(`{"approved":false,"blocking":["criterion 1 unmet: no token issued"]}`, nil).Evaluate(criticCtx())
	if v.Approved || v.Status != StatusRejected || !strings.Contains(strings.Join(v.BlockingIssues, "\n"), "criterion 1 unmet") {
		t.Fatalf("got %+v", v)
	}
	// approved=true with blocking issues is still a rejection
	if criticReviewer(`{"approved":true,"blocking":["x"]}`, nil).Evaluate(criticCtx()).Approved {
		t.Fatal("blocking issues must win over approved=true")
	}
}

func TestCriticFailsClosed(t *testing.T) {
	// Error -> StatusUnreviewed
	vErr := criticReviewer("", errors.New("rate limited")).Evaluate(criticCtx())
	if vErr.Approved || vErr.Status != StatusUnreviewed {
		t.Fatalf("expected StatusUnreviewed on critic error, got: %+v", vErr)
	}

	// Unparsable -> StatusUnreviewed
	vGarbage := criticReviewer("looks fine to me!", nil).Evaluate(criticCtx())
	if vGarbage.Approved || vGarbage.Status != StatusUnreviewed {
		t.Fatalf("expected StatusUnreviewed on unparsable critic, got: %+v", vGarbage)
	}
}

func TestCriticHardRejectsTruncatedDiff(t *testing.T) {
	called := false
	rev := NewAdversarialReviewer(persona.NewRegistry(""))
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		called = true
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})

	// Diff within default limit
	rc := criticCtx()
	rc.Diff = strings.Repeat("a", 1000)
	v := rev.Evaluate(rc)
	if !v.Approved || v.Status != StatusApproved || !called {
		t.Fatalf("expected approval for diff within limits, got: %+v", v)
	}

	// Diff exceeding custom limit
	called = false
	rev.SetMaxCriticDiffBytes(500)
	v = rev.Evaluate(rc)
	if v.Approved || v.Status != StatusRejected {
		t.Fatal("expected rejection when diff exceeds byte limit")
	}
	if called {
		t.Fatal("model should never be called when diff is truncated")
	}
	foundTruncatedMsg := false
	for _, issue := range v.BlockingIssues {
		if strings.Contains(issue, "DIFF_TRUNCATED: model review cannot be authoritative on an incomplete diff") {
			foundTruncatedMsg = true
			break
		}
	}
	if !foundTruncatedMsg {
		t.Fatalf("expected DIFF_TRUNCATED message in blocking issues, got: %v", v.BlockingIssues)
	}
}


