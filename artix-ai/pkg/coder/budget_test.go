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
	"time"

	"artix/pkg/persona"
	"artix/pkg/pilot"
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

// TestR2_5_ReconciliationThroughCoordinatorRunAndDeterministicModelPrice asserts that
// when a provider reports actual usage differing from text estimation during a loop run,
// the coordinator wires that usage into RecordRoundUsage so the ledger records the provider figure,
// and uses the exact price from PriceTable matching opts.Model deterministically.
func TestR2_5_ReconciliationThroughCoordinatorRunAndDeterministicModelPrice(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	reg := persona.NewRegistry("")
	c, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(c, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "S-R2-5-RECON",
		Title:        "Provider Usage Reconciliation",
		TestCommands: []string{"grep '1' counter.txt"},
	}

	ledgerPath := filepath.Join(tempDir, ".artix", "ledger.json")
	budget := &TokenBudget{
		LedgerPath: ledgerPath,
		TeamID:     "team-recon",
		PriceTable: map[string]float64{
			"gpt-4o-cheap":     0.001,
			"gpt-4o-targeted":  0.020, // $0.020 per 1k tokens = $0.00002 per token
			"claude-expensive": 0.100,
		},
		MaxStoryCost: 10.0,
	}

	reportedCoderTokens := 8500
	mockCoderUsage := &ProviderUsage{
		PromptTokens:     7000,
		CompletionTokens: 1500,
		TotalTokens:      reportedCoderTokens,
	}
	reportedReviewerTokens := 500
	mockReviewerUsage := &ProviderUsage{
		PromptTokens:     400,
		CompletionTokens: 100,
		TotalTokens:      reportedReviewerTokens,
	}
	totalReportedTokens := reportedCoderTokens + reportedReviewerTokens

	opts := &LoopOptions{
		MaxRounds:             1,
		Budget:                budget,
		Model:                 "gpt-4o-targeted",
		ReviewerModel:         "gpt-4o-targeted",
		TestCommandsConfirmed: true,
		MockPatchGen: func(round int, feedback string) string {
			return "--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
		CoderUsageTracker: func() *ProviderUsage {
			return mockCoderUsage
		},
		ReviewerUsageTracker: func() *ProviderUsage {
			return mockReviewerUsage
		},
	}

	res := coord.Run(context.Background(), storySpec, &repo.RepositoryContext{RootDir: tempDir}, nil, nil, opts)
	if !res.Success {
		t.Fatalf("expected successful coordinator run, failed with: %s", res.Error)
	}

	// 1. Budget's in-memory counters must reflect provider tokens, NOT estimate
	if budget.UsedStoryTokens != totalReportedTokens {
		t.Fatalf("expected in-memory budget to record provider tokens %d, got: %d", totalReportedTokens, budget.UsedStoryTokens)
	}

	// Expected cost: 9000 tokens * (0.020 / 1000) = $0.180
	expectedCost := (float64(totalReportedTokens) / 1000.0) * 0.020
	if budget.UsedStoryCost < expectedCost-0.0001 || budget.UsedStoryCost > expectedCost+0.0001 {
		t.Fatalf("expected cost $%.4f using model gpt-4o-targeted, got: $%.4f", expectedCost, budget.UsedStoryCost)
	}

	// 2. The file-locked ledger on disk must also equal the provider figure
	ledgerBytes, err := os.ReadFile(ledgerPath)
	if err != nil {
		t.Fatalf("failed to read ledger file: %v", err)
	}
	var ledgerData sharedLedgerData
	if err := json.Unmarshal(ledgerBytes, &ledgerData); err != nil {
		t.Fatalf("failed to parse ledger json: %v", err)
	}
	if ledgerData.TeamTokens["team-recon"] != totalReportedTokens {
		t.Fatalf("expected team tokens in on-disk ledger to be %d, got: %d", totalReportedTokens, ledgerData.TeamTokens["team-recon"])
	}
}

// TestR2_5_MissingModelPriceRefusesWhenUSDCapSet asserts that when a USD cap is set,
// if opts.Model is not found in PriceTable, the loop fails closed and refuses immediately.
func TestR2_5_MissingModelPriceRefusesWhenUSDCapSet(t *testing.T) {
	tempDir, driver := setupTestRepo(t)
	defer os.RemoveAll(tempDir)

	reg := persona.NewRegistry("")
	c, _ := NewDomainCoder("backend_engineer", reg)
	rev := reviewer.NewAdversarialReviewer(reg)
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(c, rev, driver, box)

	storySpec := &spec.StorySpec{
		ID:           "S-R2-5-MISSING",
		Title:        "Missing Model Price Refusal",
		TestCommands: []string{"grep '1' counter.txt"},
	}

	budget := &TokenBudget{
		MaxStoryCost: 5.0, // USD cap set
		PriceTable: map[string]float64{
			"gpt-4o": 0.015,
		},
	}

	// Pass an unknown model not in the price table
	opts := &LoopOptions{
		MaxRounds:             1,
		Budget:                budget,
		Model:                 "mystery-model-404",
		TestCommandsConfirmed: true,
		MockPatchGen: func(round int, feedback string) string {
			return "--- a/counter.txt\n+++ b/counter.txt\n@@ -1 +1 @@\n-0\n+1\n"
		},
	}

	res := coord.Run(context.Background(), storySpec, &repo.RepositoryContext{RootDir: tempDir}, nil, nil, opts)
	if res.Success {
		t.Fatalf("expected run with unknown model under USD cap to be refused, but succeeded")
	}
	if !strings.Contains(strings.ToLower(res.Error), "mystery-model-404") || !strings.Contains(strings.ToLower(res.Error), "price") {
		t.Fatalf("expected refusal error mentioning model and price, got: %s", res.Error)
	}
}

func TestR6_4_LedgerWritesPerTaskEntriesAndJoinsWithPilot(t *testing.T) {
	ledgerDir := t.TempDir()
	ledgerPath := filepath.Join(ledgerDir, "production-ledger.json")

	taskID := "TASK-PILOT-REAL-001"
	budget := &TokenBudget{
		TaskID:     taskID,
		TeamID:     "team-pilot",
		LedgerPath: ledgerPath,
	}

	// Record real round usage from coordinator/loop
	budget.RecordRoundUsage(2500, 0.075)

	// Verify using pilot's VerifyLedgerEntryMatch
	err := pilot.VerifyLedgerEntryMatch(ledgerPath, taskID, 2500, 0.075)
	if err != nil {
		t.Fatalf("pilot.VerifyLedgerEntryMatch failed against real budget ledger: %v", err)
	}
}

func TestR6_4_InsecureLedgerFilePermissionsRefused(t *testing.T) {
	ledgerDir := t.TempDir()
	ledgerPath := filepath.Join(ledgerDir, "world-writable-ledger.json")

	// Create world-writable ledger file (mode 0666)
	_ = os.WriteFile(ledgerPath, []byte(`{}`), 0666)
	_ = os.Chmod(ledgerPath, 0666)

	budget := &TokenBudget{
		TaskID:     "TASK-INSECURE",
		LedgerPath: ledgerPath,
	}

	err := budget.ValidateLedgerSecurity()
	if err == nil {
		t.Fatalf("SECURITY VIOLATION: ValidateLedgerSecurity accepted world-writable ledger file (mode 0666)!")
	}
	if !strings.Contains(err.Error(), "permission") && !strings.Contains(err.Error(), "forbidden") {
		t.Fatalf("expected error mentioning insecure permissions, got: %v", err)
	}
}

func TestR10_Budget_LedgerLockFailure_AbortsWhenCapSet(t *testing.T) {
	tmpDir := t.TempDir()
	// Create an unopenable/uncreatable lock path by making the directory read-only or invalid
	unwritableDir := filepath.Join(tmpDir, "unwritable")
	_ = os.MkdirAll(unwritableDir, 0500)
	defer os.Chmod(unwritableDir, 0700)

	ledgerPath := filepath.Join(unwritableDir, "ledger.json")

	b := &TokenBudget{
		TaskID:          "TASK-R10-LOCK-FAIL",
		MaxTeamCost:     10.0, // Financial cap set
		LedgerPath:      ledgerPath,
		CostPer1kTokens: 0.002,
	}

	b.RecordRoundUsage(100, 0.0002)

	// Since ledger lock/write failed and cap is active, it must record error or trigger exhaustion/abort
	if b.LedgerError == nil {
		t.Fatalf("expected LedgerError or abort when ledger write fails under an active budget cap, got nil")
	}
	exhausted, reason := b.CheckExhaustion()
	if !exhausted {
		t.Fatalf("expected budget exhaustion/abort when ledger operation fails with cap set")
	}
	if !strings.Contains(reason, "ledger") {
		t.Fatalf("expected reason mentioning ledger failure, got: %s", reason)
	}
}

func TestR10_Budget_TwoProcessRaceWithCap(t *testing.T) {
	tmpDir := t.TempDir()
	ledgerPath := filepath.Join(tmpDir, "shared_race_ledger.json")

	// Shared cap: MaxTeamCost = $0.010 (10 rounds of $0.001 each)
	teamID := "eng-core-race"
	t.Setenv("ARTIX_TEAM_ID", teamID)

	var wg sync.WaitGroup
	workers := 4
	roundsPerWorker := 10

	var totalRecordedCost float64
	var costMu sync.Mutex

	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			b := &TokenBudget{
				TaskID:          fmt.Sprintf("TASK-WORKER-%d", workerID),
				MaxTeamCost:     0.010,
				LedgerPath:      ledgerPath,
				CostPer1kTokens: 0.001,
			}
			for r := 0; r < roundsPerWorker; r++ {
				if exh, _ := b.CheckExhaustion(); exh {
					return
				}
				b.RecordRoundUsage(1000, 0.001)
				costMu.Lock()
				totalRecordedCost += 0.001
				costMu.Unlock()
				time.Sleep(2 * time.Millisecond)
			}
		}(w)
	}
	wg.Wait()

	// Verify persistence in shared ledger
	finalBudget := &TokenBudget{
		TaskID:      "TASK-VERIFIER",
		MaxTeamCost: 0.010,
		LedgerPath:  ledgerPath,
	}
	finalBudget.SyncWithSharedLedger()

	if finalBudget.UsedTeamCost < 0.010 {
		t.Fatalf("expected team cost to reach or hit the cap of $0.010, got: $%.4f", finalBudget.UsedTeamCost)
	}
	if exh, _ := finalBudget.CheckExhaustion(); !exh {
		t.Fatalf("expected final budget state to be exhausted after racing past the cap")
	}
}

