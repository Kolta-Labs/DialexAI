package security

import (
	"testing"

	"kritix/pkg/driver"
)

func TestAuditNetworkEventForSecretsAndPII(t *testing.T) {
	// 1. JWT in URL test
	eventWithJWT := driver.NetworkEvent{
		URL: "https://api.example.com/v1/user?token=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozG4BvjN_example_sig",
	}
	vulns := AuditNetworkEvent(eventWithJWT, "{}")
	if len(vulns) != 1 || vulns[0].Type != VulnSecretLeak {
		t.Fatalf("expected JWT URL leak detection, got %v", vulns)
	}

	// 2. AWS Key in response body test
	eventAWS := driver.NetworkEvent{URL: "https://api.example.com/config"}
	bodyAWS := `{"status": "ok", "aws_access_key": "AKIAIOSFODNN7EXAMPLE"}`
	vulnsAWS := AuditNetworkEvent(eventAWS, bodyAWS)
	if len(vulnsAWS) != 1 || vulnsAWS[0].ID != "KRITIX-SEC-AWS-KEY" {
		t.Fatalf("expected AWS key detection, got %v", vulnsAWS)
	}

	// 3. Raw stack trace test
	eventError := driver.NetworkEvent{URL: "https://api.example.com/orders/999", StatusCode: 500}
	bodyError := `java.lang.NullPointerException at com.example.service.OrderService.process(OrderService.java:142)`
	vulnsError := AuditNetworkEvent(eventError, bodyError)
	if len(vulnsError) != 1 || vulnsError[0].Type != VulnVerboseErrorLeak {
		t.Fatalf("expected stack trace leak detection, got %v", vulnsError)
	}
}

func TestGenerateOWASPFuzzPayloads(t *testing.T) {
	payloads := GenerateOWASPFuzzPayloads()
	if len(payloads[VulnSQLInjection]) == 0 {
		t.Errorf("missing SQLi payloads")
	}
	if len(payloads[VulnXSS]) == 0 {
		t.Errorf("missing XSS payloads")
	}
	if len(payloads[VulnPathTraversal]) == 0 {
		t.Errorf("missing Path Traversal payloads")
	}
}
