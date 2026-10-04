package workflow

import (
	"context"
	"errors"
	"fmt"
)

// SecurityIsolationBlock verifies staging infrastructure and database cluster isolation.
type SecurityIsolationBlock struct{}

func (b *SecurityIsolationBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "security.verify-isolation",
		Name:        "Verify Staging Infrastructure Isolation",
		Category:    "security",
		Description: "Enforces SOC 2 Type II environment isolation by verifying target does not share production databases or clusters.",
	}
}

func (b *SecurityIsolationBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	urlVal, ok := bCtx.Get("target_url")
	if !ok || urlVal == nil || fmt.Sprint(urlVal) == "" {
		return &BlockResult{
			BlockID: "security.verify-isolation",
			Status:  StatusFailed,
			Message: "Missing input: 'target_url' is required for isolation verification",
			Error:   errors.New("missing target_url"),
		}, errors.New("missing target_url")
	}
	targetURL := fmt.Sprint(urlVal)

	sharesProd := false
	if spVal, ok := bCtx.Get("shares_prod_infra"); ok && spVal != nil {
		if sp, ok := spVal.(bool); ok {
			sharesProd = sp
		}
	}

	if err := VerifyEnvironmentIsolation(targetURL, sharesProd); err != nil {
		return &BlockResult{
			BlockID: "security.verify-isolation",
			Status:  StatusFailed,
			Message: fmt.Sprintf("Isolation check failed: %v", err),
			Error:   err,
		}, err
	}

	return &BlockResult{
		BlockID: "security.verify-isolation",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Verified environment isolation for %s (Zero production blast radius)", targetURL),
		Data: map[string]interface{}{
			"target_url": targetURL,
			"isolated":   true,
		},
	}, nil
}
