package reviewer

import (
	"testing"

	"dialex/pkg/model"
	"kritix/pkg/persona"
	"kritix/pkg/sandbox"
	"kritix/pkg/steering"
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
