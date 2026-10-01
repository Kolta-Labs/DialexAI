// Package model is the Go port of the Kotlin shared/model package. JSON field names and
// enum string values match the Kotlin kotlinx.serialization output exactly (including the
// legacy @SerialName aliases on DebateConfig) so an existing state.json — written by the
// JVM app — decodes here without a migration step.
package model

import (
	"fmt"
	"math/rand"
)

// Provider is which provider backs an agent's model. Up to 5 seats can be filled at once
// (see DebateConfig), one per provider — GROK/DEEPSEEK/MISTRAL round out the catalog beyond
// the original three so 5 distinct real agents is reachable. CUSTOM is any other CLI tool
// none of these cover — CLI-only (no known API shape to call), named by Agent.DisplayName
// since there's no fixed brand for it.
type Provider string

const (
	ProviderAnthropic Provider = "ANTHROPIC"
	ProviderOpenAI    Provider = "OPENAI"
	ProviderGemini    Provider = "GEMINI"
	ProviderGrok      Provider = "GROK"
	ProviderDeepSeek  Provider = "DEEPSEEK"
	ProviderMistral   Provider = "MISTRAL"
	ProviderOllama    Provider = "OLLAMA"
	ProviderCustom    Provider = "CUSTOM"
)

// AllProviders is every known provider, in a stable order — used for iteration (status
// checks, settings forms) wherever "all providers" is needed.
var AllProviders = []Provider{
	ProviderAnthropic, ProviderOpenAI, ProviderGemini,
	ProviderGrok, ProviderDeepSeek, ProviderMistral, ProviderOllama, ProviderCustom,
}

// BrandName is the static brand name for the known providers; CUSTOM has no fixed brand —
// callers needing a label for a specific seat should use Agent.Label instead, which falls
// back to this only when Agent.DisplayName is blank.
func (p Provider) BrandName() string {
	switch p {
	case ProviderAnthropic:
		return "Claude"
	case ProviderOpenAI:
		return "ChatGPT"
	case ProviderGemini:
		return "Gemini"
	case ProviderGrok:
		return "Grok"
	case ProviderDeepSeek:
		return "DeepSeek"
	case ProviderMistral:
		return "Mistral"
	case ProviderOllama:
		return "Ollama (Local)"
	case ProviderCustom:
		return "Custom"
	default:
		return string(p)
	}
}

// DefaultModel is the default model per provider — deliberately the fast/balanced tier
// (Sonnet for Claude, Flash for Gemini, and the closest equivalent for the rest) rather than
// each provider's biggest flagship, since a debate can run many turns per agent. Blank for
// CUSTOM — freeform, no curated model list to default from.
func (p Provider) DefaultModel() string {
	switch p {
	case ProviderAnthropic:
		return "claude-sonnet-5"
	case ProviderOpenAI:
		return "gpt-5.6-sol"
	case ProviderGemini:
		return "gemini-3.7-flash"
	case ProviderGrok:
		return "grok-4-fast"
	case ProviderDeepSeek:
		return "deepseek-chat"
	case ProviderMistral:
		return "mistral-medium-latest"
	case ProviderOllama:
		return "llama3.2"
	default:
		return ""
	}
}

// RunMode is how a provider call is made. API works everywhere the engine runs; CLI shells
// out to a local binary. CUSTOM is always CLI (no known API shape to call).
type RunMode string

const (
	RunModeAPI RunMode = "API"
	RunModeCLI RunMode = "CLI"
)

// RoundMode: FIXED runs MaxRounds then the primary agent gives the final decision.
// UNLIMITED runs until paused or unanimous agreement — it never completes on its own.
type RoundMode string

const (
	RoundModeFixed     RoundMode = "FIXED"
	RoundModeUnlimited RoundMode = "UNLIMITED"
)

