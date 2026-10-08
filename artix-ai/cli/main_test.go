package main

import (
	"bytes"
	"strings"
	"testing"
)

func TestRunCLI_VersionAndHelp(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Version command
	code := RunCLI("", nil, []string{"version"}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for version, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Artix AI version") {
		t.Errorf("expected version output, got: %s", stdout.String())
	}

	// 2. Help command
	stdout.Reset()
	code = RunCLI("", nil, []string{"help"}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for help, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Usage:") {
		t.Errorf("expected usage output, got: %s", stdout.String())
	}

	// 3. Empty args prints usage
	stdout.Reset()
	code = RunCLI("", nil, []string{}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for empty args, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Usage:") {
		t.Errorf("expected usage output, got: %s", stdout.String())
	}
}

func TestRunCLI_PersonaAndUnknown(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Persona list
	code := RunCLI("", nil, []string{"persona"}, &stdout, &stderr)
	if code != 0 {
		t.Errorf("expected exit code 0 for persona, got %d", code)
	}
	if !strings.Contains(stdout.String(), "Available SWE Personas") {
		t.Errorf("expected persona listing, got: %s", stdout.String())
	}

	// 2. Unknown command -> exit 1
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"unknown_foo_bar"}, &stdout, &stderr)
	if code != 1 {
		t.Errorf("expected exit code 1 for unknown command, got %d", code)
	}
	if !strings.Contains(stderr.String(), "Unknown command") {
		t.Errorf("expected unknown command error on stderr, got: %s", stderr.String())
	}
}

func TestR13_5_VerifyApprovalCommand(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Missing flags -> error mentioning required flags
	code := RunCLI("", nil, []string{"verify-approval"}, &stdout, &stderr)
	if code != 1 {
		t.Errorf("expected exit code 1 for verify-approval without arguments, got %d", code)
	}
	if !strings.Contains(stderr.String(), "--pr") || !strings.Contains(stderr.String(), "--sha") || !strings.Contains(stderr.String(), "--spec") {
		t.Errorf("expected stderr mentioning required flags, got: %s", stderr.String())
	}

	// 2. Flags accepted
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"verify-approval", "--pr", "1", "--sha", "abcdef123456", "--spec", "S-01", "--verdict-hash", "hash1234"}, &stdout, &stderr)
	if strings.Contains(stderr.String(), "flag provided but not defined") {
		t.Fatalf("runVerifyApproval failed to recognize flags: %s", stderr.String())
	}
}

func TestR13_5_MergeCommand_Deprecated(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// Plain CLI invocation of 'artix merge' must error as deprecated
	code := RunCLI("", nil, []string{"merge"}, &stdout, &stderr)
	if code == 0 {
		t.Errorf("expected non-zero exit code for deprecated 'artix merge'")
	}
	if !strings.Contains(stderr.String(), "deprecated") || !strings.Contains(stderr.String(), "verify-approval") {
		t.Errorf("expected stderr to state 'artix merge' is deprecated and suggest 'artix verify-approval', got: %s", stderr.String())
	}

	// JSON invocation of 'artix merge' must return JSON error noting deprecation
	stdout.Reset()
	stderr.Reset()
	code = RunCLI("", nil, []string{"merge", "--json"}, &stdout, &stderr)
	if code == 0 {
		t.Errorf("expected non-zero exit code for deprecated 'artix merge --json'")
	}
	if !strings.Contains(stdout.String(), "deprecated") && !strings.Contains(stderr.String(), "deprecated") {
		t.Errorf("expected JSON/stderr output to mention deprecation, got stdout=%s stderr=%s", stdout.String(), stderr.String())
	}
}


