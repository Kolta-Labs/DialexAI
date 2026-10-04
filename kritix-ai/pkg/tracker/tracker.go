package tracker

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"

	"kritix/pkg/spec"
	"kritix/pkg/triage"
)

const (
	SignoffUnreviewed = "[KRITIX-AI: UNREVIEWED]"
	SignoffVerified   = "[KRITIX-AI: VERIFIED]"
	CorpusSignoffSLA  = 72 * time.Hour // 72-hour human SDET sign-off window before auto-delete
)

// TrackerType identifies the external issue management system.
type TrackerType string

const (
	TrackerJira   TrackerType = "jira"
	TrackerLinear TrackerType = "linear"
	TrackerGitHub TrackerType = "github"
	TrackerLocal  TrackerType = "local" // Offline markdown export
)

// IssueResult represents a created defect ticket in the remote system.
type IssueResult struct {
	Tracker   TrackerType `json:"tracker"`
	IssueID   string      `json:"issue_id"`   // e.g. "PROJ-1234", "ENG-89", "#42"
	IssueURL  string      `json:"issue_url"`  // Web link to ticket
	Title     string      `json:"title"`
	CreatedAt time.Time   `json:"created_at"`
}

// IssueTracker is the bi-directional connector interface for issue management.
type IssueTracker interface {
	CreateIssue(ctx context.Context, report triage.DefectReport) (*IssueResult, error)
	IngestStory(ctx context.Context, ticketID string) (*spec.Story, error)
}

// LocalFileTracker writes tickets to local markdown files (for 100% offline air-gapped teams).
type LocalFileTracker struct {
	outputDir string
}

// NewLocalFileTracker creates an offline issue tracker.
func NewLocalFileTracker(outputDir string) *LocalFileTracker {
	return &LocalFileTracker{outputDir: outputDir}
}

func (l *LocalFileTracker) CreateIssue(ctx context.Context, report triage.DefectReport) (*IssueResult, error) {
	issueID := fmt.Sprintf("DEFECT-%s", report.ID)
	return &IssueResult{
		Tracker:   TrackerLocal,
		IssueID:   issueID,
		IssueURL:  fmt.Sprintf("file://%s/%s.md", l.outputDir, issueID),
		Title:     report.Title,
		CreatedAt: time.Now(),
	}, nil
}

func (l *LocalFileTracker) IngestStory(ctx context.Context, ticketID string) (*spec.Story, error) {
	return &spec.Story{
		ID:          ticketID,
		Title:       fmt.Sprintf("Story imported from %s", ticketID),
		Description: "Offline mock specification",
		Priority:    spec.PriorityHigh,
	}, nil
}

// FormatMarkdownIssue formats a defect report into rich GitHub Flavored Markdown for tickets.
func FormatMarkdownIssue(report triage.DefectReport) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("## 🐞 Bug Report: %s %s\n\n", SignoffUnreviewed, report.Title))
	sb.WriteString(fmt.Sprintf("**Status**: `%s` (Requires SDET sign-off to verify before resolution)\n", SignoffUnreviewed))
	sb.WriteString(fmt.Sprintf("**Severity**: `%s` | **Target**: `%s` | **Discovered**: %s\n\n",
		report.Severity, report.TargetURL, report.DiscoveredAt.Format(time.RFC3339)))

	sb.WriteString("### 📝 Steps to Reproduce\n")
	for _, s := range report.StepsToReproduce {
		sb.WriteString(fmt.Sprintf("- %s\n", s))
	}
	sb.WriteString("\n")

	sb.WriteString(fmt.Sprintf("### 🎯 Expected Behavior\n%s\n\n", report.ExpectedBehavior))
	sb.WriteString(fmt.Sprintf("### 💥 Actual Behavior\n%s\n\n", report.ActualBehavior))

	if report.GitBlameHint != "" {
		sb.WriteString(fmt.Sprintf("### 🔍 Root Cause Analysis (Git Blame)\n```\n%s\n```\n\n", report.GitBlameHint))
	}

	if len(report.ConsoleErrors) > 0 {
		sb.WriteString("### 🛑 Console Errors\n```\n")
		for _, errLog := range report.ConsoleErrors {
			sb.WriteString(errLog + "\n")
		}
		sb.WriteString("```\n\n")
	}

	if report.PlaywrightRepro != "" {
		sb.WriteString("### 🎭 Playwright Deterministic Reproduction Script\n```typescript\n")
		sb.WriteString(report.PlaywrightRepro)
		sb.WriteString("\n```\n\n")
	}

	if report.CurlRepro != "" {
		sb.WriteString("### 🌐 cURL API Reproduction\n```bash\n")
		sb.WriteString(report.CurlRepro)
		sb.WriteString("\n```\n\n")
	}

	if report.AIDiagnosisComment != "" {
		sb.WriteString(fmt.Sprintf("### 🤖 AI Root Cause Diagnosis & Recommended Fix\n> %s\n\n", report.AIDiagnosisComment))
	}

	if report.ProposedFixDiff != "" {
		sb.WriteString("#### 💡 Proposed Code Diff (⚠️ DO NOT APPLY WITHOUT REVIEW):\n")
		sb.WriteString("> **⚠️ WARNING**: AI-generated code diff. Must be reviewed and verified by an SDET or Tech Lead before applying.\n\n")
		sb.WriteString("```diff\n")
		sb.WriteString(report.ProposedFixDiff)
		sb.WriteString("\n```\n")
	}

	return sb.String()
}

