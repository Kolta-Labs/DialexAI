package quarantine

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"kritix/pkg/tracker"
	"kritix/pkg/triage"
)

const (
	MaxQuarantineAutoDeleteTTL = 7 * 24 * time.Hour // 7 days maximum in quarantine before auto-deletion
	DefaultTeamQuarantineCap   = 5                  // Maximum 5 flaky tests allowed per squad simultaneously
)

var (
	ErrQuarantineCapExceeded = errors.New("quarantine cap exceeded for team; resolve or fix existing flaky tests before quarantining more")
	ErrSpecBudgetExceeded    = errors.New("generated spec budget exceeded for squad; SDET review required before generating more specs")
)

// Classification classifies a test's stability.
type Classification string

const (
	ClassificationStablePass     Classification = "stable_pass"
	ClassificationTrueRegression Classification = "true_regression"
	ClassificationFlaky          Classification = "flaky"
)

// ExecutionAttempt records the result of a single retry attempt.
type ExecutionAttempt struct {
	AttemptNumber int           `json:"attempt_number"`
	Passed        bool          `json:"passed"`
	Duration      time.Duration `json:"duration"`
	Error         string        `json:"error,omitempty"`
}

// FlakeAnalysis holds the statistical assessment of a test case.
type FlakeAnalysis struct {
	TestID           string             `json:"test_id"`
	Classification   Classification     `json:"classification"`
	TotalAttempts    int                `json:"total_attempts"`
	PassedAttempts   int                `json:"passed_attempts"`
	FlakeRate        float64            `json:"flake_rate"` // 0.0 to 1.0
	Attempts         []ExecutionAttempt `json:"attempts"`
	ShouldQuarantine bool               `json:"should_quarantine"`
}

// QuarantinedItem models an isolated test with strict SLA, squad, and individual engineer ownership.
type QuarantinedItem struct {
	TestID           string        `json:"test_id"`
	Owner            string        `json:"owner"` // Individual engineer / SDET owner (e.g. sdet@company.com)
	Analysis         FlakeAnalysis `json:"analysis"`
	QuarantinedAt    time.Time     `json:"quarantined_at"`
	SLA              time.Duration `json:"sla"` // e.g. 7 days
	AssignedSquad    string        `json:"assigned_squad"`
	TechDebtTicketID string        `json:"tech_debt_ticket_id"`
}

// IsSLAExceeded returns true if the quarantine period has expired without resolution.
func (q *QuarantinedItem) IsSLAExceeded() bool {
	if q.SLA <= 0 {
		return false
	}
	return time.Since(q.QuarantinedAt) > q.SLA
}

// FlakeEvaluator orchestrates statistical retries with exponential backoff.
type FlakeEvaluator struct {
	maxAttempts int
	baseBackoff time.Duration
}

// NewFlakeEvaluator creates a new evaluator.
func NewFlakeEvaluator(maxAttempts int, baseBackoff time.Duration) *FlakeEvaluator {
	if maxAttempts <= 0 {
		maxAttempts = 3
	}
	if baseBackoff <= 0 {
		baseBackoff = 20 * time.Millisecond
	}
	return &FlakeEvaluator{
		maxAttempts: maxAttempts,
		baseBackoff: baseBackoff,
	}
}

