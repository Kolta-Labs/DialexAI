package repo

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// Ecosystem represents a detected programming language / build system.
type Ecosystem string

const (
	EcosystemGradleKMP Ecosystem = "gradle_kmp"
	EcosystemGo        Ecosystem = "go"
	EcosystemRust      Ecosystem = "rust"
	EcosystemNode      Ecosystem = "node_typescript"
	EcosystemPython    Ecosystem = "python"
	EcosystemUnknown   Ecosystem = "unknown"
)

// RepositoryContext represents the discovered metadata of a repository workspace.
type RepositoryContext struct {
	RootDir        string      `json:"rootDir"`
	IsGit          bool        `json:"isGit"`
	CurrentBranch  string      `json:"currentBranch,omitempty"`
	HeadCommit     string      `json:"headCommit,omitempty"`
	IsDirty        bool        `json:"isDirty"`
	UntrackedFiles []string    `json:"untrackedFiles,omitempty"`
	ModifiedFiles  []string    `json:"modifiedFiles,omitempty"`
	DetectedEcos   []Ecosystem `json:"detectedEcosystems"`
	BuildManifests []string    `json:"buildManifests"`
}

// DetectContext discovers the repository root and git/build metadata starting from startPath.
func DetectContext(startPath string) (*RepositoryContext, error) {
	absPath, err := filepath.Abs(startPath)
	if err != nil {
		return nil, fmt.Errorf("failed to get absolute path for %s: %w", startPath, err)
	}

	root, isGit := findRepoRoot(absPath)
	ctx := &RepositoryContext{
		RootDir:      root,
		IsGit:        isGit,
		DetectedEcos: []Ecosystem{},
	}

	// Detect Build Systems / Ecosystems
	ctx.detectBuildSystems()

	// Detect Git metadata if inside a git repository
	if isGit {
		ctx.detectGitMetadata()
	}

	return ctx, nil
}

func findRepoRoot(start string) (string, bool) {
	curr := start
	for {
		gitDir := filepath.Join(curr, ".git")
		if info, err := os.Stat(gitDir); err == nil && (info.IsDir() || !info.IsDir()) {
			return curr, true
		}

		parent := filepath.Dir(curr)
		if parent == curr {
			// Reached filesystem root without finding .git
			return start, false
		}
		curr = parent
	}
}

func (c *RepositoryContext) detectBuildSystems() {
	checks := []struct {
		eco   Ecosystem
		files []string
	}{
		{
			eco:   EcosystemGradleKMP,
			files: []string{"settings.gradle.kts", "build.gradle.kts", "settings.gradle", "build.gradle", "gradlew"},
		},
		{
			eco:   EcosystemGo,
			files: []string{"go.mod", "go.work"},
		},
		{
			eco:   EcosystemRust,
			files: []string{"Cargo.toml"},
		},
		{
			eco:   EcosystemNode,
			files: []string{"package.json", "tsconfig.json"},
		},
		{
			eco:   EcosystemPython,
			files: []string{"pyproject.toml", "requirements.txt", "Pipfile", "setup.py"},
		},
	}

	for _, check := range checks {
		for _, f := range check.files {
			p := filepath.Join(c.RootDir, f)
			if _, err := os.Stat(p); err == nil {
				c.BuildManifests = append(c.BuildManifests, f)
				if !containsEcosystem(c.DetectedEcos, check.eco) {
					c.DetectedEcos = append(c.DetectedEcos, check.eco)
				}
				break
			}
		}
	}

	if len(c.DetectedEcos) == 0 {
		c.DetectedEcos = append(c.DetectedEcos, EcosystemUnknown)
	}
}

func (c *RepositoryContext) detectGitMetadata() {
	// Branch
	if out, err := exec.Command("git", "-C", c.RootDir, "rev-parse", "--abbrev-ref", "HEAD").Output(); err == nil {
		c.CurrentBranch = strings.TrimSpace(string(out))
	}

	// Commit
	if out, err := exec.Command("git", "-C", c.RootDir, "rev-parse", "--short", "HEAD").Output(); err == nil {
		c.HeadCommit = strings.TrimSpace(string(out))
	}

	// Status (Modified / Untracked / Dirty)
	if out, err := exec.Command("git", "-C", c.RootDir, "status", "--porcelain").Output(); err == nil {
		lines := strings.Split(strings.TrimSpace(string(out)), "\n")
		for _, line := range lines {
			if len(line) < 3 {
				continue
			}
			c.IsDirty = true
			code := line[:2]
			file := strings.TrimSpace(line[3:])
			if strings.HasPrefix(code, "??") {
				c.UntrackedFiles = append(c.UntrackedFiles, file)
			} else {
				c.ModifiedFiles = append(c.ModifiedFiles, file)
			}
		}
	}
}

func containsEcosystem(slice []Ecosystem, item Ecosystem) bool {
	for _, e := range slice {
		if e == item {
			return true
		}
	}
	return false
}
