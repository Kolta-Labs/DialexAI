package workflow

import (
	"context"
	"errors"
	"go/parser"
	"go/token"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"kritix/pkg/impact"
)

func gitIn(t *testing.T, dir string, args ...string) {
	t.Helper()
	cmd := exec.Command("git", append([]string{"-C", dir, "-c", "user.email=t@t", "-c", "user.name=t"}, args...)...)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %v: %v\n%s", args, err, out)
	}
}

// smokeRepo makes a repo whose branch differs from main by the given files, plus a map file.
func smokeRepo(t *testing.T, changed ...string) (repo, mapFile string) {
	t.Helper()
	repo = t.TempDir()
	gitIn(t, repo, "init", "-b", "main")
	os.WriteFile(filepath.Join(repo, "README.md"), []byte("x"), 0o644)
	gitIn(t, repo, "add", ".")
	gitIn(t, repo, "commit", "-m", "base")
	gitIn(t, repo, "checkout", "-b", "feat")
	for _, f := range changed {
		os.MkdirAll(filepath.Dir(filepath.Join(repo, f)), 0o755)
		os.WriteFile(filepath.Join(repo, f), []byte("y"), 0o644)
	}
	gitIn(t, repo, "add", ".")
	gitIn(t, repo, "commit", "-m", "change")
	mapFile = filepath.Join(t.TempDir(), "map.json")
	os.WriteFile(mapFile, []byte(`{"globs":{"src/checkout/**":["tests/e2e/checkout.spec.ts"]},"ignore":["docs/**"]}`), 0o644)
	return
}