// EvaluateTest runs the test runner function across retries to diagnose stability.
func (e *FlakeEvaluator) EvaluateTest(ctx context.Context, testID string, runner func(attempt int) error) FlakeAnalysis {
	var attempts []ExecutionAttempt
	passedCount := 0

	for i := 1; i <= e.maxAttempts; i++ {
		start := time.Now()
		err := runner(i)
		dur := time.Since(start)

		if err == nil {
			passedCount++
			attempts = append(attempts, ExecutionAttempt{
				AttemptNumber: i,
				Passed:        true,
				Duration:      dur,
			})
		} else {
			attempts = append(attempts, ExecutionAttempt{
				AttemptNumber: i,
				Passed:        false,
				Duration:      dur,
				Error:         err.Error(),
			})
		}

		if i < e.maxAttempts {
			time.Sleep(e.baseBackoff * time.Duration(1<<uint(i-1)))
		}
	}

	var class Classification
	var shouldQuarantine bool
	var flakeRate float64

	if passedCount == e.maxAttempts {
		class = ClassificationStablePass
	} else if passedCount == 0 {
		class = ClassificationTrueRegression // 100% fail = genuine bug!
	} else {
		class = ClassificationFlaky // Intermittent = flaky test!
		shouldQuarantine = true
		flakeRate = float64(e.maxAttempts-passedCount) / float64(e.maxAttempts)
	}

	return FlakeAnalysis{
		TestID:           testID,
		Classification:   class,
		TotalAttempts:    e.maxAttempts,
		PassedAttempts:   passedCount,
		FlakeRate:        flakeRate,
		Attempts:         attempts,
		ShouldQuarantine: shouldQuarantine,
	}
}

// QuarantineRegistry keeps track of quarantined flaky tests and enforces enterprise SLAs,
// individual test ownership, and per-team quarantine limits.
type QuarantineRegistry struct {
	mu          sync.RWMutex
	quarantined map[string]QuarantinedItem
	defaultSLA  time.Duration
	maxPerTeam  int
}

// NewQuarantineRegistry constructs a quarantine registry with default 7-day SLA and per-team cap of 5.
func NewQuarantineRegistry() *QuarantineRegistry {
	return &QuarantineRegistry{
		quarantined: make(map[string]QuarantinedItem),
		defaultSLA:  7 * 24 * time.Hour,
		maxPerTeam:  DefaultTeamQuarantineCap,
	}
}

// SetDefaultSLA configures the maximum allowed quarantine duration.
func (r *QuarantineRegistry) SetDefaultSLA(sla time.Duration) {
	r.defaultSLA = sla
}

// SetMaxPerTeam configures the maximum number of tests allowed in quarantine per squad.
func (r *QuarantineRegistry) SetMaxPerTeam(maxTests int) {
	if maxTests > 0 {
		r.maxPerTeam = maxTests
	}
}

// AddQuarantine registers a flaky test into quarantine using default SLA and unassigned squad/owner.
func (r *QuarantineRegistry) AddQuarantine(analysis FlakeAnalysis) error {
	return r.AddQuarantineWithOwner(analysis, "unassigned", "unassigned@company.com", "", r.defaultSLA)
}

// AddQuarantineWithSLA registers a flaky test with explicit squad ownership and ticket ID.
func (r *QuarantineRegistry) AddQuarantineWithSLA(analysis FlakeAnalysis, squad string, ticketID string, sla time.Duration) error {
	return r.AddQuarantineWithOwner(analysis, squad, squad+"-lead@company.com", ticketID, sla)
}

// AddQuarantineWithOwner registers a flaky test with explicit owner, squad, ticket, and SLA while enforcing per-squad limits.
func (r *QuarantineRegistry) AddQuarantineWithOwner(analysis FlakeAnalysis, squad, owner, ticketID string, sla time.Duration) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if squad == "" {
		squad = "unassigned"
	}
	if owner == "" {
		owner = squad + "-lead@company.com"
	}
	if sla <= 0 {
		sla = r.defaultSLA
	}

	// Enforce per-team quarantine cap
	teamCount := 0
	for _, item := range r.quarantined {
		if item.AssignedSquad == squad && item.TestID != analysis.TestID {
			teamCount++
		}
	}
	if teamCount >= r.maxPerTeam {
		return fmt.Errorf("%w (%d active in squad %q, max allowed: %d)",
			ErrQuarantineCapExceeded, teamCount, squad, r.maxPerTeam)
	}

	r.quarantined[analysis.TestID] = QuarantinedItem{
		TestID:           analysis.TestID,
		Owner:            owner,
		Analysis:         analysis,
		QuarantinedAt:    time.Now(),
		SLA:              sla,
		AssignedSquad:    squad,
		TechDebtTicketID: ticketID,
	}
	return nil
}

