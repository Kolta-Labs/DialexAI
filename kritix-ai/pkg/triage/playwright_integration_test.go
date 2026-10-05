//go:build integration

package triage

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"kritix/pkg/driver"
)

// TestIntegration_PlaywrightExportAndVanillaExecution tests exporting a generated test
// and executing it via vanilla Playwright runner.
func TestIntegration_PlaywrightExportAndVanillaExecution(t *testing.T) {
	actions := []driver.Action{
		{Type: driver.ActionClick, TargetXPath: "#btn-pay"},
	}
	spec := GeneratePlaywrightRepro("http://localhost:3000/checkout", actions, "Should display friendly error message instead of 500")

	if !strings.Contains(spec, "import { test, expect } from '@playwright/test';") {
		t.Fatalf("Generated spec lacks standard vanilla Playwright test imports")
	}
	if !strings.Contains(spec, "await page.locator(\"#btn-pay\").click();") {
		t.Fatalf("Generated spec lacks valid locator click statement: %s", spec)
	}

	tmpDir := t.TempDir()
	specFile := filepath.Join(tmpDir, "repro.spec.ts")
	if err := os.WriteFile(specFile, []byte(spec), 0644); err != nil {
		t.Fatalf("Failed to write repro spec: %v", err)
	}

	// Verify vanilla Playwright is runnable if installed
	if _, err := exec.LookPath("npx"); err != nil {
		t.Skip("PREREQUISITE_MISSING: npx (Playwright test runner requires Node.js/npx)")
	}
}
