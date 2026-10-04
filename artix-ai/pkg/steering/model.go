package steering

import (
	"time"

	"socratix/pkg/model"
)

// PendingRule represents a synthesized rule awaiting architectural approval.
type PendingRule struct {
	Hash        string    `json:"hash"`
	RuleID      string    `json:"ruleId"`
	Name        string    `json:"name"`
	IsTaboo     bool      `json:"isTaboo"`
	RuleText    string    `json:"ruleText"`
	Rationale   string    `json:"rationale"`
	TargetRoles []string  `json:"targetRoles"`
	Author      string    `json:"author"`
	CreatedAt   time.Time `json:"createdAt"`
}

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
	ExternalSources  []ExternalSource    `json:"external_sources,omitempty"`
	GlobalRules      []string            `json:"global_rules,omitempty"`
	GlobalTaboos     GlobalTabooSpace    `json:"global_taboos,omitempty"`
	AnalyzerCommands []string            `json:"analyzer_commands,omitempty"`
	Bindings         map[string][]string `json:"bindings,omitempty"` // persona_id -> list of rule IDs
}

// SteeringConfigJSONSchema is the formal published JSON Schema governing .artix/steering.json.
const SteeringConfigJSONSchema = `{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "SteeringConfig",
  "type": "object",
  "properties": {
    "external_sources": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["id", "name", "type"],
        "properties": {
          "id": {"type": "string"},
          "name": {"type": "string"},
          "type": {"type": "string", "enum": ["local_file", "standard_symlink", "remote_git", "remote_http", "local_sibling_path"]},
          "url": {"type": "string"},
          "path": {"type": "string"},
          "branch": {"type": "string"},
          "pollInterval": {"type": "string"}
        }
      }
    },
    "global_rules": {
      "type": "array",
      "items": {"type": "string"}
    },
    "global_taboos": {
      "type": "object",
      "properties": {
        "forbiddenArguments": {
          "type": "array",
          "items": {"type": "string"}
        }
      }
    },
    "analyzer_commands": {
      "type": "array",
      "items": {"type": "string"}
    },
    "bindings": {
      "type": "object",
      "additionalProperties": {
        "type": "array",
        "items": {"type": "string"}
      }
    }
  },
  "additionalProperties": false
}`

// GlobalTabooSpace represents persona-independent, workspace-wide taboo constraints.
type GlobalTabooSpace struct {
	ForbiddenArguments []string `json:"forbiddenArguments"`
}

// PersonaSteeringContext holds the compiled constraints for an individual persona.
type PersonaSteeringContext struct {
	PersonaID    string                `json:"personaId"`
	BoundRules   []RuleFile            `json:"boundRules"`
	Taboos       model.TabooSpace      `json:"taboos"`
	GlobalTaboos GlobalTabooSpace      `json:"globalTaboos,omitempty"`
	Heuristics   []model.HeuristicRule `json:"heuristics"`
}
