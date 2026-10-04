package workflow

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"strings"
	"time"

	"kritix/pkg/impact"
)

// This file must stay free of LLM-capable imports (pkg/model, pkg/spec, pkg/agent):
// the PR gate is deterministic. TestSmokeGuardImportsNoLLM enforces it.

// ErrSmokeFailed is returned when the impacted Playwright specs fail, time out
// or produce output that cannot be parsed. The gate never passes on doubt.
var ErrSmokeFailed = errors.New("pr-smoke-guard: impacted smoke tests did not pass")

// Context keys read by the pr-smoke-guard blueprint.
const (
	VarRepoDir      = "repo_dir"
	VarBaseRef      = "base_ref"
	VarMapFile      = "map_file"
	varSpecs        = "impacted_specs"
	varSmokeState   = "smoke_state"
	varGitHubAPIURL = "github_api_url"
)

type PRSmokeGuardBlueprint struct{}

func (b *PRSmokeGuardBlueprint) Descriptor() BlueprintDescriptor {
	return BlueprintDescriptor{
		ID:             "pr-smoke-guard",
		Name:           "PR Smoke Guard",
		Category:       "ci",
		Tier:           Tier1PRGate,
		Description:    "Deterministic git-diff impact analysis; runs only the precompiled Playwright specs mapped to changed files, within 120s, with zero LLM calls. Unmapped changes fail closed",
		TargetAudience: "CI/CD Pipelines, GitHub Actions, GitLab CI",
		DefaultTimeout: "120s",
		ZeroLLM:        true,
		FastPath:       true,
	}
}

func (b *PRSmokeGuardBlueprint) BuildDAG() (*DAG, error) {
	dag := NewDAG("pr-smoke-guard", "Fast PR Smoke Quality Gate")
	dag.SetTier(Tier1PRGate)

	dag.AddNode("git_diff_impact", &GitDiffImpactBlock{})
	dag.AddNode("smoke_runner", &ExecSmokeBlock{}, "git_diff_impact")
	dag.AddNode("github_status", &SyncGitHubStatusBlock{}, "smoke_runner")
	// Report failures too: a red gate must reach the PR, not just a missing check.
	dag.Nodes["github_status"].Condition = "always"
	return dag, nil
}

type GitDiffImpactBlock struct{}

func (b *GitDiffImpactBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{ID: "analysis.git-diff", Name: "Analyze PR Diff Impact", Category: "analysis"}
}

func (b *GitDiffImpactBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	return smokeImpact(ctx, bCtx)
}

type ExecSmokeBlock struct{}

func (b *ExecSmokeBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{ID: "exec.smoke", Name: "Selective Smoke Test Execution", Category: "exec"}
}

func (b *ExecSmokeBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	return smokeRun(ctx, bCtx)
}

type SyncGitHubStatusBlock struct{}

func (b *SyncGitHubStatusBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{ID: "sync.github-status", Name: "Post GitHub PR Status Check", Category: "sync"}
}

func (b *SyncGitHubStatusBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	return smokeStatus(ctx, bCtx)
}

func ctxString(bCtx *Context, key string) string {
	v, _ := bCtx.Get(key)
	s, _ := v.(string)
	return s
}

func smokeImpact(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	repo, base, mapFile := ctxString(bCtx, VarRepoDir), ctxString(bCtx, VarBaseRef), ctxString(bCtx, VarMapFile)
	if repo == "" {
		repo = "."
	}
	if base == "" || mapFile == "" {
		return nil, errors.New("pr-smoke-guard: base_ref and map_file are required")
	}
	changed, err := impact.ChangedFiles(ctx, repo, base)
	if err != nil {
		return nil, err
	}
	if len(changed) == 0 {
		return nil, errors.New("pr-smoke-guard: git diff is empty; refusing to pass a gate that saw no change (check --base)")
	}
	specs, err := impact.MapToTests(changed, mapFile)
	if err != nil {
		return nil, err
	}
	for _, s := range specs {
		if strings.HasPrefix(s, "-") {
			return nil, fmt.Errorf("pr-smoke-guard: spec path %q looks like a flag", s)
		}
	}
	bCtx.Set(varSpecs, specs)
	return &BlockResult{Status: StatusPassed, Message: fmt.Sprintf("%d changed files -> %d impacted specs", len(changed), len(specs))}, nil
}

