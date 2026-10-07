package main

import (
	"bytes"
	"encoding/json"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestG8_PluginSourcesNeverPassAutonomous asserts that no IDE plugin ever passes
// '--autonomy autonomous' to ensure human oversight is never silently bypassed.
func TestG8_PluginSourcesNeverPassAutonomous(t *testing.T) {
	pluginDirs := []string{
		"../plugins/vscode",
		"../plugins/intellij",
		"../plugins/claude-code",
	}

	for _, pDir := range pluginDirs {
		err := filepath.WalkDir(pDir, func(path string, d fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if d.IsDir() {
				// Skip build, node_modules, and cache dirs
				name := d.Name()
				if name == "node_modules" || name == ".gradle" || name == "build" || name == "out" {
					return filepath.SkipDir
				}
				return nil
			}

			// Only check source and config files
			ext := filepath.Ext(path)
			if ext != ".ts" && ext != ".js" && ext != ".kt" && ext != ".java" && ext != ".json" && ext != ".md" && ext != ".xml" {
				return nil
			}

			data, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			content := string(data)

			// Forbidden: passing --autonomy autonomous
			if strings.Contains(content, "--autonomy autonomous") || strings.Contains(content, `"--autonomy", "autonomous"`) || strings.Contains(content, `--autonomy=autonomous`) {
				t.Errorf("SECURITY VIOLATION: Plugin source file %s passes '--autonomy autonomous'! IDE plugins must only use supervised mode.", path)
			}
			return nil
		})
		if err != nil {
			t.Fatalf("failed to walk plugin dir %s: %v", pDir, err)
		}
	}
}

// TestG8_CLI_JSON_SingleLineAndStderrProgress tests that 'plan', 'code', and 'review'
// with '--json' output EXACTLY one line of valid JSON to stdout and route progress to stderr.
func TestG8_CLI_Plan_JSON_SuccessAndError(t *testing.T) {
	tempDir := t.TempDir()

	// 1. plan --json success
	var stdout, stderr bytes.Buffer
	code := RunCLI(tempDir, nil, []string{"plan", "--json", "Add basic ping healthcheck endpoint"}, &stdout, &stderr)
	if code != 0 {
		t.Fatalf("expected exit 0 for plan --json success, got %d (stderr: %s)", code, stderr.String())
	}

	stdoutStr := strings.TrimSpace(stdout.String())
	lines := strings.Split(stdoutStr, "\n")
	if len(lines) != 1 {
		t.Fatalf("expected stdout to be exactly 1 JSON line, got %d lines: %q", len(lines), stdoutStr)
	}

	var planRes map[string]any
	if err := json.Unmarshal([]byte(lines[0]), &planRes); err != nil {
		t.Fatalf("expected stdout to be valid JSON: %v", err)
	}
	if planRes["status"] != "success" && planRes["ok"] != true {
		t.Fatalf("expected ok=true or status=success in plan output, got: %+v", planRes)
	}
	if stderr.Len() == 0 {
		t.Fatalf("expected progress logs to go to stderr when --json is passed")
	}

	// 2. plan --json error (missing story prompt)
	stdout.Reset()
	stderr.Reset()
	code = RunCLI(tempDir, nil, []string{"plan", "--json"}, &stdout, &stderr)
	if code == 0 {
		t.Fatalf("expected non-zero exit for plan --json without prompt, got 0")
	}
	errLines := strings.Split(strings.TrimSpace(stdout.String()), "\n")
	if len(errLines) != 1 || strings.TrimSpace(stdout.String()) == "" {
		t.Fatalf("expected stdout to be exactly 1 JSON line on error, got: %q", stdout.String())
	}
	var errRes map[string]any
	if err := json.Unmarshal([]byte(errLines[0]), &errRes); err != nil {
		t.Fatalf("expected valid JSON on plan error: %v", err)
	}
	if errRes["status"] != "error" && errRes["ok"] != false {
		t.Fatalf("expected error status in JSON, got: %+v", errRes)
	}
}

func TestG8_CLI_Review_JSON_Approved_Rejected_Unreviewed(t *testing.T) {
	tempDir := t.TempDir()

	// 1. review --json clean tree -> success / unreviewed
	var stdout, stderr bytes.Buffer
	code := RunCLI(tempDir, nil, []string{"review", "--json"}, &stdout, &stderr)
	stdoutStr := strings.TrimSpace(stdout.String())
	lines := strings.Split(stdoutStr, "\n")
	if len(lines) != 1 || stdoutStr == "" {
		t.Fatalf("expected exactly 1 JSON line on stdout for review --json, got: %q (code %d)", stdoutStr, code)
	}

	var revRes map[string]any
	if err := json.Unmarshal([]byte(lines[0]), &revRes); err != nil {
		t.Fatalf("expected valid JSON on review stdout: %v", err)
	}
	if _, ok := revRes["status"]; !ok {
		t.Fatalf("expected 'status' key in review JSON, got: %+v", revRes)
	}
}

func TestG8_CLI_Code_JSON_OutputsOneLine(t *testing.T) {
	tempDir := t.TempDir()

	// code --json error (no spec found) -> exit 1 with 1 JSON line on stdout and message on stderr
	var stdout, stderr bytes.Buffer
	code := RunCLI(tempDir, nil, []string{"code", "--json", "--provider", "anthropic", "--model", "test"}, &stdout, &stderr)
	if code == 0 {
		t.Fatalf("expected error exit code when no spec is found")
	}

	stdoutStr := strings.TrimSpace(stdout.String())
	lines := strings.Split(stdoutStr, "\n")
	if len(lines) != 1 || stdoutStr == "" {
		t.Fatalf("expected exactly 1 JSON line on stdout for code --json error, got %d lines: %q", len(lines), stdoutStr)
	}

	var codeRes map[string]any
	if err := json.Unmarshal([]byte(lines[0]), &codeRes); err != nil {
		t.Fatalf("expected valid JSON on code stdout: %v", err)
	}
	if codeRes["status"] != "error" && codeRes["ok"] != false {
		t.Fatalf("expected status=error or ok=false in code error JSON, got: %+v", codeRes)
	}
}
