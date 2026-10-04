package impact

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// Sentinel errors returned by this package.
var (
	// ErrInvalidBaseRef is returned when the supplied base git ref contains
	// characters outside the safe alphabet [A-Za-z0-9._/\-].
	ErrInvalidBaseRef = errors.New("impact: invalid base ref (must match ^[A-Za-z0-9._/\\-]+$)")

	// ErrUnmappedChange is returned when at least one changed file has no
	// corresponding entry in the impact map and is not explicitly ignored.
	ErrUnmappedChange = errors.New("impact: changed file has no test mapping and is not in the ignore list")
)

// validBaseRef enforces a safe character set to prevent shell injection through the ref argument.
var validBaseRef = regexp.MustCompile(`^[A-Za-z0-9._/][A-Za-z0-9._/\-]*$`)

// impactMap is the JSON structure expected on disk.
type impactMap struct {
	// Globs maps a file glob pattern to a list of test spec paths.
	// Supported: filepath.Match per segment; "**" works as a full-segment wildcard only.
	// ponytail: "**" does NOT match mid-segment patterns like "src/foo**bar/".
	Globs map[string][]string `json:"globs"`

	// Ignore is a list of glob patterns for changed files that should be silently
	// excluded from impact analysis. Matched files do not require a test mapping.
	Ignore []string `json:"ignore"`
}

// ChangedFiles runs `git diff --name-only <baseRef>...HEAD` in repoDir and
// returns the list of files changed between baseRef and HEAD.
// baseRef is validated against a strict safe-character regexp before use.
// No shell is involved; exec.CommandContext is used directly.
func ChangedFiles(ctx context.Context, repoDir, baseRef string) ([]string, error) {
	if !validBaseRef.MatchString(baseRef) {
		return nil, fmt.Errorf("%w: %q", ErrInvalidBaseRef, baseRef)
	}

	cmd := exec.CommandContext(ctx, "git", "-C", repoDir, "diff", "--name-only", baseRef+"...HEAD")
	out, err := cmd.Output()
	if err != nil {
		var exitErr *exec.ExitError
		if errors.As(err, &exitErr) {
			return nil, fmt.Errorf("impact: git diff failed (exit %d): %s", exitErr.ExitCode(), string(exitErr.Stderr))
		}
		return nil, fmt.Errorf("impact: git diff: %w", err)
	}

	raw := strings.TrimSpace(string(out))
	if raw == "" {
		return nil, nil
	}

	lines := strings.Split(raw, "\n")
	result := make([]string, 0, len(lines))
	for _, l := range lines {
		l = strings.TrimSpace(l)
		if l != "" {
			result = append(result, l)
		}
	}
	return result, nil
}

// MapToTests reads the JSON impact map at mapFile, matches each path in changed
// against the configured globs, and returns a de-duplicated, sorted list of
// test spec paths that must be exercised.
//
// Matching rules:
//   - Each changed file is tested against every key in "globs".
//   - Glob keys are split on "/"; each segment is matched with filepath.Match.
//   - "**" as an entire segment matches zero or more path segments.
//   - Changed files that match any "ignore" glob are silently excluded.
//   - If every changed file is ignored → returns (nil, nil); gate passes.
//   - If any changed file matches neither a glob nor an ignore entry →
//     returns (nil, ErrUnmappedChange).
//
// ponytail: "**" only works as a complete path segment wildcard.
// Mid-segment patterns like "src/**foo" are not supported and will not match.
func MapToTests(changed []string, mapFile string) ([]string, error) {
	data, err := os.ReadFile(mapFile)
	if err != nil {
		return nil, fmt.Errorf("impact: reading map file %q: %w", mapFile, err)
	}

	var imap impactMap
	if err := json.Unmarshal(data, &imap); err != nil {
		return nil, fmt.Errorf("impact: parsing map file %q: %w", mapFile, err)
	}

	specSet := make(map[string]struct{})
	hasUnmapped := false
	allIgnored := true

	for _, cf := range changed {
		// Check ignore patterns first.
		if matchesAnyGlob(cf, imap.Ignore) {
			continue // this file is explicitly ignored
		}
		allIgnored = false

		matched := false
		for pattern, specs := range imap.Globs {
			if matchGlobPath(cf, pattern) {
				matched = true
				for _, s := range specs {
					specSet[s] = struct{}{}
				}
			}
		}
		if !matched {
			hasUnmapped = true
		}
	}

	if allIgnored {
		// All files were in the ignore list — gate passes explicitly.
		return nil, nil
	}

	if hasUnmapped {
		return nil, fmt.Errorf("%w", ErrUnmappedChange)
	}

	specs := make([]string, 0, len(specSet))
	for s := range specSet {
		specs = append(specs, s)
	}
	sort.Strings(specs)
	return specs, nil
}

// matchesAnyGlob returns true if path matches any of the given glob patterns.
// It uses filepath.Match for single-segment globs and matchGlobPath for patterns
// containing directory separators.
func matchesAnyGlob(path string, patterns []string) bool {
	for _, p := range patterns {
		if matchGlobPath(path, p) {
			return true
		}
	}
	return false
}

// matchGlobPath matches a file path against a glob pattern that may contain
// "**" as a full-segment wildcard.
//
// Rules:
//   - Pattern and path are split on "/".
//   - A "**" segment in the pattern matches zero or more path segments.
//   - All other segments are matched with filepath.Match (supports *, ?, [...]).
//
// ponytail: "**" only works as a complete segment; mid-segment use is unsupported.
func matchGlobPath(path, pattern string) bool {
	pathParts := strings.Split(filepath.ToSlash(path), "/")
	patParts := strings.Split(filepath.ToSlash(pattern), "/")
	return matchParts(pathParts, patParts)
}

// matchParts is a recursive helper implementing "**" multi-segment expansion.
func matchParts(pathParts, patParts []string) bool {
	for len(patParts) > 0 {
		seg := patParts[0]
		if seg == "**" {
			patParts = patParts[1:]
			// "**" matches zero segments
			if matchParts(pathParts, patParts) {
				return true
			}
			// "**" matches one or more segments
			for i := 1; i <= len(pathParts); i++ {
				if matchParts(pathParts[i:], patParts) {
					return true
				}
			}
			return false
		}
		// Normal segment: must have a corresponding path segment.
		if len(pathParts) == 0 {
			return false
		}
		ok, err := filepath.Match(seg, pathParts[0])
		if err != nil || !ok {
			return false
		}
		pathParts = pathParts[1:]
		patParts = patParts[1:]
	}
	// Pattern is exhausted; path must also be exhausted.
	return len(pathParts) == 0
}
