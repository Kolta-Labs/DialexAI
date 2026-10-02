package steering

import "socratix/pkg/model"

// SourceType represents where a steering rule came from.
type SourceType string

const (
	SourceLocalFile    SourceType = "local_file"
	SourceStandard     SourceType = "standard_symlink"
	SourceRemoteGit    SourceType = "remote_git"
	SourceRemoteHTTP   SourceType = "remote_http"
	SourceLocalSibling SourceType = "local_sibling_path"
)

// RuleFile represents an individual steering document.
type RuleFile struct {
	ID         string     `json:"id"`
	Name       string     `json:"name"`
	RelPath    string     `json:"relPath"`
	SourceType SourceType `json:"sourceType"`
	Content    string     `json:"content"`
	Hash       string     `json:"hash,omitempty"`
}

// ExternalSource defines a remote or sibling steering location to sync from.
type ExternalSource struct {
	ID           string     `json:"id"`
	Name         string     `json:"name"`
	Type         SourceType `json:"type"`
	URL          string     `json:"url,omitempty"`
	Path         string     `json:"path,omitempty"`
	Branch       string     `json:"branch,omitempty"`
	PollInterval string     `json:"pollInterval,omitempty"`
}

// SteeringConfig represents the persistent .artix/steering.json configuration.
type SteeringConfig struct {
	ExternalSources []ExternalSource    `json:"external_sources,omitempty"`
	GlobalRules     []string            `json:"global_rules,omitempty"`
	Bindings        map[string][]string `json:"bindings,omitempty"` // persona_id -> list of rule IDs
}

// PersonaSteeringContext holds the compiled constraints for an individual persona.
type PersonaSteeringContext struct {
	PersonaID  string                `json:"personaId"`
	BoundRules []RuleFile            `json:"boundRules"`
	Taboos     model.TabooSpace      `json:"taboos"`
	Heuristics []model.HeuristicRule `json:"heuristics"`
}