// fakeNpx installs an npx shim on PATH that records its args and runs body.
func fakeNpx(t *testing.T, body string) (argsFile string) {
	t.Helper()
	bin := t.TempDir()
	argsFile = filepath.Join(bin, "args.txt")
	script := "#!/bin/sh\necho \"$@\" > " + argsFile + "\n" + body + "\n"
	if err := os.WriteFile(filepath.Join(bin, "npx"), []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", bin+string(os.PathListSeparator)+os.Getenv("PATH"))
	return
}

func runSmoke(t *testing.T, repo, mapFile string, budget time.Duration) (*DAGResult, error) {
	t.Helper()
	dag, _ := (&PRSmokeGuardBlueprint{}).BuildDAG()
	if budget > 0 {
		dag.SetMaxTimeBudget(budget)
	}
	t.Setenv("GITHUB_TOKEN", "")
	return dag.Execute(context.Background(), NewContext(map[string]interface{}{VarRepoDir: repo, VarBaseRef: "main", VarMapFile: mapFile}))
}

const okJSON = `echo '{"stats":{"expected":2,"unexpected":0,"flaky":0}}'`

func TestSmokeGuardRunsOnlyMappedSpecs(t *testing.T) {
	repo, m := smokeRepo(t, "src/checkout/a.ts")
	args := fakeNpx(t, okJSON)
	res, err := runSmoke(t, repo, m, 0)
	if err != nil || !res.Success {
		t.Fatalf("want pass, got %v %+v", err, res)
	}
	got, _ := os.ReadFile(args)
	if strings.TrimSpace(string(got)) != "playwright test --reporter=json tests/e2e/checkout.spec.ts" {
		t.Errorf("unexpected npx args: %s", got)
	}
}

func TestSmokeGuardUnmappedFailsClosed(t *testing.T) {
	repo, m := smokeRepo(t, "src/checkout/a.ts", "src/other/b.ts")
	fakeNpx(t, okJSON)
	res, _ := runSmoke(t, repo, m, 0)
	if res.Success || !errors.Is(res.NodeResults["git_diff_impact"].Error, impact.ErrUnmappedChange) {
		t.Fatalf("want unmapped failure, got %+v", res.NodeResults)
	}
}

func TestSmokeGuardAllIgnoredPasses(t *testing.T) {
	repo, m := smokeRepo(t, "docs/a.md")
	args := fakeNpx(t, okJSON)
	res, err := runSmoke(t, repo, m, 0)
	if err != nil || !res.Success {
		t.Fatalf("want pass, got %v", err)
	}
	if _, err := os.Stat(args); err == nil {
		t.Error("npx must not run when nothing is impacted")
	}
}

func TestSmokeGuardFailingSpecFails(t *testing.T) {
	repo, m := smokeRepo(t, "src/checkout/a.ts")
	fakeNpx(t, `echo '{"stats":{"expected":1,"unexpected":1,"flaky":0}}'; exit 1`)
	if res, _ := runSmoke(t, repo, m, 0); res.Success {
		t.Fatal("failing spec must fail the gate")
	}
}

func TestSmokeGuardGarbageOutputFails(t *testing.T) {
	repo, m := smokeRepo(t, "src/checkout/a.ts")
	fakeNpx(t, `echo not-json`)
	if res, _ := runSmoke(t, repo, m, 0); res.Success {
		t.Fatal("unparseable output must fail the gate")
	}
}

func TestSmokeGuardTimeoutFails(t *testing.T) {
	repo, m := smokeRepo(t, "src/checkout/a.ts")
	fakeNpx(t, `exec sleep 10`)
	start := time.Now()
	res, err := runSmoke(t, repo, m, 500*time.Millisecond)
	if err == nil && res.Success {
		t.Fatal("timeout must fail the gate")
	}
	if time.Since(start) > 5*time.Second {
		t.Error("timeout not enforced")
	}
}

func TestSmokeGuardGitHubStatus(t *testing.T) {
	repo, m := smokeRepo(t, "src/checkout/a.ts")
	fakeNpx(t, `echo '{"stats":{"expected":1,"unexpected":1,"flaky":0}}'; exit 1`)
	var path, auth, body string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, auth = r.URL.Path, r.Header.Get("Authorization")
		b := make([]byte, 512)
		n, _ := r.Body.Read(b)
		body = string(b[:n])
		w.WriteHeader(http.StatusCreated)
	}))
	defer srv.Close()
	dag, _ := (&PRSmokeGuardBlueprint{}).BuildDAG()
	t.Setenv("GITHUB_TOKEN", "tok")
	t.Setenv("GITHUB_REPOSITORY", "o/r")
	t.Setenv("GITHUB_SHA", "abc")
	res, _ := dag.Execute(context.Background(), NewContext(map[string]interface{}{VarRepoDir: repo, VarBaseRef: "main", VarMapFile: m, varGitHubAPIURL: srv.URL}))
	if res.Success {
		t.Fatal("gate should be red")
	}
	if path != "/repos/o/r/statuses/abc" || auth != "Bearer tok" || !strings.Contains(body, `"failure"`) {
		t.Errorf("bad status post: %s %s %s", path, auth, body)
	}
}

func TestSmokeGuardImportsNoLLM(t *testing.T) {
	f, err := parser.ParseFile(token.NewFileSet(), "smoke.go", nil, parser.ImportsOnly)
	if err != nil {
		t.Fatal(err)
	}
	for _, imp := range f.Imports {
		for _, bad := range []string{"pkg/model", "pkg/spec", "pkg/agent", "pkg/context"} {
			if strings.Contains(imp.Path.Value, bad) {
				t.Errorf("smoke.go imports LLM-capable package %s", imp.Path.Value)
			}
		}
	}
}

func TestTier3RefusedInBlockingTiers(t *testing.T) {
	for _, id := range []string{"ticket-to-ship", "nightly-deep-audit"} {
		bp, _ := GetBlueprint(id)
		for _, tier := range []PipelineTier{Tier1PRGate, Tier2MergeGate} {
			if err := ValidateBlueprintForTier(bp.Descriptor(), tier); !errors.Is(err, ErrTierMismatch) {
				t.Errorf("%s in %s: want ErrTierMismatch, got %v", id, tier, err)
			}
		}
		if err := ValidateBlueprintForTier(bp.Descriptor(), Tier3Nightly); err != nil {
			t.Errorf("%s in nightly: %v", id, err)
		}
	}
}
