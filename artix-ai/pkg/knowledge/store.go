package knowledge

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Store manages persistence of Knowledge Items.
type Store struct {
	projectDir string
	globalDir  string
	mu         sync.RWMutex
}

// NewStore creates a knowledge store for a repository.
func NewStore(projectDir string) *Store {
	home, _ := os.UserHomeDir()
	globalDir := ""
	if home != "" {
		globalDir = filepath.Join(home, ".artix", "knowledge")
	}

	pDir := ""
	if projectDir != "" {
		pDir = filepath.Join(projectDir, ".artix", "knowledge")
		_ = os.MkdirAll(pDir, 0755)
	}

	return &Store{
		projectDir: pDir,
		globalDir:  globalDir,
	}
}

// Save persists a Knowledge Item to JSON disk.
func (s *Store) Save(ki *KnowledgeItem) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if ki.ID == "" {
		ki.ID = fmt.Sprintf("ki-%d", time.Now().UnixNano())
	}
	if ki.CreatedAt.IsZero() {
		ki.CreatedAt = time.Now()
	}

	dir := s.projectDir
	if dir == "" {
		dir = s.globalDir
	}
	_ = os.MkdirAll(dir, 0755)

	targetFile := filepath.Join(dir, fmt.Sprintf("%s.json", ki.ID))
	data, err := json.MarshalIndent(ki, "", "  ")
	if err != nil {
		return fmt.Errorf("failed to marshal knowledge item: %w", err)
	}

	return os.WriteFile(targetFile, data, 0644)
}

// Get retrieves a Knowledge Item by ID.
func (s *Store) Get(id string) (*KnowledgeItem, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	dirs := []string{s.projectDir, s.globalDir}
	for _, dir := range dirs {
		if dir == "" {
			continue
		}
		p := filepath.Join(dir, fmt.Sprintf("%s.json", id))
		if data, err := os.ReadFile(p); err == nil {
			var ki KnowledgeItem
			if err := json.Unmarshal(data, &ki); err == nil {
				return &ki, nil
			}
		}
	}

	return nil, fmt.Errorf("knowledge item %s not found", id)
}

// List returns all stored Knowledge Items.
func (s *Store) List() ([]KnowledgeItem, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	itemMap := make(map[string]KnowledgeItem)
	dirs := []string{s.globalDir, s.projectDir}

	for _, dir := range dirs {
		if dir == "" {
			continue
		}
		entries, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if strings.HasSuffix(e.Name(), ".json") {
				p := filepath.Join(dir, e.Name())
				if data, err := os.ReadFile(p); err == nil {
					var ki KnowledgeItem
					if err := json.Unmarshal(data, &ki); err == nil {
						itemMap[ki.ID] = ki
					}
				}
			}
		}
	}

	items := make([]KnowledgeItem, 0, len(itemMap))
	for _, ki := range itemMap {
		items = append(items, ki)
	}
	return items, nil
}

// Delete removes a Knowledge Item from the project store.
func (s *Store) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.projectDir != "" {
		p := filepath.Join(s.projectDir, fmt.Sprintf("%s.json", id))
		return os.Remove(p)
	}
	return nil
}
