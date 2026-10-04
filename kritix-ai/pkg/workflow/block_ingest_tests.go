package workflow

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// IngestTestsBlock ingests existing Playwright/Cypress/BDD test files from disk.
type IngestTestsBlock struct{}

func (b *IngestTestsBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "ingest.tests",
		Name:        "Ingest Existing Test Repository",
		Category:    "ingest",
		Description: "Ingests existing Playwright, Cypress, and Cucumber test specifications from repository.",
	}
}

func (b *IngestTestsBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	testDirVal, ok := bCtx.Get("test_dir")
	if !ok || testDirVal == nil {
		return &BlockResult{
			BlockID: "ingest.tests",
			Status:  StatusFailed,
			Message: "Missing input: 'test_dir' required in execution context",
			Error:   errors.New("missing test_dir"),
		}, errors.New("missing test_dir")
	}
	testDir := fmt.Sprint(testDirVal)

	repoDir := "."
	if dirVal, ok := bCtx.Get(VarRepoDir); ok && dirVal != nil && fmt.Sprint(dirVal) != "" {
		repoDir = fmt.Sprint(dirVal)
	}

	fullPath := testDir
	if !filepath.IsAbs(testDir) {
		fullPath = filepath.Join(repoDir, testDir)
	}
	entries, err := os.ReadDir(fullPath)
	specCount := 0
	if err == nil {
		for _, e := range entries {
			if !e.IsDir() {
				specCount++
			}
		}
	}

	if specCount == 0 {
		return &BlockResult{
			BlockID: "ingest.tests",
			Status:  StatusFailed,
			Message: fmt.Sprintf("No test files found in %s", fullPath),
			Error:   errors.New("no test files found"),
		}, errors.New("no test files found")
	}

	bCtx.Set("ingested_spec_count", specCount)

	return &BlockResult{
		BlockID: "ingest.tests",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Ingested %d test specs from %s", specCount, fullPath),
		Data: map[string]interface{}{
			"test_dir":   fullPath,
			"spec_count": specCount,
		},
	}, nil
}
