package persona

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	"dialex/pkg/model"
)

// Registry manages built-in and user-customized SWE personas.
type Registry struct {
	mu            sync.RWMutex
	personas      map[string]model.Persona
	customUserDir string
	customRepoDir string
}

// NewRegistry creates a new persona registry populated with built-in SWE personas.
func NewRegistry(repoRoot string) *Registry {
	r := &Registry{
		personas: make(map[string]model.Persona),
	}

	// 1. Load built-ins
	for _, p := range GetAllBuiltinPersonas() {
		r.personas[p.ID] = p
	}

	// 2. Discover user global dir (~/.kritix/personas)
	if home, err := os.UserHomeDir(); err == nil {
		r.customUserDir = filepath.Join(home, ".kritix", "personas")
		_ = os.MkdirAll(r.customUserDir, 0755)
		r.loadDir(r.customUserDir, false)
	}

	// 3. Discover repo-level dir (<repo>/.kritix/personas)
	if repoRoot != "" {
		r.customRepoDir = filepath.Join(repoRoot, ".kritix", "personas")
		r.loadDir(r.customRepoDir, true)
	}

	return r
}

// Get returns a persona by ID (case-insensitive).
func (r *Registry) Get(id string) (model.Persona, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()

	p, ok := r.personas[id]
	return p, ok
}

// List returns all registered personas sorted by category.
func (r *Registry) List() []model.Persona {
	r.mu.RLock()
	defer r.mu.RUnlock()

	res := make([]model.Persona, 0, len(r.personas))
	for _, p := range r.personas {
		res = append(res, p)
	}
	return res
}

// SaveCustom persists a custom or edited persona to disk (repo or global).
func (r *Registry) SaveCustom(p model.Persona, projectScope bool) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if p.ID == "" {
		return fmt.Errorf("persona ID cannot be empty")
	}

	targetDir := r.customUserDir
	if projectScope {
		if r.customRepoDir == "" {
			return fmt.Errorf("no repository root configured for project-scoped persona")
		}
		targetDir = r.customRepoDir
	}

	if err := os.MkdirAll(targetDir, 0755); err != nil {
		return fmt.Errorf("failed to create persona dir: %w", err)
	}

	p.IsSystem = false
	data, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal persona: %w", err)
	}

	filePath := filepath.Join(targetDir, fmt.Sprintf("%s.json", p.ID))
	if err := os.WriteFile(filePath, data, 0644); err != nil {
		return fmt.Errorf("failed to write persona file: %w", err)
	}

	r.personas[p.ID] = p
	return nil
}

// DeleteCustom removes a custom persona from memory and disk.
func (r *Registry) DeleteCustom(id string) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	p, ok := r.personas[id]
	if !ok {
		return fmt.Errorf("persona %q not found", id)
	}
	if p.IsSystem {
		return fmt.Errorf("cannot delete built-in system persona %q", id)
	}

	// Try removing from repo first, then global
	if r.customRepoDir != "" {
		_ = os.Remove(filepath.Join(r.customRepoDir, fmt.Sprintf("%s.json", id)))
	}
	if r.customUserDir != "" {
		_ = os.Remove(filepath.Join(r.customUserDir, fmt.Sprintf("%s.json", id)))
	}

	delete(r.personas, id)
	return nil
}

func (r *Registry) loadDir(dir string, isProject bool) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}

	for _, entry := range entries {
		if entry.IsDir() || filepath.Ext(entry.Name()) != ".json" {
			continue
		}

		path := filepath.Join(dir, entry.Name())
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}

		var p model.Persona
		if err := json.Unmarshal(data, &p); err == nil && p.ID != "" {
			p.IsSystem = false
			r.personas[p.ID] = p
		}
	}
}
