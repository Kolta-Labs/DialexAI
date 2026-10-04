package quarantine

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"
)

const (
	MaxQuarantineAutoDeleteTTL = 7 * 24 * time.Hour // 7 days maximum in quarantine before auto-deletion
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

// QuarantinedItem models an isolated test with strict SLA and tech-debt ownership.
type QuarantinedItem struct {
	TestID           string        `json:"test_id"`
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

// QuarantineRegistry keeps track of quarantined flaky tests and enforces enterprise SLAs.
type QuarantineRegistry struct {
	mu          sync.RWMutex
	quarantined map[string]QuarantinedItem
	defaultSLA  time.Duration
}

// NewQuarantineRegistry constructs a quarantine registry with default 7-day SLA.
func NewQuarantineRegistry() *QuarantineRegistry {
	return &QuarantineRegistry{
		quarantined: make(map[string]QuarantinedItem),
		defaultSLA:  7 * 24 * time.Hour, // 7-day strict SLA requested by Head of Tech
	}
}

// SetDefaultSLA configures the maximum allowed quarantine duration.
func (r *QuarantineRegistry) SetDefaultSLA(sla time.Duration) {
	r.defaultSLA = sla
}

// AddQuarantine registers a flaky test into quarantine using default SLA.
func (r *QuarantineRegistry) AddQuarantine(analysis FlakeAnalysis) {
	r.AddQuarantineWithSLA(analysis, "unassigned", "", r.defaultSLA)
}

// AddQuarantineWithSLA registers a flaky test with explicit squad ownership and ticket ID.
func (r *QuarantineRegistry) AddQuarantineWithSLA(analysis FlakeAnalysis, squad string, ticketID string, sla time.Duration) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.quarantined[analysis.TestID] = QuarantinedItem{
		TestID:           analysis.TestID,
		Analysis:         analysis,
		QuarantinedAt:    time.Now(),
		SLA:              sla,
		AssignedSquad:    squad,
		TechDebtTicketID: ticketID,
	}
}

// IsQuarantined checks if a test is currently quarantined.
func (r *QuarantineRegistry) IsQuarantined(testID string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	_, ok := r.quarantined[testID]
	return ok
}

// ShouldFailBuild checks if an executing test is in quarantine and whether its SLA has expired.
// If SLA is exceeded, returns true so CI fails until the team resolves their tech debt!
func (r *QuarantineRegistry) ShouldFailBuild(testID string) (bool, string) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	item, ok := r.quarantined[testID]
	if !ok {
		return false, ""
	}
	if item.IsSLAExceeded() {
		return true, fmt.Sprintf("QUARANTINE SLA EXCEEDED: Test %q has been in quarantine >%v without a fix (Assigned to: %s, Ticket: %s). Blocking CI build.",
			testID, item.SLA, item.AssignedSquad, item.TechDebtTicketID)
	}
	return false, fmt.Sprintf("Test %q is in quarantine (Assigned to: %s, Ticket: %s). Non-blocking.", testID, item.AssignedSquad, item.TechDebtTicketID)
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
		"Squad:      %s\n"+
		"Ticket:     %s\n"+
		"SLA Limit:  %v (EXCEEDED)\n"+
		"Policy:     Test has rotted in quarantine past its SLA without remediation. CI merge gates are now BLOCKED until fixed or signed off by Team Lead.\n",
		q.TestID, q.AssignedSquad, q.TechDebtTicketID, q.SLA)
}

// PurgeExpiredTests automatically deletes tests that have been quarantined longer than maxAge (default 7 days).
// Eliminates test rot and creates forced team accountability as requested by VP Engineering.
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

// LeadershipFlakeScorecard models the weekly flakiness and tech-debt report surfaced to Engineering leadership.
type LeadershipFlakeScorecard struct {
	GeneratedAt       time.Time         `json:"generated_at"`
	TotalQuarantined  int               `json:"total_quarantined"`
	ExceededSLACount  int               `json:"exceeded_sla_count"`
	PendingPurgeCount int               `json:"pending_purge_count"` // Approaching 30-day auto deletion
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
			sb.WriteString(fmt.Sprintf("- `%s` (Squad: %s, Flake: %.0f%%, Age: %dd)\n",
				t.TestID, t.AssignedSquad, t.Analysis.FlakeRate*100, int(time.Since(t.QuarantinedAt).Hours()/24)))
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
