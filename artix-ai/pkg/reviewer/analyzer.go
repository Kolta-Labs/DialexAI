package reviewer

import (
	"context"
	"fmt"
	"strings"
	"time"

	"artix/pkg/sandbox"
)

// AnalyzerFinding represents a diagnostic issue found by static analysis tools (Konsist, Detekt, Semgrep, go vet).
type AnalyzerFinding struct {
	Tool     string `json:"tool"`
	Rule     string `json:"rule,omitempty"`
	File     string `json:"file,omitempty"`
	Line     int    `json:"line,omitempty"`
	Severity string `json:"severity"` // "ERROR", "WARNING", "INFO"
	Message  string `json:"message"`
}

// RunAnalyzers executes configured static analysis commands within the sandbox.
func RunAnalyzers(ctx context.Context, rootDir string, box *sandbox.Sandbox, commands []string) ([]AnalyzerFinding, []*sandbox.ExecResult) {
	var findings []AnalyzerFinding
	var results []*sandbox.ExecResult

	if box == nil {
		box = sandbox.NewSandbox(rootDir)
	}

	for _, cmdStr := range commands {
		cmdStr = strings.TrimSpace(cmdStr)
		if cmdStr == "" {
			continue
		}
		opts := &sandbox.ExecOptions{
			Cwd:     rootDir,
			Timeout: 2 * time.Minute,
		}
		res := box.Run(ctx, cmdStr, opts)
		results = append(results, res)

		if !res.Success() {
			toolName := extractToolName(cmdStr)
			out := res.Stderr
			if out == "" {
				out = res.Stdout
			}
			finding := AnalyzerFinding{
				Tool:     toolName,
				Severity: "ERROR",
				Message:  fmt.Sprintf("%s failed with exit code %d:\n%s", cmdStr, res.ExitCode, out),
			}
			findings = append(findings, finding)
		}
	}

	return findings, results
}

func extractToolName(cmd string) string {
	fields := strings.Fields(cmd)
	if len(fields) > 0 {
		return fields[0]
	}
	return "analyzer"
}
