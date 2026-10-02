package persona

import (
	"encoding/json"
	"errors"
	"strings"

	"socratix/pkg/model"
	"gopkg.in/yaml.v3"
)

// ImportDNA parses a raw byte slice (JSON or YAML) into a validated PersonaDNA struct.
func ImportDNA(raw []byte, formatHint string) (*model.PersonaDNA, error) {
	if len(raw) == 0 {
		return nil, errors.New("empty payload")
	}

	var dna model.PersonaDNA

	isYAML := strings.EqualFold(formatHint, "yaml") || strings.EqualFold(formatHint, "yml")
	if !isYAML {
		// Heuristically detect YAML if it starts with YAML markers or lacks JSON {
		trimmed := strings.TrimSpace(string(raw))
		if strings.HasPrefix(trimmed, "---") || (!strings.HasPrefix(trimmed, "{") && strings.Contains(trimmed, ":")) {
			isYAML = true
		}
	}

	if isYAML {
		if err := yaml.Unmarshal(raw, &dna); err != nil {
			return nil, err
		}
	} else {
		if err := json.Unmarshal(raw, &dna); err != nil {
			// Fallback: try yaml parser which can also parse json
			if err2 := yaml.Unmarshal(raw, &dna); err2 != nil {
				return nil, err
			}
		}
	}

	if err := ValidateDNA(&dna); err != nil {
		return nil, err
	}

	return &dna, nil
}

// ExportDNA serializes a PersonaDNA struct into either YAML or formatted JSON.
func ExportDNA(dna *model.PersonaDNA, format string) ([]byte, error) {
	if dna == nil {
		return nil, errors.New("nil persona DNA")
	}

	if strings.EqualFold(format, "yaml") || strings.EqualFold(format, "yml") {
		return yaml.Marshal(dna)
	}

	return json.MarshalIndent(dna, "", "  ")
}