// AutoTicketExpired scans for expired quarantine items and automatically creates defect tickets via the IssueTracker.
func (r *QuarantineRegistry) AutoTicketExpired(ctx context.Context, trackerClient tracker.IssueTracker) ([]string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if trackerClient == nil {
		return nil, errors.New("tracker client cannot be nil")
	}

	var createdTickets []string
	for id, item := range r.quarantined {
		if item.IsSLAExceeded() && item.TechDebtTicketID == "" {
			report := triage.DefectReport{
				ID:       fmt.Sprintf("QUARANTINE-EXPIRED-%s", item.TestID),
				Title:    fmt.Sprintf("[QUARANTINE EXPIRED] Flaky test %s SLA exceeded (Owner: %s, Squad: %s)", item.TestID, item.Owner, item.AssignedSquad),
				Severity: triage.SeverityMajor,
				Category: triage.CategoryInfraFlake,
				ActualBehavior: fmt.Sprintf("Test %s has remained in quarantine for %v past its SLA of %v without remediation by owner %s (Squad: %s). Merge gate blocked.",
					item.TestID, time.Since(item.QuarantinedAt).Round(time.Hour), item.SLA, item.Owner, item.AssignedSquad),
				StepsToReproduce: []string{
					fmt.Sprintf("1. Investigate test %s in %s repository", item.TestID, item.AssignedSquad),
					"2. Run statistical flake reproducer: kritix test --reproduce " + item.TestID,
					"3. Fix timing race condition or delete obsolete test",
				},
				DiscoveredAt: time.Now(),
			}

			res, err := trackerClient.CreateIssue(ctx, report)
			if err == nil && res != nil {
				item.TechDebtTicketID = res.IssueID
				r.quarantined[id] = item
				createdTickets = append(createdTickets, res.IssueID)
			}
		}
	}

	return createdTickets, nil
}

// IsQuarantined checks if a test is currently quarantined.
func (r *QuarantineRegistry) IsQuarantined(testID string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	_, ok := r.quarantined[testID]
	return ok
}

// ShouldFailBuild checks if an executing test is in quarantine and whether its SLA has expired.
func (r *QuarantineRegistry) ShouldFailBuild(testID string) (bool, string) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	item, ok := r.quarantined[testID]
	if !ok {
		return false, ""
	}
	if item.IsSLAExceeded() {
		return true, fmt.Sprintf("QUARANTINE SLA EXCEEDED: Test %q has been in quarantine >%v without a fix (Owner: %s, Squad: %s, Ticket: %s). Blocking CI build.",
			testID, item.SLA, item.Owner, item.AssignedSquad, item.TechDebtTicketID)
	}
	return false, fmt.Sprintf("Test %q is in quarantine (Owner: %s, Squad: %s, Ticket: %s). Non-blocking.", testID, item.Owner, item.AssignedSquad, item.TechDebtTicketID)
}

// ListQuarantined returns all currently quarantined tests.
func (r *QuarantineRegistry) ListQuarantined() []QuarantinedItem {
	r.mu.RLock()
	defer r.mu.RUnlock()
	var list []QuarantinedItem
	for _, a := range r.quarantined {
		list = append(list, a)
	}
	return list
}

// GetExceededSLAs returns all tests whose quarantine period has expired.
func (r *QuarantineRegistry) GetExceededSLAs() []QuarantinedItem {
	r.mu.RLock()
	defer r.mu.RUnlock()
	var exceeded []QuarantinedItem
	for _, item := range r.quarantined {
		if item.IsSLAExceeded() {
			exceeded = append(exceeded, item)
		}
	}
	return exceeded
}

// TeamLeadEscalations returns all tests requiring immediate Engineering Lead intervention.
func (r *QuarantineRegistry) TeamLeadEscalations() []QuarantinedItem {
	return r.GetExceededSLAs()
}

