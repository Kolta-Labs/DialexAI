package tui

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"kritix/pkg/steering"
)

// MentionType classifies what kind of context was referenced.
type MentionType string

const (
	MentionFile   MentionType = "file"
	MentionSpec   MentionType = "spec"
	MentionRule   MentionType = "rule"
	MentionSymbol MentionType = "symbol"
)

// ResolvedMention holds the extracted content and metadata of a mention.
type ResolvedMention struct {
	RawTag  string      `json:"rawTag"`
	Type    MentionType `json:"type"`
	Target  string      `json:"target"`
	Content string      `json:"content"`
	Error   string      `json:"error,omitempty"`
}

// MentionResolver resolves @ and # tokens within user prompts into rich context.
type MentionResolver struct {
	rootDir string
}

// NewMentionResolver creates a resolver for a repository root.
func NewMentionResolver(rootDir string) *MentionResolver {
	return &MentionResolver{rootDir: rootDir}
}

var mentionRegex = regexp.MustCompile(`(@[a-zA-Z0-9_\-./:]+|#[a-zA-Z0-9_\-]+)`)

// ResolveAll scans text for mentions, loads their content, and returns stripped prompt + context attachments.
func (r *MentionResolver) ResolveAll(prompt string) (string, []ResolvedMention) {
	var resolved []ResolvedMention
	cleanedPrompt := prompt

	matches := mentionRegex.FindAllString(prompt, -1)
	for _, raw := range matches {
		m := r.resolveOne(raw)
		if m != nil {
			resolved = append(resolved, *m)
		}
	}

	return cleanedPrompt, resolved
}

func (r *MentionResolver) resolveOne(raw string) *ResolvedMention {
	if strings.HasPrefix(raw, "@file:") {
		path := strings.TrimPrefix(raw, "@file:")
		fullPath := filepath.Join(r.rootDir, path)
		content, err := os.ReadFile(fullPath)
		if err != nil {
			return &ResolvedMention{
				RawTag: raw,
				Type:   MentionFile,
				Target: path,
				Error:  fmt.Sprintf("file not found: %v", err),
			}
		}
		return &ResolvedMention{
			RawTag:  raw,
			Type:    MentionFile,
			Target:  path,
			Content: string(content),
		}
	}

	if strings.HasPrefix(raw, "@spec:") {
		specID := strings.TrimPrefix(raw, "@spec:")
		specPath := filepath.Join(r.rootDir, "docs", "specs", fmt.Sprintf("%s.md", specID))
		if !strings.HasSuffix(specID, ".md") {
			specPath = filepath.Join(r.rootDir, "docs", "specs", fmt.Sprintf("%s.md", specID))
		} else {
			specPath = filepath.Join(r.rootDir, "docs", "specs", specID)
		}
		content, err := os.ReadFile(specPath)
		if err != nil {
			return &ResolvedMention{
				RawTag: raw,
				Type:   MentionSpec,
				Target: specID,
				Error:  fmt.Sprintf("spec not found: %v", err),
			}
		}
		return &ResolvedMention{
			RawTag:  raw,
			Type:    MentionSpec,
			Target:  specID,
			Content: string(content),
		}
	}

	if strings.HasPrefix(raw, "@rule:") {
		ruleName := strings.TrimPrefix(raw, "@rule:")
		agg := steering.NewAggregator(r.rootDir)
		rules, _ := agg.CollectLocalRules()
		for _, rule := range rules {
			if strings.EqualFold(rule.ID, ruleName) || strings.EqualFold(rule.Name, ruleName) {
				return &ResolvedMention{
					RawTag:  raw,
					Type:    MentionRule,
					Target:  rule.Name,
					Content: rule.Content,
				}
			}
		}
		return &ResolvedMention{
			RawTag: raw,
			Type:   MentionRule,
			Target: ruleName,
			Error:  "rule not found in active steering",
		}
	}

	if strings.HasPrefix(raw, "#") {
		symbol := strings.TrimPrefix(raw, "#")
		return &ResolvedMention{
			RawTag:  raw,
			Type:    MentionSymbol,
			Target:  symbol,
			Content: fmt.Sprintf("// Symbol reference: %s (Context search target)", symbol),
		}
	}

	return nil
}