// Agent is one participant. Provider is freely chosen per seat in DebateConfig — any seat
// can be any Provider, the only rule is no two seats share one (each provider gets at most
// one seat, since DebateMessage.AgentID identifies a turn by provider alone).
type Agent struct {
	ID       string   `json:"id,omitempty"`
	Provider Provider `json:"provider"`
	// Freeform for CUSTOM (no curated list); one of Provider's known model names otherwise.
	Model   string  `json:"model"`
	RunMode RunMode `json:"runMode"`
	// Overrides the global Settings CLI command for this provider, if set. Usually empty —
	// the command is resolved from Settings at run time. Kept only for backward-compat with
	// discussions saved before global CLI commands existed.
	CliCommand *string `json:"cliCommand,omitempty"`
	// Individual starting prompt/persona for this agent only.
	SystemPrompt string `json:"systemPrompt"`
	// Individual background context for this agent only.
	Context string `json:"context"`
	// Required for CUSTOM (no fixed brand to fall back on); ignored for known providers
	// unless set, in which case it overrides their brand name too.
	DisplayName string `json:"displayName"`
	// Role is the agent's functional label, e.g. "Devil's Advocate".
	Role string `json:"role"`
	// PersonaID is the predefined-persona ID this agent was seeded from, or empty.
	PersonaID string `json:"personaId"`
	// Ponytail: when true, agent responds in structured Ponytail style (per-agent).
	Ponytail bool `json:"ponytail"`
	// Temperature: optional model sampling temperature.
	Temperature *float64 `json:"temperature,omitempty"`
	// TopP: optional model nucleus sampling probability.
	TopP *float64 `json:"topP,omitempty"`
	// MaxTokens: optional maximum output completion tokens.
	MaxTokens *int `json:"maxTokens,omitempty"`
	// AllowWebSearch: optional per-agent WebSearch permission override.
	AllowWebSearch *bool `json:"allowWebSearch,omitempty"`
}

// Label is what this seat is actually called — the whole reason DisplayName exists instead
// of always using Provider.BrandName.
func (a Agent) Label() string {
	if a.DisplayName != "" {
		return a.DisplayName
	}
	return a.Provider.BrandName()
}

// NewAgent builds an Agent with the field defaults the Kotlin data class constructor has
// (RunMode defaults to CLI, matching the Kotlin side).
func NewAgent(provider Provider, model string) Agent {
	return Agent{
		ID:       fmt.Sprintf("seat_%d", rand.Intn(90000)+10000),
		Provider: provider,
		Model:    model,
		RunMode:  RunModeCLI,
	}
}

// DebateConfig is shared by every agent. Primary always takes part, always speaks first
// each round, and gives the final decision — configurable to any provider, defaults to
// Claude. The other four seats are optional extras, filled/edited/removed independently —
// up to 5 participants total. Each seat's provider is independently pickable, the only
// constraint being no two seats share a provider.
//
// JSON tags on Primary/Secondary/Tertiary intentionally keep the old Kotlin field names
// (claude/gemini/chatgpt) — that's the wire format an existing state.json was written with,
// from before the primary/secondary/tertiary rename on the Kotlin side. Keeping the same
// tags here means a legacy file decodes with no migration step.
type DebateConfig struct {
	Topic string `json:"topic"`
	// Shared background every agent sees.
	CommonContext string `json:"commonContext"`
	// Third shared card — extra background/data beyond the topic and context.
	CommonInfo string    `json:"commonInfo"`
	Primary    Agent     `json:"claude"`
	Secondary  *Agent    `json:"gemini,omitempty"`
	Tertiary   *Agent    `json:"chatgpt,omitempty"`
	Quaternary *Agent    `json:"quaternary,omitempty"`
	Quinary    *Agent    `json:"quinary,omitempty"`
	// Sixth optional seat (spec v2 — 6-agent expansion).
	Senary     *Agent    `json:"senary,omitempty"`
	RoundMode  RoundMode `json:"roundMode"`
	// Only used when RoundMode == FIXED. Default: 10
	MaxRounds int `json:"maxRounds"`
	// MasterInstructions is the frozen snapshot of the global master instructions
	// template at the time this discussion was created.
	MasterInstructions string `json:"masterInstructions"`
	// ConsensusTolerance controls how close to unanimous agreement (1.0 = all agents)
	// the debate must reach before early termination is triggered.
	ConsensusTolerance float64 `json:"consensusTolerance"`
	// Consensus controls dynamic consensus detection and early stopping.
	Consensus *ConsensusConfig `json:"consensus,omitempty"`
	// ValidateObjections, when true, triggers a second objection-check round before
	// accepting an early consensus.
	ValidateObjections bool `json:"validateObjections"`
	// AttachedFiles contains reference files attached for AI context injection.
	AttachedFiles []AttachedFile `json:"attachedFiles,omitempty"`
	// SharedMemory controls compression of shared knowledge between turns.
	SharedMemory *SharedMemoryConfig `json:"sharedMemory,omitempty"`
	// Moderation controls loop detection, moderator agent selection, and interventions.
	Moderation *ModeratorConfig `json:"moderation,omitempty"`
	// UserInterventionPolicy controls autonomous vs interactive debate moderation.
	UserInterventionPolicy string `json:"userInterventionPolicy,omitempty"`
	// TokenBudgetAction controls warning alert vs hard stop when token budget ceiling is reached.
	TokenBudgetAction TokenBudgetAction `json:"tokenBudgetAction,omitempty"`
	// Permissions defines tool execution and search capabilities.
	Permissions *PermissionConfig `json:"permissions,omitempty"`
	// HumanDialogueMode: forces conversational 2-4 sentence turns without monologues.
	HumanDialogueMode bool `json:"humanDialogueMode,omitempty"`
	// HumanDialogueDirective: custom prompt text for human dialogue mode. If empty, engine uses default.
	HumanDialogueDirective string `json:"humanDialogueDirective,omitempty"`
}

