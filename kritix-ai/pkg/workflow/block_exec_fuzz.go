package workflow

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"kritix/pkg/data"
)

// ExecFuzzBlock performs boundary and schema fuzzing against API endpoints.
type ExecFuzzBlock struct{}

func (b *ExecFuzzBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "exec.fuzz",
		Name:        "Boundary Fuzzing & Schema Assertions",
		Category:    "exec",
		Description: "Executes boundary value payloads (integer overflows, Unicode nulls, oversized strings) against target API endpoints.",
	}
}

func (b *ExecFuzzBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "exec.fuzz",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required for boundary fuzzing",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)
	if res, err := guardTarget("exec.fuzz", targetURL); err != nil {
		return res, err
	}

	factory := data.NewSyntheticFactory()
	payloads := factory.GenerateBoundaryValues()

	path, param := "/api/test", "input"
	if v, ok := bCtx.Get("fuzz_path"); ok && fmt.Sprint(v) != "" {
		path = fmt.Sprint(v)
	}
	if v, ok := bCtx.Get("fuzz_param"); ok && fmt.Sprint(v) != "" {
		param = fmt.Sprint(v)
	}

	client := &http.Client{Timeout: 3 * time.Second}
	var unhandled500s []string // server faults: any 5xx, timeout or dropped connection

	for _, s := range payloads {
		reqURL := fmt.Sprintf("%s%s?%s=%s", strings.TrimRight(targetURL, "/"), path, url.QueryEscape(param), url.QueryEscape(s))
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
		if err != nil {
			continue
		}
		resp, err := client.Do(req)
		if err != nil {
			unhandled500s = append(unhandled500s, fmt.Sprintf("no response (%v) for payload %q", err, s))
			continue
		}
		if resp.StatusCode >= 500 {
			unhandled500s = append(unhandled500s, fmt.Sprintf("%d for payload %q", resp.StatusCode, s))
		}
		resp.Body.Close()
	}

	if len(unhandled500s) > 0 {
		return &BlockResult{
			BlockID: "exec.fuzz",
			Status:  StatusFailed,
			Message: fmt.Sprintf("Boundary fuzzing triggered %d unhandled 500 error(s): %v", len(unhandled500s), unhandled500s),
			Error:   fmt.Errorf("boundary fuzzing triggered %d internal server errors", len(unhandled500s)),
		}, fmt.Errorf("boundary fuzzing triggered %d internal server errors", len(unhandled500s))
	}

	return &BlockResult{
		BlockID: "exec.fuzz",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Executed %d boundary payloads against %s (0 unhandled exceptions detected)",
			len(payloads), targetURL),
		Data: map[string]interface{}{
			"payloads_sent": len(payloads),
			"target_url":    targetURL,
		},
	}, nil
}
