package repo

import (
	"bufio"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

var defaultIgnoredDirs = map[string]bool{
	".git":         true,
	"node_modules": true,
	"build":        true,
	".gradle":      true,
	".idea":        true,
	".kotlin":      true,
	"target":       true,
	"dist":         true,
	"bin":          true,
	"vendor":       true,
	"__pycache__":  true,
	".venv":        true,
}

// FileEntry represents a file in the workspace tree.
type FileEntry struct {
	RelPath  string `json:"relPath"`
	Size     int64  `json:"size"`
	IsDir    bool   `json:"isDir"`
	Language string `json:"language,omitempty"`
}

// TreeIndex maps the repository files.
type TreeIndex struct {
	RootDir string      `json:"rootDir"`
	Files   []FileEntry `json:"files"`
}

// gitVisibleFiles returns the set of files git considers part of the project (tracked plus
// untracked-but-not-ignored), as slash-separated paths. It returns nil outside a git repo or
// when git is unavailable, in which case only the built-in ignore rules apply.
func gitVisibleFiles(rootDir string) map[string]bool {
	out, err := exec.Command("git", "-C", rootDir, "ls-files", "-co", "--exclude-standard", "-z").Output()
	if err != nil {
		return nil
	}
	set := make(map[string]bool)
	for _, p := range strings.Split(string(out), "\x00") {
		if p != "" {
			set[p] = true
		}
	}
	return set
}

// ScanTree walks the repository root and collects unignored files.
func ScanTree(rootDir string, maxFiles int) (*TreeIndex, error) {
	if maxFiles <= 0 {
		maxFiles = 5000
	}

	visible := gitVisibleFiles(rootDir)
	index := &TreeIndex{
		RootDir: rootDir,
		Files:   make([]FileEntry, 0),
	}

	err := filepath.WalkDir(rootDir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // skip inaccessible files
		}

		rel, err := filepath.Rel(rootDir, path)
		if err != nil || rel == "." {
			return nil
		}

		name := d.Name()
		if d.IsDir() {
			if defaultIgnoredDirs[name] || name == ".git" {
				return filepath.SkipDir
			}
			return nil
		}

		// Skip hidden dotfiles
		if strings.HasPrefix(name, ".") && name != ".gitignore" {
			return nil
		}

		if visible != nil && !visible[filepath.ToSlash(rel)] {
			return nil // gitignored
		}

		if len(index.Files) >= maxFiles {
			return filepath.SkipAll
		}

		info, err := d.Info()
		if err != nil {
			return nil
		}

		entry := FileEntry{
			RelPath:  rel,
			Size:     info.Size(),
			IsDir:    false,
			Language: detectLanguage(name),
		}

		index.Files = append(index.Files, entry)
		return nil
	})

	if err != nil && err != filepath.SkipAll {
		return nil, fmt.Errorf("failed to scan tree: %w", err)
	}

	return index, nil
}

// ReadFileSnippet reads lines from startLine to endLine (1-indexed, inclusive).
func ReadFileSnippet(filePath string, startLine, endLine int) (string, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return "", fmt.Errorf("failed to open file %s: %w", filePath, err)
	}
	defer file.Close()

	var sb strings.Builder
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 0, 64*1024), 16*1024*1024) // minified files have very long lines
	currentLine := 1

	for scanner.Scan() {
		if (startLine <= 0 || currentLine >= startLine) && (endLine <= 0 || currentLine <= endLine) {
			sb.WriteString(scanner.Text())
			sb.WriteByte('\n')
		}
		if endLine > 0 && currentLine > endLine {
			break
		}
		currentLine++
	}

	if err := scanner.Err(); err != nil {
		return "", fmt.Errorf("error reading file %s: %w", filePath, err)
	}

	return sb.String(), nil
}

func detectLanguage(filename string) string {
	ext := strings.ToLower(filepath.Ext(filename))
	switch ext {
	case ".go":
		return "go"
	case ".kt", ".kts":
		return "kotlin"
	case ".swift":
		return "swift"
	case ".rs":
		return "rust"
	case ".ts", ".tsx":
		return "typescript"
	case ".js", ".jsx":
		return "javascript"
	case ".py":
		return "python"
	case ".java":
		return "java"
	case ".json":
		return "json"
	case ".md":
		return "markdown"
	case ".yaml", ".yml":
		return "yaml"
	case ".sql":
		return "sql"
	default:
		return "text"
	}
}