func TestR10_Budget_RestartPersistence(t *testing.T) {
	tmpDir := t.TempDir()
	ledgerPath := filepath.Join(tmpDir, "restart_ledger.json")

	teamID := "persistence-team"
	t.Setenv("ARTIX_TEAM_ID", teamID)

	// Process 1: run 3 rounds, then exit
	b1 := &TokenBudget{
		TaskID:          "TASK-P1",
		MaxTeamCost:     1.0,
		LedgerPath:      ledgerPath,
		CostPer1kTokens: 0.002,
	}
	b1.RecordRoundUsage(2000, 0.004)
	b1.RecordRoundUsage(3000, 0.006)

	// Process 2: restart / new process instantiating TokenBudget pointing to same ledger
	b2 := &TokenBudget{
		TaskID:          "TASK-P2",
		MaxTeamCost:     1.0,
		LedgerPath:      ledgerPath,
		CostPer1kTokens: 0.002,
	}
	b2.SyncWithSharedLedger()

	if b2.UsedTeamTokens != 5000 {
		t.Fatalf("expected 5000 team tokens persisted across process restart, got: %d", b2.UsedTeamTokens)
	}
	if b2.UsedTeamCost < 0.0099 || b2.UsedTeamCost > 0.0101 {
		t.Fatalf("expected $0.010 team cost persisted across restart, got: $%.4f", b2.UsedTeamCost)
	}
}