// ModeratorConfig controls moderator agent loop-detection and summary behaviour.
	// ModeratorSeatID picks the moderator by seat, so one persona among several on the same
	// provider can moderate. It wins over ModeratorProvider, which stays for old discussions.
	ModeratorSeatID     string   `json:"moderatorSeatId,omitempty"`
type ModeratorConfig struct {
	Enabled             bool     `json:"enabled"`
	ModeratorProvider   Provider `json:"moderatorProvider,omitempty"`
	Strictness          int      `json:"strictness,omitempty"`
	DetectTopicDrift    bool     `json:"detectTopicDrift,omitempty"`
	TopicDriftDirective string   `json:"topicDriftDirective,omitempty"`
}

// TokenBudgetAction specifies whether to warn or hard-stop when token budget is reached.
type TokenBudgetAction string

const (
	TokenBudgetActionWarning  TokenBudgetAction = "WARNING"
	TokenBudgetActionHardStop TokenBudgetAction = "HARD_STOP"
)

// PermissionConfig encapsulates tool and execution permissions for debates.
type PermissionConfig struct {
	AllowWebSearch         bool     `json:"allowWebSearch"`
	AllowFileRead          bool     `json:"allowFileRead"`
	AllowSafeShell         bool     `json:"allowSafeShell"`
	AllowFileWrite         bool     `json:"allowFileWrite"`
	AllowShellCommands     bool     `json:"allowShellCommands"`
	AllowMcpTools          bool            `json:"allowMcpTools"`
	AllowedCommandPatterns []string        `json:"allowedCommandPatterns,omitempty"`
	AllowedDomains         []string        `json:"allowedDomains,omitempty"`
	AgentWebSearchOverrides map[string]bool `json:"agentWebSearchOverrides,omitempty"`
}

// IsWebSearchAllowedFor returns whether WebSearch is permitted for the given agent seat ID.
func (p PermissionConfig) IsWebSearchAllowedFor(agentID string) bool {
	if p.AgentWebSearchOverrides != nil {
		if allowed, ok := p.AgentWebSearchOverrides[agentID]; ok {
			return allowed
		}
	}
	return p.AllowWebSearch
}

// DefaultPermissionConfig returns secure-and-productive safe defaults.
func DefaultPermissionConfig() PermissionConfig {
	return PermissionConfig{
		AllowWebSearch:     true,
		AllowFileRead:      true,
		AllowSafeShell:     true,
		AllowFileWrite:     false,
		AllowShellCommands: false,
		AllowMcpTools:      true,
	}
}

// SharedMemoryConfig controls how the debate's shared knowledge is compressed between turns.
type SharedMemoryConfig struct {
	Enabled            bool   `json:"enabled"`
	ModelSource        string `json:"modelSource,omitempty"`
	SummaryModel       string `json:"summaryModel,omitempty"`
	MaxSummaryTokens   int    `json:"maxSummaryTokens,omitempty"`
	IncludeOwnLastTurn bool   `json:"includeOwnLastTurn,omitempty"`
	IncludeFullRound1  bool   `json:"includeFullRound1,omitempty"`
}

// Agents is the speaking order — primary first always, then whichever others are included,
// in the order they were added.
func (c DebateConfig) Agents() []Agent {
	agents := []Agent{c.Primary}
	for _, seat := range []*Agent{c.Secondary, c.Tertiary, c.Quaternary, c.Quinary, c.Senary} {
		if seat != nil {
			agents = append(agents, *seat)
		}
	}
// ModeratorAgent is the seat that moderates and writes the wrap-up: the configured seat, else the
// first seat of the configured provider (old discussions), else the primary agent.
func (c DebateConfig) ModeratorAgent() Agent {
	if c.Moderation != nil {
		agents := c.Agents()
		if id := c.Moderation.ModeratorSeatID; id != "" {
			for _, a := range agents {
				if a.ID == id {
					return a
				}
			}
		}
		if p := c.Moderation.ModeratorProvider; p != "" {
			for _, a := range agents {
				if a.Provider == p {
					return a
				}
			}
		}
	}
	return c.Primary
}

	return agents
}

