package coder

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"

	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
)

func TestG5_MissingPriceRefusesWhenUSDCapSet(t *testing.T) {
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
		ID:           "S-G5-PRICE",
		Title:        "Price Table Enforcement",
		TestCommands: []string{"grep '1' counter.txt"},
	}

	// USD cap set, but PriceTable is empty and CostPer1kTokens is 0 (no hardcoded default allowed)
	budget := &TokenBudget{
		MaxStoryCost:    5.0, // USD cap set
		CostPer1kTokens: 0,   // no hardcoded default
	}

	opts := &LoopOptions{
		MaxRounds:             1,
		Budget:                budget,
		TestCommandsConfirmed: true,
		MockPatchGen: func(round int, feedback string) string {
			return "--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
	}

	res := coord.Run(context.Background(), storySpec, &repo.RepositoryContext{RootDir: tempDir}, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected missing price with USD cap to be refused, but succeeded")
	}
	if !strings.Contains(strings.ToLower(res.Error), "price") {
		t.Fatalf("expected error mentioning missing price table / pricing, got: %s", res.Error)
	}
}

func TestSharedLedgerProcessWorker(t *testing.T) {
	if os.Getenv("ARTIX_TEST_LEDGER_WORKER") != "1" {
		return
	}
	ledgerPath := os.Getenv("TEST_LEDGER_PATH")
	tokens, _ := strconv.Atoi(os.Getenv("TEST_TOKENS"))
	b := &TokenBudget{
		TeamID:     "race-team",
		LedgerPath: ledgerPath,
	}
	b.RecordRoundUsage(tokens, 0.1)
	os.Exit(0)
}

func TestG5_TwoProcessesRacingPastCap_FileLockedLedger(t *testing.T) {
	ledgerDir := t.TempDir()
	ledgerPath := filepath.Join(ledgerDir, "shared-ledger.json")

	processes := 8
	tokensPerProc := 100

	var wg sync.WaitGroup
	for i := 0; i < processes; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			cmd := exec.Command(os.Args[0], "-test.run=TestSharedLedgerProcessWorker")
			cmd.Env = append(os.Environ(),
				"ARTIX_TEST_LEDGER_WORKER=1",
				"TEST_LEDGER_PATH="+ledgerPath,
				fmt.Sprintf("TEST_TOKENS=%d", tokensPerProc),
			)
			out, err := cmd.CombinedOutput()
			if err != nil {
				t.Errorf("worker %d failed: %v: %s", id, err, string(out))
			}
		}(i)
	}
	wg.Wait()

	data, err := os.ReadFile(ledgerPath)
	if err != nil {
		t.Fatalf("read ledger: %v", err)
	}
	var state sharedLedgerData
	if err := json.Unmarshal(data, &state); err != nil {
		t.Fatalf("unmarshal ledger: %v", err)
	}

	totalRecorded := state.TeamTokens["race-team"]
	if totalRecorded != processes*tokensPerProc {
		t.Fatalf("expected atomic ledger total %d tokens, got %d (lost updates under cross-process race)", processes*tokensPerProc, totalRecorded)
	}
}

func TestG5_LedgerRestartPersistence(t *testing.T) {
	ledgerDir := t.TempDir()
	ledgerPath := filepath.Join(ledgerDir, "persist-ledger.json")

	// Process 1 records usage
	b1 := &TokenBudget{
		TeamID:     "team-alpha",
		LedgerPath: ledgerPath,
	}
	b1.RecordRoundUsage(500, 1.25)

	// Process 2 starts fresh (restart simulation)
	b2 := &TokenBudget{
		TeamID:     "team-alpha",
		LedgerPath: ledgerPath,
	}
	b2.SyncWithSharedLedger()

	if b2.UsedTeamTokens != 500 {
		t.Fatalf("expected 500 team tokens persisted, got: %d", b2.UsedTeamTokens)
	}
	if b2.UsedTeamCost != 1.25 {
		t.Fatalf("expected 1.25 team cost persisted, got: %f", b2.UsedTeamCost)
	}
}

func TestG5_CapExceededMidRoundAbortsBeforeNextModelCall(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	reg := persona.NewRegistry("")
	coder, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	criticCalls := 0
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		criticCalls++
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(coder, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "S-G5-MIDROUND",
		Title:        "Mid Round Abort",
		TestCommands: []string{"grep '1' counter.txt"},
	}

	// Budget that allows coder prompt but exhausts before reviewer
	budget := &TokenBudget{
		MaxStoryTokens:  5, // extremely tiny
		CostPer1kTokens: 0.01,
	}

	opts := &LoopOptions{
		MaxRounds:             2,
		Budget:                budget,
		TestCommandsConfirmed: true,
		MockPatchGen: func(round int, feedback string) string {
			return "--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
	}

	res := coord.Run(context.Background(), storySpec, &repo.RepositoryContext{RootDir: tempDir}, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected mid-round budget exhaustion to fail, but succeeded")
	}
	// Critic model call MUST NOT have been made once budget exhausted
	if criticCalls > 0 {
		t.Fatalf("expected mid-round abort BEFORE critic model call, but critic was called %d times", criticCalls)
	}
}

func TestG5_ReconciliationWithProviderReportedUsage(t *testing.T) {
	b := &TokenBudget{
		CostPer1kTokens: 0.005,
	}

	// Text estimation estimated 100 tokens, but provider API returned 1250 tokens
	pu := &ProviderUsage{
		PromptTokens:     1000,
		CompletionTokens: 250,
		TotalTokens:      1250,
	}

	b.RecordRoundUsage(100, 0.0005, pu)

	if b.UsedStoryTokens != 1250 {
		t.Fatalf("expected reconciliation to record 1250 provider tokens, got: %d", b.UsedStoryTokens)
	}
	expectedCost := (1250.0 / 1000.0) * 0.005
	if b.UsedStoryCost != expectedCost {
		t.Fatalf("expected reconciled cost %f, got: %f", expectedCost, b.UsedStoryCost)
	}
}
