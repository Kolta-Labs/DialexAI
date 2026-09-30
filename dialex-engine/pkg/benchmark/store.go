package benchmark

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

// Store manages persistence of benchmark runs and custom test cases.
type Store struct {
	mu          sync.RWMutex
	filePath    string
	runs        []BenchmarkRun
	customCases []BenchmarkCase
}

type persistedBenchmarkData struct {
	Runs        []BenchmarkRun  `json:"runs"`
	CustomCases []BenchmarkCase `json:"customCases"`
}

// NewStore initializes a benchmark store in the specified directory.
func NewStore(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0755); err != nil {
		return nil, err
	}
	filePath := filepath.Join(dir, "benchmarks.json")
	s := &Store{
		filePath:    filePath,
		runs:        []BenchmarkRun{},
		customCases: []BenchmarkCase{},
	}
	_ = s.load()
	return s, nil
}

// NewMemoryStore creates an in-memory benchmark store for testing.
func NewMemoryStore() *Store {
	return &Store{
		filePath:    "",
		runs:        []BenchmarkRun{},
		customCases: []BenchmarkCase{},
	}
}

func (s *Store) load() error {
	if s.filePath == "" {
		return nil
	}
	data, err := os.ReadFile(s.filePath)
	if err != nil {
		return err
	}
	var p persistedBenchmarkData
	if err := json.Unmarshal(data, &p); err != nil {
		return err
	}
	s.runs = p.Runs
	s.customCases = p.CustomCases
	return nil
}

func (s *Store) save() error {
	if s.filePath == "" {
		return nil
	}
	p := persistedBenchmarkData{
		Runs:        s.runs,
		CustomCases: s.customCases,
	}
	data, err := json.MarshalIndent(p, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.filePath + ".tmp"
	if err := os.WriteFile(tmp, data, 0644); err != nil {
		return err
	}
	return os.Rename(tmp, s.filePath)
}

// ListCases returns all bundled cases plus any user-defined custom cases.
func (s *Store) ListCases() []BenchmarkCase {
	s.mu.RLock()
	defer s.mu.RUnlock()

	bundled := BundledDialexBench10()
	result := make([]BenchmarkCase, len(bundled)+len(s.customCases))
	copy(result, bundled)
	copy(result[len(bundled):], s.customCases)
	return result
}

// GetCase retrieves a benchmark case by ID.
func (s *Store) GetCase(id string) *BenchmarkCase {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, c := range s.customCases {
		if c.ID == id {
			return &c
		}
	}
	return GetBundledCaseByID(id)
}

// AddCustomCase saves a new user-defined architectural dilemma.
func (s *Store) AddCustomCase(c BenchmarkCase) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	c.IsBundled = false
	s.customCases = append(s.customCases, c)
	return s.save()
}

// SaveRun records a completed benchmark run.
func (s *Store) SaveRun(run BenchmarkRun) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Prepend to show most recent first
	s.runs = append([]BenchmarkRun{run}, s.runs...)
	return s.save()
}

// ListRuns returns all historical benchmark runs.
func (s *Store) ListRuns() []BenchmarkRun {
	s.mu.RLock()
	defer s.mu.RUnlock()

	res := make([]BenchmarkRun, len(s.runs))
	copy(res, s.runs)
	return res
}

// GetRun retrieves a run by its ID.
func (s *Store) GetRun(id string) *BenchmarkRun {
	s.mu.RLock()
	defer s.mu.RUnlock()

	for _, r := range s.runs {
		if r.ID == id {
			return &r
		}
	}
	return nil
}

// GetSummary calculates the aggregate statistical summary across all historical runs.
func (s *Store) GetSummary() BenchmarkSummary {
	s.mu.RLock()
	defer s.mu.RUnlock()

	return ComputeSummary(s.runs)
}
