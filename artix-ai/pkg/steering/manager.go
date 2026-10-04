package steering

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var validIDRegex = regexp.MustCompile(`^[a-zA-Z0-9_\-\.\:]+$`)

// Manager provides programmatic management of local steering configuration and bindings.
type Manager struct {
	rootDir    string
	configPath string
	aggregator *Aggregator
}

// NewManager creates a steering manager for a repository.
func NewManager(rootDir string) *Manager {
	cfgPath := filepath.Join(rootDir, ".artix", "steering.json")
	if _, err := os.Stat(cfgPath); os.IsNotExist(err) {
		fallback := filepath.Join(rootDir, ".kritix", "steering.json")
		if _, ferr := os.Stat(fallback); ferr == nil {
			cfgPath = fallback
		}
	}
	return &Manager{
		rootDir:    rootDir,
		configPath: cfgPath,
		aggregator: NewAggregator(rootDir),
	}
}

// ValidateConfig enforces strict enterprise data governance rules against the SteeringConfig.
func ValidateConfig(cfg *SteeringConfig) error {
	if cfg == nil {
		return fmt.Errorf("steering config cannot be nil")
	}

	for _, src := range cfg.ExternalSources {
		if strings.TrimSpace(src.ID) == "" {
			return fmt.Errorf("external source ID cannot be empty")
		}
		if !validIDRegex.MatchString(src.ID) {
			return fmt.Errorf("invalid external source ID %q: must contain only alphanumeric, dash, underscore, dot or colon", src.ID)
		}

		switch src.Type {
		case SourceLocalSibling:
			if strings.TrimSpace(src.Path) == "" {
				return fmt.Errorf("local sibling source %q must specify path", src.ID)
			}
		case SourceRemoteHTTP:
			if strings.TrimSpace(src.URL) == "" {
				return fmt.Errorf("remote HTTP source %q must specify URL", src.ID)
			}
			u, err := url.Parse(src.URL)
			if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
				return fmt.Errorf("remote HTTP source %q has invalid HTTP/HTTPS URL %q", src.ID, src.URL)
			}
		case SourceRemoteGit:
			if strings.TrimSpace(src.URL) == "" {
				return fmt.Errorf("remote Git source %q must specify URL", src.ID)
			}
		default:
			return fmt.Errorf("external source %q has unknown type %q", src.ID, src.Type)
		}
	}

	for personaID, ruleIDs := range cfg.Bindings {
		if strings.TrimSpace(personaID) == "" {
			return fmt.Errorf("binding persona ID cannot be blank")
		}
		if !validIDRegex.MatchString(personaID) {
			return fmt.Errorf("invalid persona ID %q in bindings", personaID)
		}
		for _, rid := range ruleIDs {
			if strings.TrimSpace(rid) == "" {
				return fmt.Errorf("bound rule ID cannot be empty for persona %q", personaID)
			}
		}
	}

	for _, gr := range cfg.GlobalRules {
		if strings.TrimSpace(gr) == "" {
			return fmt.Errorf("global rule name cannot be empty")
		}
	}

	return nil
}

// LoadConfig reads .artix/steering.json or returns default empty configuration.
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

