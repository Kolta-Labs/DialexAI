package steering

import (
	"crypto/sha256"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// Aggregator collects all local steering documents within a repository.
type Aggregator struct {
	repoRoot string
}

// NewAggregator creates an aggregator for a repository root.
func NewAggregator(repoRoot string) *Aggregator {
	return &Aggregator{repoRoot: repoRoot}
}

// CollectLocalRules scans the repository and returns discovered steering documents.
func (a *Aggregator) CollectLocalRules() ([]RuleFile, error) {
	var rules []RuleFile
	seen := make(map[string]bool)

	// 1. Check Root Standard Steering Files (CLAUDE.md, AGENTS.md, GEMINI.md, .cursorrules)
	rootCandidates := []string{"CLAUDE.md", "AGENTS.md", "GEMINI.md", ".cursorrules"}
	for _, fname := range rootCandidates {
		p := filepath.Join(a.repoRoot, fname)
		if content, err := os.ReadFile(p); err == nil && len(content) > 0 {
			hash := fmt.Sprintf("%x", sha256.Sum256(content))
			if !seen[hash] {
				seen[hash] = true
				rules = append(rules, RuleFile{
					ID:         strings.ToLower(strings.TrimSuffix(fname, filepath.Ext(fname))),
					Name:       fname,
					RelPath:    fname,
					SourceType: SourceStandard,
					Content:    string(content),
					Hash:       hash,
				})
			}
		}
	}

	// 2. Scan .standards directory (if present or symlinked)
	standardsDir := filepath.Join(a.repoRoot, ".standards")
	if info, err := os.Stat(standardsDir); err == nil && info.IsDir() {
		_ = filepath.WalkDir(standardsDir, func(path string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() || filepath.Ext(d.Name()) != ".md" {
				return nil
			}
			content, err := os.ReadFile(path)
			if err != nil || len(content) == 0 {
				return nil
			}
			hash := fmt.Sprintf("%x", sha256.Sum256(content))
			if !seen[hash] {
				seen[hash] = true
				rel, _ := filepath.Rel(a.repoRoot, path)
				rules = append(rules, RuleFile{
					ID:         "std_" + strings.TrimSuffix(d.Name(), ".md"),
					Name:       d.Name(),
					RelPath:    rel,
					SourceType: SourceStandard,
					Content:    string(content),
					Hash:       hash,
				})
			}
			return nil
		})
	}

	// 3. Scan .kritix/steering and .dialex/steering directories
	projectDirs := []string{
		filepath.Join(a.repoRoot, ".kritix", "steering"),
		filepath.Join(a.repoRoot, ".dialex", "steering"),
	}

	for _, pDir := range projectDirs {
		if info, err := os.Stat(pDir); err == nil && info.IsDir() {
			_ = filepath.WalkDir(pDir, func(path string, d fs.DirEntry, err error) error {
				if err != nil || d.IsDir() || filepath.Ext(d.Name()) != ".md" {
					return nil
				}
				content, err := os.ReadFile(path)
				if err != nil || len(content) == 0 {
					return nil
				}
				hash := fmt.Sprintf("%x", sha256.Sum256(content))
				if !seen[hash] {
					seen[hash] = true
					rel, _ := filepath.Rel(a.repoRoot, path)
					rules = append(rules, RuleFile{
						ID:         "proj_" + strings.TrimSuffix(d.Name(), ".md"),
						Name:       d.Name(),
						RelPath:    rel,
						SourceType: SourceLocalFile,
						Content:    string(content),
						Hash:       hash,
					})
				}
				return nil
			})
		}
	}

	return rules, nil
}
