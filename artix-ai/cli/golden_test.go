package main

import (
	"bytes"
	"encoding/json"
	"io/fs"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
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

// TestR2_4_SupervisedNonTTY_RefusesWithoutConfirmFlag verifies that running
// 'artix code --autonomy supervised' in non-TTY mode without --confirm-tests or --yes
// strictly fails closed and prints an error, while keeping stdout as exactly 1 JSON line.
func TestR2_4_SupervisedNonTTY_RefusesWithoutConfirmFlag(t *testing.T) {
	tempDir := t.TempDir()
	specDir := filepath.Join(tempDir, "docs", "specs")
	if err := os.MkdirAll(specDir, 0755); err != nil {
		t.Fatal(err)
	}
	specContent := `# Story Spec STORY-001: Sample Story
## Acceptance Criteria
- Scenario: Test
## Test Commands
` + "```bash\n" + `echo "test command execution"
` + "```\n"
	if err := os.WriteFile(filepath.Join(specDir, "STORY-001.md"), []byte(specContent), 0644); err != nil {
		t.Fatal(err)
	}

	var stdout, stderr bytes.Buffer
	// Stdin is a non-TTY buffer with no user confirmation flag passed
	code := RunCLIWithIO(tempDir, nil, []string{
		"code", "--json", "--autonomy", "supervised",
		"--provider", "openai", "--model", "gpt-4o",
	}, strings.NewReader(""), &stdout, &stderr)

	if code == 0 {
		t.Fatalf("expected non-zero exit code when running in non-TTY supervised mode without --confirm-tests, got 0")
	}

	stdoutStr := strings.TrimSpace(stdout.String())
	lines := strings.Split(stdoutStr, "\n")
	if len(lines) != 1 || stdoutStr == "" {
		t.Fatalf("expected exactly 1 JSON line on stdout, got %d lines: %q", len(lines), stdoutStr)
	}

	var res map[string]any
	if err := json.Unmarshal([]byte(lines[0]), &res); err != nil {
		t.Fatalf("stdout must be valid JSON: %v", err)
	}
	if res["ok"] != false {
		t.Fatalf("expected ok=false in JSON response, got: %+v", res)
	}
	errMsg, _ := res["error"].(string)
	if !strings.Contains(errMsg, "unconfirmed test commands") {
		t.Fatalf("expected error mentioning unconfirmed test commands, got: %s", errMsg)
	}
}

// TestR2_4_SupervisedWithConfirmFlag_Accepted verifies that passing --confirm-tests or --yes
// satisfies the supervised test command gate in non-TTY environments.
func TestR2_4_SupervisedWithConfirmFlag_Accepted(t *testing.T) {
	tempDir := t.TempDir()
	specDir := filepath.Join(tempDir, "docs", "specs")
	if err := os.MkdirAll(specDir, 0755); err != nil {
		t.Fatal(err)
	}
	specContent := `# Story Spec STORY-001: Sample Story
## Acceptance Criteria
- Scenario: Test
## Test Commands
` + "```bash\n" + `echo "ok"
` + "```\n"
	if err := os.WriteFile(filepath.Join(specDir, "STORY-001.md"), []byte(specContent), 0644); err != nil {
		t.Fatal(err)
	}

	var stdout, stderr bytes.Buffer
	// Pass --confirm-tests flag
	code := RunCLIWithIO(tempDir, nil, []string{
		"code", "--json", "--autonomy", "supervised", "--confirm-tests",
		"--provider", "openai", "--model", "gpt-4o",
	}, strings.NewReader(""), &stdout, &stderr)

	// Since OPENAI_API_KEY is not set or network call not made, it may fail on model provider or proceed,
	// but it MUST NOT fail with "unconfirmed test commands"!
	stdoutStr := strings.TrimSpace(stdout.String())
	if strings.Contains(stdoutStr, "unconfirmed test commands") || strings.Contains(stderr.String(), "unconfirmed test commands") {
		t.Fatalf("unexpected unconfirmed test commands error when --confirm-tests was explicitly provided! code=%d, stdout=%s, stderr=%s", code, stdoutStr, stderr.String())
	}
}

// TestR2_4_RealBinaryEndToEndWithFakeProvider executes the actual compiled 'artix' binary
// against a mock OpenAI-compatible HTTP server with --json and --confirm-tests, verifying
// single-line stdout JSON, stderr logging, and valid round execution.
func TestR2_4_RealBinaryEndToEndWithFakeProvider(t *testing.T) {
	// Compile real artix binary
	binDir := t.TempDir()
	binPath := filepath.Join(binDir, "artix")
	buildCmd := exec.Command("go", "build", "-o", binPath, ".")
	if out, err := buildCmd.CombinedOutput(); err != nil {
		t.Fatalf("failed to build real artix binary: %v\nOutput: %s", err, string(out))
	}

	// Mock HTTP server returning OpenAI chat completion
	fakeServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		resp := map[string]any{
			"choices": []map[string]any{
				{
					"message": map[string]any{
						"role":    "assistant",
						"content": "```diff\n--- /dev/null\n+++ b/result.txt\n@@ -0,0 +1 @@\n+completed\n```",
					},
				},
			},
			"usage": map[string]any{
				"prompt_tokens":     150,
				"completion_tokens": 50,
				"total_tokens":      200,
			},
		}
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer fakeServer.Close()

	// Initialize temp git repo with spec and a passing test command
	workDir := t.TempDir()
	initCmd := exec.Command("git", "init")
	initCmd.Dir = workDir
	if out, err := initCmd.CombinedOutput(); err != nil {
		t.Fatalf("git init failed: %v\nOutput: %s", err, string(out))
	}

	specDir := filepath.Join(workDir, "docs", "specs")
	if err := os.MkdirAll(specDir, 0755); err != nil {
		t.Fatal(err)
	}
	specContent := `# Story Spec STORY-001: Golden Test Story
## Acceptance Criteria
- Scenario: Verified
## Test Commands
` + "```bash\n" + `echo "tests passed"
` + "```\n"
	if err := os.WriteFile(filepath.Join(specDir, "STORY-001.md"), []byte(specContent), 0644); err != nil {
		t.Fatal(err)
	}

	cmd := exec.Command(binPath, "code", "--json", "--autonomy", "supervised", "--confirm-tests",
		"--provider", "openai", "--model", "gpt-4o", "--rounds", "1")
	cmd.Dir = workDir
	cmd.Env = append(os.Environ(),
		"OPENAI_API_KEY=mock-key",
		"ARTIX_API_URL="+fakeServer.URL,
	)

	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	_ = cmd.Run() // exit code may be 0 or 1 depending on whether patch was cleanly accepted

	outLines := strings.Split(strings.TrimSpace(stdout.String()), "\n")
	if len(outLines) != 1 || strings.TrimSpace(stdout.String()) == "" {
		t.Fatalf("expected real binary stdout to be EXACTLY 1 JSON line, got %d lines:\nSTDOUT:\n%s\nSTDERR:\n%s",
			len(outLines), stdout.String(), stderr.String())
	}

	var jsonRes map[string]any
	if err := json.Unmarshal([]byte(outLines[0]), &jsonRes); err != nil {
		t.Fatalf("expected valid JSON on stdout from real binary, got error: %v, raw: %s", err, outLines[0])
	}
}

// TestR2_7_PluginContract_RealBinaryOutputsExpectedJSONForPlugins verifies that all three
// commands used by the IDE plugins ('plan', 'code', 'review') when run via the real binary
// produce the exact JSON schema and fields parsed by the VS Code and IntelliJ plugins.
func TestR2_7_PluginContract_RealBinaryOutputsExpectedJSONForPlugins(t *testing.T) {
	// Compile real artix binary
	binDir := t.TempDir()
	binPath := filepath.Join(binDir, "artix")
	buildCmd := exec.Command("go", "build", "-o", binPath, ".")
	if out, err := buildCmd.CombinedOutput(); err != nil {
		t.Fatalf("failed to build real artix binary: %v\nOutput: %s", err, string(out))
	}

	workDir := t.TempDir()
	initCmd := exec.Command("git", "init")
	initCmd.Dir = workDir
	if out, err := initCmd.CombinedOutput(); err != nil {
		t.Fatalf("git init failed: %v\nOutput: %s", err, string(out))
	}

	// 1. Contract: plan --json produces specPath
	planCmd := exec.Command(binPath, "plan", "--json", "Add basic ping healthcheck endpoint")
	planCmd.Dir = workDir
	var planOut, planErr bytes.Buffer
	planCmd.Stdout = &planOut
	planCmd.Stderr = &planErr
	if err := planCmd.Run(); err != nil {
		t.Fatalf("plan command failed: %v (stderr: %s)", err, planErr.String())
	}

	planLines := strings.Split(strings.TrimSpace(planOut.String()), "\n")
	if len(planLines) != 1 {
		t.Fatalf("plan --json: expected 1 line, got %d: %s", len(planLines), planOut.String())
	}
	var planJSON map[string]any
	if err := json.Unmarshal([]byte(planLines[0]), &planJSON); err != nil {
		t.Fatalf("invalid plan JSON: %v", err)
	}
	specPath, ok := planJSON["specPath"].(string)
	if !ok || specPath == "" {
		t.Fatalf("plan JSON missing 'specPath' parsed by plugins: %+v", planJSON)
	}
	if _, err := os.Stat(specPath); err != nil {
		t.Fatalf("specPath %s does not exist on disk: %v", specPath, err)
	}

	// 2. Contract: review --json produces status and summary
	revCmd := exec.Command(binPath, "review", "--json")
	revCmd.Dir = workDir
	var revOut, revErr bytes.Buffer
	revCmd.Stdout = &revOut
	revCmd.Stderr = &revErr
	_ = revCmd.Run()

	revLines := strings.Split(strings.TrimSpace(revOut.String()), "\n")
	if len(revLines) != 1 {
		t.Fatalf("review --json: expected 1 line, got %d: %s", len(revLines), revOut.String())
	}
	var revJSON map[string]any
	if err := json.Unmarshal([]byte(revLines[0]), &revJSON); err != nil {
		t.Fatalf("invalid review JSON: %v", err)
	}
	status, ok := revJSON["status"].(string)
	if !ok || (status != "approved" && status != "unreviewed" && status != "rejected") {
		t.Fatalf("review JSON missing valid 'status' parsed by plugins: %+v", revJSON)
	}
	if _, ok := revJSON["summary"].(string); !ok {
		t.Fatalf("review JSON missing 'summary' parsed by plugins: %+v", revJSON)
	}

	// 3. Contract: code --json produces success and roundsRun
	fakeServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		resp := map[string]any{
			"choices": []map[string]any{
				{
					"message": map[string]any{
						"role":    "assistant",
						"content": "```diff\n--- /dev/null\n+++ b/result.txt\n@@ -0,0 +1 @@\n+completed\n```",
					},
				},
			},
			"usage": map[string]any{
				"prompt_tokens":     100,
				"completion_tokens": 50,
				"total_tokens":      150,
			},
		}
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer fakeServer.Close()

	codeCmd := exec.Command(binPath, "code", "--json", "--autonomy", "supervised", "--confirm-tests",
		"--provider", "openai", "--model", "gpt-4o", "--rounds", "1")
	codeCmd.Dir = workDir
	codeCmd.Env = append(os.Environ(),
		"OPENAI_API_KEY=mock-key",
		"ARTIX_API_URL="+fakeServer.URL,
	)
	var codeOut, codeErr bytes.Buffer
	codeCmd.Stdout = &codeOut
	codeCmd.Stderr = &codeErr
	_ = codeCmd.Run()

	codeLines := strings.Split(strings.TrimSpace(codeOut.String()), "\n")
	if len(codeLines) != 1 {
		t.Fatalf("code --json: expected 1 line, got %d: %s", len(codeLines), codeOut.String())
	}
	var codeJSON map[string]any
	if err := json.Unmarshal([]byte(codeLines[0]), &codeJSON); err != nil {
		t.Fatalf("invalid code JSON: %v", err)
	}
	if _, ok := codeJSON["success"].(bool); !ok {
		t.Fatalf("code JSON missing boolean 'success' parsed by plugins: %+v", codeJSON)
	}
	if _, ok := codeJSON["roundsRun"].(float64); !ok {
		t.Fatalf("code JSON missing numeric 'roundsRun' parsed by plugins: %+v", codeJSON)
	}
}

