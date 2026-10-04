package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
)

// IngestOpenAPIBlock parses OpenAPI / Swagger v3 specifications from schema files.
type IngestOpenAPIBlock struct{}

func (b *IngestOpenAPIBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "ingest.openapi",
		Name:        "Ingest OpenAPI Specification",
		Category:    "ingest",
		Description: "Parses OpenAPI v3 specification schemas and extracts endpoint routes and schemas.",
	}
}

func (b *IngestOpenAPIBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	specPathVal, ok := bCtx.Get("openapi_path")
	specContentVal, okContent := bCtx.Get("openapi_spec")

	var specBytes []byte
	if ok && specPathVal != nil && fmt.Sprint(specPathVal) != "" {
		path := fmt.Sprint(specPathVal)
		var err error
		specBytes, err = os.ReadFile(path)
		if err != nil {
			return &BlockResult{
				BlockID: "ingest.openapi",
				Status:  StatusFailed,
				Message: fmt.Sprintf("Failed to read OpenAPI spec at %s: %v", path, err),
				Error:   err,
			}, err
		}
	} else if okContent && specContentVal != nil {
		specBytes = []byte(fmt.Sprint(specContentVal))
	} else {
		return &BlockResult{
			BlockID: "ingest.openapi",
			Status:  StatusFailed,
			Message: "Missing input: 'openapi_path' or 'openapi_spec' required in context",
			Error:   errors.New("missing openapi specification input"),
		}, errors.New("missing openapi specification input")
	}

	var parsedSpec struct {
		OpenAPI string                            `json:"openapi"`
		Info    map[string]interface{}            `json:"info"`
		Paths   map[string]map[string]interface{} `json:"paths"`
	}

	if err := json.Unmarshal(specBytes, &parsedSpec); err != nil {
		return &BlockResult{
			BlockID: "ingest.openapi",
			Status:  StatusFailed,
			Message: fmt.Sprintf("Failed to parse OpenAPI JSON schema: %v", err),
			Error:   err,
		}, err
	}

	bCtx.Set("parsed_openapi", parsedSpec)
	bCtx.Set("num_api_paths", len(parsedSpec.Paths))

	return &BlockResult{
		BlockID: "ingest.openapi",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Parsed OpenAPI spec: %d endpoint routes extracted", len(parsedSpec.Paths)),
		Data: map[string]interface{}{
			"endpoints": len(parsedSpec.Paths),
		},
	}, nil
}
