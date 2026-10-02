package forge

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"artix/pkg/coder"
	"artix/pkg/git"
	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
	"artix/pkg/steering"
)

// RemoteWorkerTask configures an autonomous server execution job.
type RemoteWorkerTask struct {
	Target       RemoteRepoTarget                        `json:"target"`
	Auth         ForgeAuth                               `json:"auth"`
	Prompt       string                                  `json:"prompt"`
	Domain       string                                  `json:"domain"`
	MockPatchGen func(round int, feedback string) string // for tests
}

// RemoteWorkerResult contains the completed job artifacts.
type RemoteWorkerResult struct {
	Success     bool                 `json:"success"`
	Branch      string               `json:"branch"`
	Spec        *spec.StorySpec      `json:"spec"`
	PullRequest *PullRequestResponse `json:"pullRequest,omitempty"`
	LoopResult  *coder.LoopResult    `json:"loopResult,omitempty"`
	Error       string               `json:"error,omitempty"`
}

// RemoteWorker coordinates server-side autonomous repository tasks.
type RemoteWorker struct {
	workRoot string
	registry *persona.Registry
}

// NewRemoteWorker creates a remote worker using a base directory for ephemeral clones.
func NewRemoteWorker(workRoot string, registry *persona.Registry) *RemoteWorker {
	_ = os.MkdirAll(workRoot, 0755)
	return &RemoteWorker{
		workRoot: workRoot,
		registry: registry,
	}
}

// Execute runs the full autonomous pipeline: Clone -> Plan -> Code -> Verify -> Push -> PR.
func (w *RemoteWorker) Execute(ctx context.Context, task *RemoteWorkerTask) *RemoteWorkerResult {
	res := &RemoteWorkerResult{
		Success: false,
	}

	taskID := fmt.Sprintf("job-%d", time.Now().Unix())
	cloneDir := filepath.Join(w.workRoot, taskID)
	_ = os.MkdirAll(cloneDir, 0755)
	defer os.RemoveAll(cloneDir)

	// 1. Clone repository
	authURL := formatAuthURL(task.Target.CloneURL, task.Auth)
	cloneCmd := exec.Command("git", "clone", "--depth", "50", authURL, cloneDir)
	if err := cloneCmd.Run(); err != nil {
		res.Error = fmt.Sprintf("failed to clone repository: %v", err)
		return res
	}

	// 2. Discover repo context & create working branch
	repoCtx, err := repo.DetectContext(cloneDir)
	if err != nil {
		res.Error = fmt.Sprintf("failed to detect repo context: %v", err)
		return res
	}

	driver := git.NewDriver(cloneDir)
	branchName, err := driver.CreateWorkingBranch(taskID)
	if err != nil {
		res.Error = fmt.Sprintf("failed to create working branch: %v", err)
		return res
	}
	res.Branch = branchName

	// 3. Plan: Stakeholder Council generates Story Spec
	council := spec.NewCouncil(w.registry)
	pCtx := &spec.PlanningContext{
		StoryPrompt: task.Prompt,
		RepoContext: repoCtx,
		Style:       spec.StyleStandard,
	}

	storySpec, err := council.Plan(ctx, pCtx)
	if err != nil {
		res.Error = fmt.Sprintf("planning council failed: %v", err)
		return res
	}
	res.Spec = storySpec

	// Write spec to repo
	specsDir := filepath.Join(cloneDir, "docs", "specs")
	_ = os.MkdirAll(specsDir, 0755)
	_ = os.WriteFile(filepath.Join(specsDir, fmt.Sprintf("%s.md", storySpec.ID)), []byte(storySpec.RawMarkdown), 0644)

	// 4. Code: Domain Coder & Reviewer loop
	domainCoder, err := coder.NewDomainCoder(task.Domain, w.registry)
	if err != nil {
		res.Error = fmt.Sprintf("failed to initialize domain coder: %v", err)
		return res
	}

	rev := reviewer.NewAdversarialReviewer(w.registry)
	box := sandbox.NewSandbox(cloneDir)
	coord := coder.NewCoordinator(domainCoder, rev, driver, box)

	// Ingest steering
	agg := steering.NewAggregator(cloneDir)
	rules, _ := agg.CollectLocalRules()
	binder := steering.NewBinder(steering.SteeringConfig{}, rules)
	coderSteering := binder.CompilePersonaSteering(task.Domain)
	revSteering := binder.CompilePersonaSteering("adversarial_code_reviewer")

	opts := &coder.LoopOptions{
		MaxRounds:    3,
		Autonomy:     coder.AutonomyAutonomous,
		MockPatchGen: task.MockPatchGen,
	}

	loopRes := coord.Run(ctx, storySpec, repoCtx, coderSteering, revSteering, opts)
	res.LoopResult = loopRes

	if !loopRes.Success {
		res.Error = fmt.Sprintf("coder loop failed: %s", loopRes.Error)
		return res
	}

	// 5. Open Pull Request / Merge Request
	var client ForgeClient
	if task.Auth.Type == ForgeGitLab {
		client = NewGitLabClient(task.Auth)
	} else {
		client = NewGitHubClient(task.Auth)
	}

	prReq := &PullRequestRequest{
		Title: fmt.Sprintf("feat: %s", storySpec.Title),
		Body:  storySpec.RawMarkdown,
		Head:  branchName,
		Base:  task.Target.Branch,
	}
	if prReq.Base == "" {
		prReq.Base = "main"
	}

	prResp, err := client.CreatePullRequest(&task.Target, prReq)
	if err != nil {
		// Log PR opening failure without failing entire local git work
		res.Error = fmt.Sprintf("code committed but PR creation failed: %v", err)
	} else {
		res.PullRequest = prResp
	}

	res.Success = loopRes.Success
	return res
}

func formatAuthURL(rawURL string, auth ForgeAuth) string {
	if auth.Token == "" || strings.HasPrefix(rawURL, "git@") {
		return rawURL
	}

	// Embed token for https cloning: https://x-access-token:<token>@github.com/...
	if strings.HasPrefix(rawURL, "https://") {
		trimmed := strings.TrimPrefix(rawURL, "https://")
		return fmt.Sprintf("https://oauth2:%s@%s", auth.Token, trimmed)
	}

	return rawURL
}