// FormatEscalationNotice renders an actionable Slack/PagerDuty escalation message for team leads.
func (q *QuarantinedItem) FormatEscalationNotice() string {
	return fmt.Sprintf("🚨 [QUARANTINE ESCALATION - ACTION REQUIRED]\n"+
		"Test ID:    %s\n"+
		"Owner:      %s\n"+
		"Squad:      %s\n"+
		"Ticket:     %s\n"+
		"SLA Limit:  %v (EXCEEDED)\n"+
		"Policy:     Test has rotted in quarantine past its SLA without remediation. CI merge gates are now BLOCKED until fixed or signed off by Team Lead.\n",
		q.TestID, q.Owner, q.AssignedSquad, q.TechDebtTicketID, q.SLA)
}

// PurgeExpiredTests automatically deletes tests that have been quarantined longer than maxAge (default 7 days).
func (r *QuarantineRegistry) PurgeExpiredTests(maxAge time.Duration) []QuarantinedItem {
	r.mu.Lock()
	defer r.mu.Unlock()

	if maxAge <= 0 {
		maxAge = MaxQuarantineAutoDeleteTTL
	}

	var purged []QuarantinedItem
	for id, item := range r.quarantined {
		if time.Since(item.QuarantinedAt) > maxAge {
			purged = append(purged, item)
			delete(r.quarantined, id)
		}
	}
	return purged
}

// SpecBudgetManager enforces generation budgets on AI test synthesis to prevent AI code debt.
type SpecBudgetManager struct {
	mu           sync.RWMutex
	pendingSpecs map[string]map[string]time.Time // [squad][specID] -> createdAt
	maxBudget    int
}

// NewSpecBudgetManager initializes a budget manager with a max unreviewed spec cap per squad.
func NewSpecBudgetManager(maxBudgetPerSquad int) *SpecBudgetManager {
	if maxBudgetPerSquad <= 0 {
		maxBudgetPerSquad = 10
	}
	return &SpecBudgetManager{
		pendingSpecs: make(map[string]map[string]time.Time),
		maxBudget:    maxBudgetPerSquad,
	}
}

// RegisterGeneratedSpec registers an AI synthesized test spec against the squad's budget.
func (m *SpecBudgetManager) RegisterGeneratedSpec(squad, specID string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if squad == "" {
		squad = "default-squad"
	}
	if m.pendingSpecs[squad] == nil {
		m.pendingSpecs[squad] = make(map[string]time.Time)
	}

	if len(m.pendingSpecs[squad]) >= m.maxBudget {
		return fmt.Errorf("%w: squad %q already has %d unreviewed AI specs (budget limit: %d)",
			ErrSpecBudgetExceeded, squad, len(m.pendingSpecs[squad]), m.maxBudget)
	}

	m.pendingSpecs[squad][specID] = time.Now()
	return nil
}

// SignOffSpec removes a spec from the pending budget once human SDET signoff is completed.
func (m *SpecBudgetManager) SignOffSpec(squad, specID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.pendingSpecs[squad] != nil {
		delete(m.pendingSpecs[squad], specID)
	}
}

// PurgeStaleSpecs removes unreviewed AI specs that have rotted beyond maxAge (default 72h).
func (m *SpecBudgetManager) PurgeStaleSpecs(maxAge time.Duration) []string {
	m.mu.Lock()
	defer m.mu.Unlock()

	if maxAge <= 0 {
		maxAge = 72 * time.Hour
	}

	var purged []string
	now := time.Now()
	for squad, specs := range m.pendingSpecs {
		for id, createdAt := range specs {
			if now.Sub(createdAt) > maxAge {
				purged = append(purged, fmt.Sprintf("%s:%s", squad, id))
				delete(specs, id)
			}
		}
	}
	return purged
}

