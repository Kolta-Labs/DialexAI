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

func TestRunCLI_MergeCommandValidation(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// 1. Missing flags -> error
	code := RunCLI("", nil, []string{"merge"}, &stdout, &stderr)
	if code != 1 {
		t.Errorf("expected exit code 1 for merge without arguments, got %d", code)
	}
	if !strings.Contains(stderr.String(), "--pr") || !strings.Contains(stderr.String(), "--sha") {
		t.Errorf("expected stderr mentioning required flags, got: %s", stderr.String())
	}
}

func TestRunCLI_MergeCommand_AcceptsVerdictHashFlag(t *testing.T) {
	var stdout, stderr bytes.Buffer

	// When --verdict-hash is supplied, verify it parses and does not fail with "flag provided but not defined"
	code := RunCLI("", nil, []string{"merge", "--pr", "1", "--sha", "abcdef123456", "--verdict-hash", "hash1234"}, &stdout, &stderr)
	// It will fail because forge/network/audit is not configured, but NOT with unknown flag
	if strings.Contains(stderr.String(), "flag provided but not defined: -verdict-hash") {
		t.Fatalf("runMerge failed to recognize --verdict-hash flag: %s", stderr.String())
	}
	_ = code
}


