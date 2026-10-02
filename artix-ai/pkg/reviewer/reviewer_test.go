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
	if !verdict.Approved {
		t.Fatalf("expected approved=true, got false: %s", verdict.ActionableFeedback)
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
	if verdict.Approved {
		t.Fatalf("expected approved=false on test failure")
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
	if verdict.Approved {
		t.Errorf("expected approved=false due to taboo violation")
	}
}

func TestReviewerIsHonestAboutWhatItChecked(t *testing.T) {
	rev := NewAdversarialReviewer(persona.NewRegistry(""))
	diff := "--- a/f.go\n+++ b/f.go\n@@ -1 +1 @@\n-a\n+b\n"

	noTests := rev.Evaluate(&ReviewContext{Diff: diff})
	if !noTests.Approved {
		t.Fatalf("diff checks alone still approve: %+v", noTests)
	}
	if len(noTests.Warnings) == 0 || !strings.Contains(noTests.Warnings[0], "No test commands") {
		t.Errorf("approving without any tests must warn: %+v", noTests.Warnings)
	}
	if strings.Contains(noTests.Summary, "satisfies all acceptance criteria") || !strings.Contains(noTests.Summary, "not evaluated") {
		t.Errorf("summary must not claim checks it does not perform: %q", noTests.Summary)
	}

	if v := rev.Evaluate(&ReviewContext{Diff: "  \n"}); v.Approved {
		t.Error("empty diff must be rejected")
	}
}

func TestReviewerReportsTaboosItCannotEnforce(t *testing.T) {
	rev := NewAdversarialReviewer(persona.NewRegistry(""))
	sc := &steering.PersonaSteeringContext{}
	sc.Taboos.ForbiddenArguments = []string{"Never use raw SQLite", "Never put business logic in composables"}
	v := rev.Evaluate(&ReviewContext{
		Diff:            "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-a\n+import android.database.sqlite.SQLiteDatabase\n",
		TestResults:     []*sandbox.ExecResult{{Command: "t", ExitCode: 0}},
		SteeringContext: sc,
	})
	if v.Approved {
		t.Error("the raw-SQLite taboo has a built-in check and must block")
	}
	joined := strings.Join(v.Warnings, "|")
	if !strings.Contains(joined, "business logic in composables") || strings.Contains(joined, "raw SQLite") {
		t.Errorf("only the unenforceable taboo should be reported as unchecked: %v", v.Warnings)
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
	if !v.Approved || !strings.Contains(v.Summary, "model review") || len(v.Warnings) == 0 {
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
	if v.Approved || !strings.Contains(strings.Join(v.BlockingIssues, "\n"), "criterion 1 unmet") {
		t.Fatalf("got %+v", v)
	}
	// approved=true with blocking issues is still a rejection
	if criticReviewer(`{"approved":true,"blocking":["x"]}`, nil).Evaluate(criticCtx()).Approved {
		t.Fatal("blocking issues must win over approved=true")
	}
}

func TestCriticFailsClosed(t *testing.T) {
	for name, rev := range map[string]*AdversarialReviewer{
		"error":     criticReviewer("", errors.New("rate limited")),
		"garbage":   criticReviewer("looks fine to me!", nil),
		"bad field": criticReviewer(`{"ok":true}`, nil),
	} {
		if v := rev.Evaluate(criticCtx()); v.Approved {
			t.Fatalf("%s: unavailable or unparsable critic must not approve", name)
		}
	}
}

func TestNoCriticKeepsRuleBasedSummary(t *testing.T) {
	v := NewAdversarialReviewer(persona.NewRegistry("")).Evaluate(criticCtx())
	if !v.Approved || !strings.Contains(v.Summary, "Acceptance criteria are not evaluated") {
		t.Fatalf("got %+v", v)
	}
}
