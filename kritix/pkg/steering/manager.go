package steering

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

// Manager provides programmatic management of local steering configuration and bindings.
type Manager struct {
	rootDir    string
	configPath string
	aggregator *Aggregator
}

// NewManager creates a steering manager for a repository.
func NewManager(rootDir string) *Manager {
	return &Manager{
		rootDir:    rootDir,
		configPath: filepath.Join(rootDir, ".kritix", "steering.json"),
		aggregator: NewAggregator(rootDir),
	}
}

// LoadConfig reads .kritix/steering.json or returns default empty configuration.
func (m *Manager) LoadConfig() (*SteeringConfig, error) {
	if _, err := os.Stat(m.configPath); os.IsNotExist(err) {
		return &SteeringConfig{
			ExternalSources: make([]ExternalSource, 0),
			GlobalRules:     make([]string, 0),
			Bindings:        make(map[string][]string),
		}, nil
	}

	data, err := os.ReadFile(m.configPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read steering config: %w", err)
	}

	var cfg SteeringConfig
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("failed to parse steering config: %w", err)
	}

	if cfg.Bindings == nil {
		cfg.Bindings = make(map[string][]string)
	}
	return &cfg, nil
}

// SaveConfig persists the steering configuration to .kritix/steering.json.
func (m *Manager) SaveConfig(cfg *SteeringConfig) error {
	_ = os.MkdirAll(filepath.Dir(m.configPath), 0755)
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to serialize steering config: %w", err)
	}
	return os.WriteFile(m.configPath, data, 0644)
}

// ListRules discovers and returns all active repository steering rules.
func (m *Manager) ListRules() ([]RuleFile, error) {
	return m.aggregator.CollectLocalRules()
}

// BindRule associates a rule ID with a target SWE persona.
func (m *Manager) BindRule(personaID, ruleID string) error {
	cfg, err := m.LoadConfig()
	if err != nil {
		return err
	}

	existing := cfg.Bindings[personaID]
	for _, id := range existing {
		if id == ruleID {
			return nil // already bound
		}
	}

	cfg.Bindings[personaID] = append(existing, ruleID)
	return m.SaveConfig(cfg)
}

// UnbindRule removes an association between a rule ID and a target persona.
func (m *Manager) UnbindRule(personaID, ruleID string) error {
	cfg, err := m.LoadConfig()
	if err != nil {
		return err
	}

	existing := cfg.Bindings[personaID]
	var updated []string
	for _, id := range existing {
		if id != ruleID {
			updated = append(updated, id)
		}
	}

	cfg.Bindings[personaID] = updated
	return m.SaveConfig(cfg)
}
