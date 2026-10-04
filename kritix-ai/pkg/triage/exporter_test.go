package triage

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestExportJUnitXML(t *testing.T) {
	results := []TestCaseResult{
		{
			Name:      "test_checkout_flow",
			SuiteName: "E2E_Shop",
			Duration:  250 * time.Millisecond,
			Passed:    true,
		},
		{
			Name:      "test_login_invalid_password",
			SuiteName: "Auth_Suite",
			Duration:  120 * time.Millisecond,
			Passed:    false,
			ErrorMsg:  "Expected 401 Unauthorized, got 500",
			ErrorType: "AssertionError",
			Details:   "login.spec.ts:42",
		},
		{
			Name:      "test_flaky_payment",
			SuiteName: "Quarantined_Suite",
			Duration:  10 * time.Millisecond,
			Skipped:   true,
			ErrorMsg:  "Quarantined due to flake",
		},
	}

	xmlBytes, err := ExportJUnitXML("Kritix-CI-Run", results)
	if err != nil {
		t.Fatalf("failed to export JUnit XML: %v", err)
	}

	xmlStr := string(xmlBytes)
	if !strings.Contains(xmlStr, `<?xml version="1.0" encoding="UTF-8"?>`) {
		t.Errorf("missing XML header")
	}
	if !strings.Contains(xmlStr, `tests="3"`) || !strings.Contains(xmlStr, `failures="1"`) || !strings.Contains(xmlStr, `skipped="1"`) {
		t.Errorf("incorrect summary attributes in JUnit XML: %s", xmlStr)
	}
	if !strings.Contains(xmlStr, `test_login_invalid_password`) {
		t.Errorf("missing failing test case in XML output")
	}
}

func TestExportSARIF(t *testing.T) {
	findings := []SecurityFindingInput{
		{
			RuleID:      "KRITIX-DAST-001",
			Title:       "SQL Injection Vulnerability",
			Severity:    "critical",
			Description: "Payload ' OR '1'='1 caused database syntax error",
			TargetURL:   "https://api.example.com/v1/search?q=",
		},
		{
			RuleID:      "KRITIX-DAST-002",
			Title:       "PII Leak in HTTP Response",
			Severity:    "major",
			Description: "Unmasked SSN pattern detected in body",
			TargetURL:   "https://api.example.com/v1/profile",
		},
	}

	sarifBytes, err := ExportSARIF(findings)
	if err != nil {
		t.Fatalf("failed to export SARIF: %v", err)
	}

	var report SARIFReport
	if err := json.Unmarshal(sarifBytes, &report); err != nil {
		t.Fatalf("invalid SARIF JSON: %v", err)
	}

	if report.Version != "2.1.0" {
		t.Errorf("expected SARIF version 2.1.0, got %s", report.Version)
	}
	if len(report.Runs[0].Results) != 2 {
		t.Errorf("expected 2 SARIF results, got %d", len(report.Runs[0].Results))
	}
	if report.Runs[0].Results[0].Level != "error" {
		t.Errorf("expected critical finding to map to SARIF level 'error', got %s", report.Runs[0].Results[0].Level)
	}
}