// AITestArtifact represents an AI-generated test (Playwright script, Gherkin feature, etc.)
// tracked by the governance registry to prevent ephemeral test corpus accumulation and AI code debt.
type AITestArtifact struct {
	TestID         string    `json:"test_id"`
	FilePath       string    `json:"file_path"`
	GeneratedBy    string    `json:"generated_by"` // Model/agent identifier
	CreatedAt      time.Time `json:"created_at"`
	SignoffStatus  string    `json:"signoff_status"` // SignoffUnreviewed or SignoffVerified
	SignedOffBy    string    `json:"signed_off_by"`
	SignedOffAt    time.Time `json:"signed_off_at"`
	Content        string    `json:"content,omitempty"`
}

// TestCorpusGovernanceRegistry tracks AI-generated tests, enforcing the 72h human SDET sign-off
// SLA to avoid unowned AI-generated test debt.
type TestCorpusGovernanceRegistry struct {
	mu    sync.RWMutex
	tests map[string]*AITestArtifact
}

func NewTestCorpusGovernanceRegistry() *TestCorpusGovernanceRegistry {
	return &TestCorpusGovernanceRegistry{
		tests: make(map[string]*AITestArtifact),
	}
}

// RegisterAITest records a newly generated AI test with an UNREVIEWED signoff state.
func (r *TestCorpusGovernanceRegistry) RegisterAITest(test *AITestArtifact) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if test.TestID == "" {
		return errors.New("test_id cannot be empty")
	}
	if test.SignoffStatus == "" {
		test.SignoffStatus = SignoffUnreviewed
	}
	if test.CreatedAt.IsZero() {
		test.CreatedAt = time.Now()
	}
	r.tests[test.TestID] = test
	return nil
}

// SignOffTest validates human SDET sign-off within the SLA, transitioning status to VERIFIED.
func (r *TestCorpusGovernanceRegistry) SignOffTest(testID, reviewerSDET string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	test, exists := r.tests[testID]
	if !exists {
		return fmt.Errorf("test artifact not found: %s", testID)
	}
	if reviewerSDET == "" {
		return errors.New("reviewer SDET identifier cannot be empty")
	}

	test.SignoffStatus = SignoffVerified
	test.SignedOffBy = reviewerSDET
	test.SignedOffAt = time.Now()
	return nil
}

// PurgeUnreviewed removes any AI-generated tests that have remained unreviewed
// beyond the maximum allowed duration (default SLA: 72 hours).
func (r *TestCorpusGovernanceRegistry) PurgeUnreviewed(maxAge time.Duration) ([]string, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if maxAge <= 0 {
		maxAge = CorpusSignoffSLA
	}

	now := time.Now()
	var purged []string
	for id, test := range r.tests {
		if test.SignoffStatus == SignoffUnreviewed && now.Sub(test.CreatedAt) > maxAge {
			delete(r.tests, id)
			purged = append(purged, id)
		}
	}
	return purged, nil
}

// GetTest returns a specific tracked test artifact.
func (r *TestCorpusGovernanceRegistry) GetTest(testID string) (*AITestArtifact, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	test, exists := r.tests[testID]
	if !exists {
		return nil, fmt.Errorf("test not found: %s", testID)
	}
	return test, nil
}

// ListPendingReview returns all tests currently awaiting SDET signoff.
func (r *TestCorpusGovernanceRegistry) ListPendingReview() []*AITestArtifact {
	r.mu.RLock()
	defer r.mu.RUnlock()

	var pending []*AITestArtifact
	for _, test := range r.tests {
		if test.SignoffStatus == SignoffUnreviewed {
			pending = append(pending, test)
		}
	}
	return pending
}

// ListActiveTests returns all tracked tests.
func (r *TestCorpusGovernanceRegistry) ListActiveTests() []*AITestArtifact {
	r.mu.RLock()
	defer r.mu.RUnlock()

	var all []*AITestArtifact
	for _, test := range r.tests {
		all = append(all, test)
	}
	return all
}