// playwrightStats is the subset of Playwright's JSON reporter output we read.
type playwrightStats struct {
	Stats struct {
		Expected   int `json:"expected"`
		Unexpected int `json:"unexpected"`
		Flaky      int `json:"flaky"`
	} `json:"stats"`
}

func smokeRun(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	v, ok := bCtx.Get(varSpecs)
	if !ok || v == nil {
		return &BlockResult{
			Status:  StatusFailed,
			Message: "pr-smoke-guard: missing input specs list in context (ensure git_diff_impact ran first)",
			Error:   errors.New("missing specs input"),
		}, errors.New("missing specs input")
	}
	specs, _ := v.([]string)
	if len(specs) == 0 {
		bCtx.Set(varSmokeState, "success")
		return &BlockResult{Status: StatusPassed, Message: "no impacted tests: every changed file is on the ignore list"}, nil
	}
	cmd := exec.CommandContext(ctx, "npx", append([]string{"playwright", "test", "--reporter=json"}, specs...)...)
	if repo := ctxString(bCtx, VarRepoDir); repo != "" {
		cmd.Dir = repo
	}
	cmd.WaitDelay = 2 * time.Second
	var out bytes.Buffer
	cmd.Stdout = &out
	runErr := cmd.Run()
	if ctx.Err() != nil {
		bCtx.Set(varSmokeState, "failure")
		return nil, fmt.Errorf("%w: %v", ErrSmokeFailed, ctx.Err())
	}
	var st playwrightStats
	if err := json.Unmarshal(out.Bytes(), &st); err != nil {
		bCtx.Set(varSmokeState, "failure")
		return nil, fmt.Errorf("%w: unparseable playwright output (run error: %v)", ErrSmokeFailed, runErr)
	}
	s := st.Stats
	if s.Unexpected > 0 || s.Flaky > 0 || runErr != nil || s.Expected == 0 {
		bCtx.Set(varSmokeState, "failure")
		return nil, fmt.Errorf("%w: passed=%d failed=%d flaky=%d (exit: %v)", ErrSmokeFailed, s.Expected, s.Unexpected, s.Flaky, runErr)
	}
	bCtx.Set(varSmokeState, "success")
	return &BlockResult{Status: StatusPassed, Message: fmt.Sprintf("%d specs, %d tests passed", len(specs), s.Expected)}, nil
}

func smokeStatus(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	token, repo, sha := os.Getenv("GITHUB_TOKEN"), os.Getenv("GITHUB_REPOSITORY"), os.Getenv("GITHUB_SHA")
	if token == "" || repo == "" || sha == "" {
		return &BlockResult{Status: StatusSkipped, Message: "skipped: not in GitHub CI (GITHUB_TOKEN/GITHUB_REPOSITORY/GITHUB_SHA unset)"}, nil
	}
	state, desc := "failure", "Kritix smoke gate failed"
	if ctxString(bCtx, varSmokeState) == "success" {
		state, desc = "success", "Kritix smoke gate passed"
	}
	api := ctxString(bCtx, varGitHubAPIURL)
	if api == "" {
		api = "https://api.github.com"
	}
	body, _ := json.Marshal(map[string]string{"state": state, "context": "kritix/pr-smoke-guard", "description": desc})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, fmt.Sprintf("%s/repos/%s/statuses/%s", api, repo, sha), bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("github status post failed: %v", err) // err carries URL only, never the token
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		return nil, fmt.Errorf("github status post: unexpected HTTP %d", resp.StatusCode)
	}
	return &BlockResult{Status: StatusPassed, Message: "posted commit status: " + state}, nil
}
