package model

import "encoding/json"

// FolderScope defines scoped filesystem directories for debate context and agent tools.
type FolderScope struct {
	Path        string `json:"path"`
	IsReadOnly  bool   `json:"isReadOnly"`
	IsTrusted   bool   `json:"isTrusted"`
	TrustMode   string `json:"trustMode,omitempty"` // "TRUSTED", "RESTRICTED_READONLY", "ISOLATED"
	Description string `json:"description,omitempty"`
}

// WorkspaceScope configures scoped folders & terminal approval policy for agentic interaction.
type WorkspaceScope struct {
	Folders                 []FolderScope `json:"folders"`
	CommandApproval         string        `json:"commandApproval,omitempty"`
	AutoApproveSafeCommands bool          `json:"autoApproveSafeCommands,omitempty"`
}

// Project groups discussions.
type Project struct {
	ID                 string          `json:"id"`
	Name               string          `json:"name"`
	SharedContext      string          `json:"sharedContext"`
	SharedInstructions string          `json:"sharedInstructions"`
	DefaultConsensus   float64         `json:"defaultConsensus"`
	WorkspaceScope     WorkspaceScope    `json:"workspaceScope"`
	DebatePolicy       json.RawMessage   `json:"debatePolicy,omitempty"`
	Permissions        *PermissionConfig `json:"permissions,omitempty"`
}

type CompactionSettings struct {
	PreferCli           bool   `json:"preferCli"`
	CompactionThreshold int    `json:"compactionThreshold"`
	PrimaryModel        string `json:"primaryModel"`
	FallbackStrategy    string `json:"fallbackStrategy"`
	CustomCliCommand    string `json:"customCliCommand"`
}

func DefaultCompactionSettings() CompactionSettings {
	return CompactionSettings{
		CompactionThreshold: 20,
		PrimaryModel:        DefaultCompactionModel,
		FallbackStrategy:    "api",
	}
}

// DiscussionStatus tracks where a discussion is in its lifecycle.
type DiscussionStatus string

const (
	DiscussionDraft                DiscussionStatus = "DRAFT"
	DiscussionRunning              DiscussionStatus = "RUNNING"
	DiscussionPaused               DiscussionStatus = "PAUSED"
	DiscussionCompleted            DiscussionStatus = "COMPLETED"
	DiscussionCompletedWithWarning DiscussionStatus = "COMPLETED_WITH_WARNING"
	DiscussionFailed               DiscussionStatus = "FAILED"
	DiscussionDone                 DiscussionStatus = "DONE"
	DiscussionError                DiscussionStatus = "ERROR"
)

func (s DiscussionStatus) IsCompleted() bool {
	return s == DiscussionCompleted || s == DiscussionCompletedWithWarning || s == DiscussionDone
}

func (s DiscussionStatus) IsFailed() bool {
	return s == DiscussionFailed || s == DiscussionError
}

// Discussion is one debate, scoped to a project. Config is empty (Agents = []) while
// Status == DRAFT.
type Discussion struct {
	ID         string           `json:"id"`
	ProjectID  string           `json:"projectId"`
	Name       string           `json:"name"`
	Config     DebateConfig     `json:"config"`
	Status     DiscussionStatus `json:"status"`
	Transcript []DebateMessage  `json:"transcript"`
	Conclusion *string          `json:"conclusion,omitempty"`
	Summary    *string          `json:"summary,omitempty"`
	Deliverable *string         `json:"deliverable,omitempty"`
	// The "Generate AI handoff prompt" result — persisted so it survives navigating away/
	// back or an app restart, and always renders as the last bubble.
	HandoffPrompt   *string              `json:"handoffPrompt,omitempty"`
	AttachedFiles   []AttachedFile       `json:"attachedFiles,omitempty"`
	AttachedFolders []FolderScope        `json:"attachedFolders,omitempty"`
	TotalTokensUsed int64                `json:"totalTokensUsed,omitempty"`
	Artifacts       []DiscussionArtifact `json:"artifacts,omitempty"`
	DismissedArtifactIds []string        `json:"dismissedArtifactIds,omitempty"`
	TensionPairs    []TensionPair        `json:"tensionPairs,omitempty"`
	CreatedAt       int64                `json:"createdAt,omitempty"`
	UpdatedAt       int64                `json:"updatedAt,omitempty"`
	Warning            *string              `json:"warning,omitempty"`
	IsConsensusReached bool                 `json:"isConsensusReached,omitempty"`
	EarlyExitReason    *string              `json:"earlyExitReason,omitempty"`
}

