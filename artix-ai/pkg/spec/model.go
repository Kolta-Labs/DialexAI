package spec

import (
	"artix/pkg/repo"
	"artix/pkg/steering"
)

// StyleVector defines the communication and brevity profile.
type StyleVector string

const (
	StyleStandard StyleVector = "standard"
	StylePonytail StyleVector = "ponytail" // Structured executive summaries, bullet trade-offs
	StyleCaveman  StyleVector = "caveman"  // High density, zero fluff, direct code commands
)

// Scenario represents a Gherkin-formatted acceptance test case.
type Scenario struct {
	Name  string `json:"name"`
	Given string `json:"given"`
	When  string `json:"when"`
	Then  string `json:"then"`
}

// ArchitectureDecision records a technical design choice (ADR).
type ArchitectureDecision struct {
	ID           string   `json:"id"`
	Title        string   `json:"title"`
	Context      string   `json:"context"`
	Decision     string   `json:"decision"`
	Consequences []string `json:"consequences"`
}

// FileMutation declares an intended change to a file.
type FileMutation struct {
	Path      string `json:"path"`
	Action    string `json:"action"` // "create", "modify", "delete"
	Rationale string `json:"rationale"`
}

// StorySpec represents a complete, verified technical specification for a feature or story.
type StorySpec struct {
	ID                 string                 `json:"id"`
	Title              string                 `json:"title"`
	UserStory          string                 `json:"userStory"`
	InScope            []string               `json:"inScope"`
	OutOfScope         []string               `json:"outOfScope"`
	AcceptanceCriteria []Scenario             `json:"acceptanceCriteria"`
	Decisions          []ArchitectureDecision `json:"decisions"`
	FileManifest       []FileMutation         `json:"fileManifest"`
	TestCommands       []string               `json:"testCommands"`
	Style              StyleVector            `json:"style"`
	RawMarkdown        string                 `json:"rawMarkdown,omitempty"`
}

// PlanningContext contains all background data required for council deliberation.
type PlanningContext struct {
	StoryPrompt string                            `json:"storyPrompt"`
	RepoContext *repo.RepositoryContext           `json:"repoContext"`
	Steering    []steering.PersonaSteeringContext `json:"steering"`
	Style       StyleVector                       `json:"style"`
	Attached    []string                          `json:"attached,omitempty"`
}