// DebateMessage is one turn's persisted result.
type DebateMessage struct {
	SeatID                  string   `json:"seatId,omitempty"`
	Provider                Provider `json:"provider,omitempty"`
	AuthorDisplayName       string   `json:"authorDisplayName,omitempty"`
	AgentID                 Provider `json:"agentId"`
	Round                   int      `json:"round"`
	Content                 string   `json:"content"`
	// Unused by current runs — kept so old persisted transcripts still decode.
	IsFinalOpinion          bool     `json:"isFinalOpinion"`
	// True when Content is an error (a turn failed) rather than an agent's reply.
	IsError                 bool     `json:"isError"`
	// True when this turn is a system annotation or status notification.
	IsSystem                bool     `json:"isSystem,omitempty"`
	// True when this message was injected by human user as a comment.
	IsUserComment           bool     `json:"isUserComment,omitempty"`
	// True when this turn was generated as a moderator intervention.
	IsModeratorIntervention bool     `json:"isModeratorIntervention,omitempty"`
	// Best-effort — only API providers report usage; CLI turns leave these nil.
	TokensIn                *int     `json:"tokensIn,omitempty"`
	TokensOut               *int     `json:"tokensOut,omitempty"`
	TokensCached            *int     `json:"tokensCached,omitempty"`
	TimestampMs             int64    `json:"timestampMs,omitempty"`
}

type ConsensusMode string

const (
	ConsensusModeUnanimous      ConsensusMode = "UNANIMOUS"
	ConsensusModeSupermajority  ConsensusMode = "SUPERMAJORITY"
	ConsensusModeSimpleMajority ConsensusMode = "SIMPLE_MAJORITY"
	ConsensusModeDisabled       ConsensusMode = "DISABLED"
)

type ConsensusStrategy string

const (
	ConsensusStrategyPrefixAndPattern ConsensusStrategy = "PREFIX_AND_PATTERN"
	ConsensusStrategyHeuristicHybrid  ConsensusStrategy = "HEURISTIC_HYBRID"
	ConsensusStrategyModelClassified  ConsensusStrategy = "MODEL_CLASSIFIED"
)

type ConsensusConfig struct {
	Mode                     ConsensusMode     `json:"mode"`
	Strategy                 ConsensusStrategy `json:"strategy"`
	MinRoundsBeforeExit      int               `json:"minRoundsBeforeExit"`
	ConsensusThreshold       float64           `json:"consensusThreshold"`
	AllowMidRoundTermination bool              `json:"allowMidRoundTermination"`
	ClassifierModel          string            `json:"classifierModel"`
}

// DebateResult is the outcome of one DebateOrchestrator.Run call — not persisted as-is,
// folded into a Discussion by the caller.
type DebateResult struct {
	Transcript         []DebateMessage `json:"transcript"`
	// Single verdict from the primary agent — only produced when FIXED rounds complete
	// naturally or the debate ends in unanimous agreement.
	Conclusion         *string         `json:"conclusion,omitempty"`
	// Set when a turn failed and ended the debate early.
	Error              *string         `json:"error,omitempty"`
	// True when Pause halted the debate mid-flight — resumable via the same transcript.
	Paused             bool            `json:"paused"`
	// Set when emergency synthesis salvaged an outcome after a turn failure.
	Warning            *string         `json:"warning,omitempty"`
	IsConsensusReached bool            `json:"isConsensusReached,omitempty"`
	EarlyExitReason    *string         `json:"earlyExitReason,omitempty"`
	TensionPairs       []TensionPair   `json:"tensionPairs,omitempty"`
	RetrievedEvidence  []RoundEvidence `json:"retrievedEvidence,omitempty"`
	CredenceLedger     *CredenceLedger `json:"credenceLedger,omitempty"`
}

var ProviderMaxInputLimits = map[Provider]int{
	ProviderAnthropic: 190_000,
	ProviderOpenAI:    120_000,
	ProviderGemini:    500_000,
	ProviderOllama:    16_000,
}

func EnsurePayloadWithinCeiling(provider Provider, transcript []DebateMessage) []DebateMessage {
	maxSafeTokens, ok := ProviderMaxInputLimits[provider]
	if !ok {
		maxSafeTokens = 64_000
	}
	estimatedTokens := 0
	for _, m := range transcript {
		if m.TokensOut != nil {
			estimatedTokens += *m.TokensOut
		} else {
			estimatedTokens += len(m.Content) / 4
		}
	}
	if float64(estimatedTokens) > float64(maxSafeTokens)*0.85 {
		if len(transcript) > 6 {
			return transcript[len(transcript)-6:]
		}
	}
	return transcript
}
