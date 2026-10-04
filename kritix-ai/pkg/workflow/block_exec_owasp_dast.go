package workflow

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"kritix/pkg/driver"
	"kritix/pkg/security"
)

// ExecOWASPDASTBlock executes automated OWASP Top 10 security penetration scanning.
type ExecOWASPDASTBlock struct{}

func (b *ExecOWASPDASTBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "exec.owasp-dast",
		Name:        "OWASP Top 10 Security Audit",
		Category:    "security",
		Description: "Executes automated DAST security scanning (SQLi, XSS, Path Traversal, and PII/Secret Leak Audit).",
	}
}

func (b *ExecOWASPDASTBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "exec.owasp-dast",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required for OWASP DAST scanning",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)
	if res, err := guardTarget("exec.owasp-dast", targetURL); err != nil {
		return res, err
	}

	payloadMap := security.GenerateOWASPFuzzPayloads()
	client := &http.Client{Timeout: 5 * time.Second}

	var securityFindings []string
	var scannedCount int
	var successfulProbes int

	for vulnType, payloads := range payloadMap {
		for _, p := range payloads {
			scannedCount++
			reqURL := targetURL
			if strings.Contains(reqURL, "?") {
				reqURL += "&q=" + url.QueryEscape(p)
			} else {
				reqURL += "?q=" + url.QueryEscape(p)
			}

			req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
			if err != nil {
				continue
			}

			resp, err := client.Do(req)
			if err != nil {
				continue
			}
			successfulProbes++
			bodyBytes, _ := io.ReadAll(resp.Body)
			resp.Body.Close()

			netEv := driver.NetworkEvent{
				URL:        reqURL,
				Method:     "GET",
				StatusCode: resp.StatusCode,
			}
			vulns := security.AuditNetworkEvent(netEv, string(bodyBytes))
			for _, v := range vulns {
				securityFindings = append(securityFindings, fmt.Sprintf("[%s/%s] %s on %s", vulnType, v.Severity, v.Evidence, v.URL))
			}
		}
	}

	bCtx.Set("dast_findings", securityFindings)

	if successfulProbes == 0 {
		return &BlockResult{
			BlockID: "exec.owasp-dast",
			Status:  StatusFailed,
			Message: fmt.Sprintf("OWASP DAST scan failed: target %s was unreachable for all %d probe requests", targetURL, scannedCount),
			Error:   fmt.Errorf("target %s unreachable", targetURL),
		}, fmt.Errorf("target %s unreachable", targetURL)
	}

	if len(securityFindings) > 0 {
		return &BlockResult{
			BlockID: "exec.owasp-dast",
			Status:  StatusFailed,
			Message: fmt.Sprintf("OWASP DAST detected %d security issue(s): %v", len(securityFindings), securityFindings),
			Error:   fmt.Errorf("detected %d security vulnerabilities", len(securityFindings)),
		}, fmt.Errorf("detected %d security vulnerabilities", len(securityFindings))
	}

	return &BlockResult{
		BlockID: "exec.owasp-dast",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Completed OWASP Top 10 DAST scan against %s (%d payload checks executed, 0 vulnerabilities detected)",
			targetURL, successfulProbes),
		Data: map[string]interface{}{
			"target_url":      targetURL,
			"payloads_tested": scannedCount,
			"vulnerabilities": 0,
		},
	}, nil
}