// LeadershipFlakeScorecard models the weekly flakiness and tech-debt report surfaced to Engineering leadership.
type LeadershipFlakeScorecard struct {
	GeneratedAt       time.Time         `json:"generated_at"`
	TotalQuarantined  int               `json:"total_quarantined"`
	ExceededSLACount  int               `json:"exceeded_sla_count"`
	PendingPurgeCount int               `json:"pending_purge_count"`
	SquadDebt         map[string]int    `json:"squad_debt"`
	HighFlakeTests    []QuarantinedItem `json:"high_flake_tests"`
}

// GenerateLeadershipScorecard compiles a high-visibility flakiness scorecard for VP Eng / QA Director review.
func (r *QuarantineRegistry) GenerateLeadershipScorecard() LeadershipFlakeScorecard {
	r.mu.RLock()
	defer r.mu.RUnlock()

	card := LeadershipFlakeScorecard{
		GeneratedAt:       time.Now(),
		TotalQuarantined:  len(r.quarantined),
		SquadDebt:         make(map[string]int),
		HighFlakeTests:    make([]QuarantinedItem, 0),
	}

	for _, item := range r.quarantined {
		card.SquadDebt[item.AssignedSquad]++
		if item.IsSLAExceeded() {
			card.ExceededSLACount++
		}
		if time.Since(item.QuarantinedAt) > 20*24*time.Hour {
			card.PendingPurgeCount++
		}
		if item.Analysis.FlakeRate >= 0.50 {
			card.HighFlakeTests = append(card.HighFlakeTests, item)
		}
	}

	return card
}

// FormatMarkdownScorecard formats the scorecard for weekly leadership distribution.
func (s *LeadershipFlakeScorecard) FormatMarkdownScorecard() string {
	var sb strings.Builder
	sb.WriteString("## 📊 Weekly Engineering Flakiness & Test-Debt Scorecard\n\n")
	sb.WriteString(fmt.Sprintf("**Report Date**: %s | **Active Quarantined Tests**: `%d`\n",
		s.GeneratedAt.Format("2006-01-02"), s.TotalQuarantined))
	sb.WriteString(fmt.Sprintf("**SLA Exceeded (>14d)**: `%d` | **Pending Auto-Deletion (>20d)**: `%d`\n\n",
		s.ExceededSLACount, s.PendingPurgeCount))

	sb.WriteString("### 👥 Quarantine Debt By Squad\n")
	for squad, count := range s.SquadDebt {
		sb.WriteString(fmt.Sprintf("- **%s**: %d tests in quarantine\n", squad, count))
	}
	sb.WriteString("\n")

	if len(s.HighFlakeTests) > 0 {
		sb.WriteString("### 🔥 Severe Flakiness Tests (Flake Rate >= 50%)\n")
		for _, t := range s.HighFlakeTests {
			sb.WriteString(fmt.Sprintf("- `%s` (Owner: %s, Squad: %s, Flake: %.0f%%, Age: %dd)\n",
				t.TestID, t.Owner, t.AssignedSquad, t.Analysis.FlakeRate*100, int(time.Since(t.QuarantinedAt).Hours()/24)))
		}
	}

	return sb.String()
}

// CadenceRunResult records the outcome of a background slow-cadence test run.
type CadenceRunResult struct {
	TestID     string    `json:"test_id"`
	Passed     bool      `json:"passed"`
	ExecutedAt time.Time `json:"executed_at"`
	Error      string    `json:"error,omitempty"`
}

// RunSlowWeeklyCadence executes quarantined tests on a slow background cadence to monitor real failures.
func (r *QuarantineRegistry) RunSlowWeeklyCadence(ctx context.Context, runner func(testID string) error) []CadenceRunResult {
	r.mu.RLock()
	tests := make([]string, 0, len(r.quarantined))
	for id := range r.quarantined {
		tests = append(tests, id)
	}
	r.mu.RUnlock()

	var results []CadenceRunResult
	for _, id := range tests {
		err := runner(id)
		res := CadenceRunResult{
			TestID:     id,
			Passed:     err == nil,
			ExecutedAt: time.Now(),
		}
		if err != nil {
			res.Error = err.Error()
		}
		results = append(results, res)
	}
	return results
}