// SaveConfig validates and persists the steering configuration to .artix/steering.json.
func (m *Manager) SaveConfig(cfg *SteeringConfig) error {
	if err := ValidateConfig(cfg); err != nil {
		return fmt.Errorf("steering config validation failed: %w", err)
	}

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

// QueuePendingRule places a newly synthesized rule into the pending review queue.
func (m *Manager) QueuePendingRule(ruleID, name, ruleText, rationale string, isTaboo bool, targetRoles []string, author string) (*PendingRule, error) {
	if ruleID == "" {
		ruleID = fmt.Sprintf("rule-syn-%d", time.Now().UnixNano()%1000000)
	}
	if author == "" {
		author = "pr-synthesizer"
	}
	content := fmt.Sprintf("%s|%s|%s|%v|%s", ruleID, name, ruleText, isTaboo, strings.Join(targetRoles, ","))
	sum := sha256.Sum256([]byte(content))
	hash := hex.EncodeToString(sum[:8])

	pending := &PendingRule{
		Hash:        hash,
		RuleID:      ruleID,
		Name:        name,
		IsTaboo:     isTaboo,
		RuleText:    ruleText,
		Rationale:   rationale,
		TargetRoles: targetRoles,
		Author:      author,
		CreatedAt:   time.Now().UTC(),
	}

	pendingDir := filepath.Join(m.rootDir, ".artix", "steering", "pending")
	if err := os.MkdirAll(pendingDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create pending rules directory: %w", err)
	}

	targetFile := filepath.Join(pendingDir, fmt.Sprintf("%s.json", hash))
	data, err := json.MarshalIndent(pending, "", "  ")
	if err != nil {
		return nil, fmt.Errorf("failed to marshal pending rule: %w", err)
	}
	if err := os.WriteFile(targetFile, data, 0644); err != nil {
		return nil, fmt.Errorf("failed to write pending rule: %w", err)
	}

	return pending, nil
}

// ListPendingRules returns all rules in the review queue awaiting approval.
func (m *Manager) ListPendingRules() ([]PendingRule, error) {
	pendingDir := filepath.Join(m.rootDir, ".artix", "steering", "pending")
	entries, err := os.ReadDir(pendingDir)
	if err != nil {
		if os.IsNotExist(err) {
			return []PendingRule{}, nil
		}
		return nil, err
	}

	var rules []PendingRule
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".json") {
			p := filepath.Join(pendingDir, e.Name())
			data, err := os.ReadFile(p)
			if err != nil {
				continue
			}
			var r PendingRule
			if err := json.Unmarshal(data, &r); err == nil {
				rules = append(rules, r)
			}
		}
	}
	return rules, nil
}

// ApproveRule commits a pending rule from the review queue into active steering rules.
// Requires a designated approver role (e.g. senior_architect, lead, admin).
func (m *Manager) ApproveRule(hash string, approverRole string) (*RuleFile, error) {
	if strings.TrimSpace(approverRole) == "" {
		return nil, fmt.Errorf("approval rejected: approver role or identity must be specified")
	}

	pendingDir := filepath.Join(m.rootDir, ".artix", "steering", "pending")
	pendingFile := filepath.Join(pendingDir, fmt.Sprintf("%s.json", hash))
	data, err := os.ReadFile(pendingFile)
	if err != nil {
		return nil, fmt.Errorf("pending rule with hash %q not found: %w", hash, err)
	}

	var pr PendingRule
	if err := json.Unmarshal(data, &pr); err != nil {
		return nil, fmt.Errorf("failed to parse pending rule: %w", err)
	}

	// Persist active rule markdown file in .artix/steering/
	steeringDir := filepath.Join(m.rootDir, ".artix", "steering")
	_ = os.MkdirAll(steeringDir, 0755)

	ruleMdPath := filepath.Join(steeringDir, fmt.Sprintf("%s.md", pr.RuleID))
	ruleContent := fmt.Sprintf("# Rule: %s\n\n**ID:** `%s`  \n**IsTaboo:** `%v`  \n**ApprovedBy:** `%s`  \n**Rationale:** %s\n\n%s\n",
		pr.Name, pr.RuleID, pr.IsTaboo, approverRole, pr.Rationale, pr.RuleText)

	if err := os.WriteFile(ruleMdPath, []byte(ruleContent), 0644); err != nil {
		return nil, fmt.Errorf("failed to write approved rule: %w", err)
	}

	// Bind rule to its target roles in steering.json
	for _, role := range pr.TargetRoles {
		_ = m.BindRule(role, pr.RuleID)
	}

	// Remove from pending review queue
	_ = os.Remove(pendingFile)

	return &RuleFile{
		ID:         pr.RuleID,
		Name:       pr.Name,
		RelPath:    filepath.Join(".artix", "steering", fmt.Sprintf("%s.md", pr.RuleID)),
		SourceType: SourceLocalFile,
		Content:    ruleContent,
	}, nil
}

// RejectRule discards a pending rule from the review queue.
func (m *Manager) RejectRule(hash string) error {
	pendingDir := filepath.Join(m.rootDir, ".artix", "steering", "pending")
	pendingFile := filepath.Join(pendingDir, fmt.Sprintf("%s.json", hash))
	if err := os.Remove(pendingFile); err != nil {
		return fmt.Errorf("failed to reject pending rule %q: %w", hash, err)
	}
	return nil
}