// DiscussionArtifact is a saved artifact (deliverable, summary, or transcript).
type DiscussionArtifact struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Type        string `json:"type"`
	Format      string `json:"format"`
	Content     string `json:"content"`
	Timestamp   string `json:"timestamp,omitempty"`
	TimestampMs int64  `json:"timestampMs,omitempty"`
	SizeBytes   int64  `json:"sizeBytes,omitempty"`
	Round       *int   `json:"round,omitempty"`
}

// AttachedFile represents an attached reference file for context injection.
type AttachedFile struct {
	ID              string `json:"id"`
	Name            string `json:"name"`
	SizeLabel       string `json:"sizeLabel,omitempty"`
	Content         string `json:"content,omitempty"`
	Scope           string `json:"scope,omitempty"`
	EstimatedTokens int    `json:"estimatedTokens,omitempty"`
	MimeType        string `json:"mimeType,omitempty"`
}

// NewDiscussion mirrors the Kotlin Discussion() default constructor (DRAFT status, empty
// transcript).
func NewDiscussion(id, projectID, name string, config DebateConfig) Discussion {
	return Discussion{
		ID:         id,
		ProjectID:  projectID,
		Name:       name,
		Config:     config,
		Status:     DiscussionDraft,
		Transcript: []DebateMessage{},
	}
}

const (
	// DefaultCompactionModel is used only for transcript-compaction summaries — a
	// mechanical task, so a cheap/fast model regardless of what's picked for the debate
	// itself. Configurable in Settings; this is just the fallback.
	DefaultCompactionModel = "claude-haiku-4-5-20251001"
	// DefaultTokenBudget is a hard ceiling on total tokens (in+out) a single discussion run
	// spends before it stops itself and asks to be raised. 0 disables it. Only API-mode
	// turns report usage, so this can't see CLI-mode spend.
	DefaultTokenBudget = 1_000_000
	// DefaultAgentLimit is the default max additional (non-primary) seats a discussion can
	// have — a setting, not a hardcoded ceiling; Settings can raise or lower it.
	DefaultAgentLimit = 4
)

// AppState is everything persisted to disk.
type AppState struct {
	Projects        []Project    `json:"projects"`
	Discussions     []Discussion `json:"discussions"`
	ApiKeys         ApiKeys      `json:"apiKeys"`
	CliCommands     CliCommands  `json:"cliCommands"`
	CompactionModel string       `json:"compactionModel"`
	TokenBudget     int          `json:"tokenBudget"`
	// AgentLimit caps how many additional (non-primary) seats a discussion may have.
	// New in the Go engine — the Kotlin app had this as a compiled-in UI constant; making
	// it a setting here is a deliberate improvement (see ROADMAP_AND_OPTIMIZATIONS.md,
	// round-2 decision on agent limits).
	AgentLimit int `json:"agentLimit"`
	// Users is the multi-user account store (see pkg/model/user.go). New in the Go engine —
	// the Kotlin app had no server, so no concept of accounts existed.
	Users []User `json:"users"`
	CompactionSettings CompactionSettings `json:"compactionSettings"`
	MasterInstructions string             `json:"masterInstructions"`
	Personas           []Persona             `json:"personas"`
	DebatePolicy       json.RawMessage       `json:"debatePolicy,omitempty"`
	AgentDefaults      ProviderAgentDefaults `json:"agentDefaults"`
}

// NewAppState mirrors the Kotlin AppState() default constructor's defaults.
func NewAppState() AppState {
	return AppState{
		Projects:        []Project{},
		Discussions:     []Discussion{},
		CliCommands:     DefaultCliCommands(),
		CompactionModel: DefaultCompactionModel,
		TokenBudget:     DefaultTokenBudget,
		AgentLimit:      DefaultAgentLimit,
		Users:           []User{},
		CompactionSettings: DefaultCompactionSettings(),
		MasterInstructions: "",
		Personas:           []Persona{},
		AgentDefaults:      DefaultProviderAgentDefaults(),
	}
}
