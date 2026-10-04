package triage

import (
	"fmt"
	"regexp"
	"strings"
)

// BlameInfo records git provenance for a buggy line of code.
type BlameInfo struct {
	CommitHash    string `json:"commit_hash"`
	AuthorName    string `json:"author_name"`
	AuthorEmail   string `json:"author_email"`
	CommitDate    string `json:"commit_date"`
	CommitMessage string `json:"commit_message"`
	FilePath      string `json:"file_path"`
	LineNumber    int    `json:"line_number"`
}

// LocateFailureFrame parses console stack traces to extract the topmost app source file and line.
func LocateFailureFrame(stackTrace string) (string, int, bool) {
	// Pattern 1: Go / Python: path/file.go:123 or file.py", line 123
	reGo := regexp.MustCompile(`([a-zA-Z0-9_\-./]+\.(?:go|ts|js|kt|py)):(\d+)`)
	if match := reGo.FindStringSubmatch(stackTrace); len(match) == 3 {
		file := match[1]
		var line int
		fmt.Sscanf(match[2], "%d", &line)
		return file, line, true
	}

	// Pattern 2: Java / Kotlin: (OrderService.kt:142)
	reJVM := regexp.MustCompile(`\(([a-zA-Z0-9_]+\.(?:kt|java)):(\d+)\)`)
	if match := reJVM.FindStringSubmatch(stackTrace); len(match) == 3 {
		file := match[1]
		var line int
		fmt.Sscanf(match[2], "%d", &line)
		return file, line, true
	}

	return "", 0, false
}

// FormatRCAHint formats blame information into a human-readable root cause explanation.
func FormatRCAHint(info BlameInfo) string {
	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("Probable Root Cause Location: %s:%d\n", info.FilePath, info.LineNumber))
	if info.CommitHash != "" {
		sb.WriteString(fmt.Sprintf("Introduced in Commit: %s by %s <%s>\n", info.CommitHash, info.AuthorName, info.AuthorEmail))
		sb.WriteString(fmt.Sprintf("Commit Message: %q (Date: %s)\n", info.CommitMessage, info.CommitDate))
	}
	return sb.String()
}
