package steering

import (
	"fmt"
	"regexp"
	"strings"
)

var injectionPatterns = []*regexp.Regexp{
	regexp.MustCompile(`(?i)(ignore|disregard|forget)\s+(all\s+)?(previous|prior|above)\s+(instructions|prompts|rules)`),
	regexp.MustCompile(`(?i)system\s+prompt\s+(override|leak|injection)`),
	regexp.MustCompile(`(?i)you\s+are\s+now\s+(in\s+)?(developer\s+mode|dan\s+mode)`),
	regexp.MustCompile(`(?i)bypass\s+all\s+(rules|taboos|security|safety|restrictions)`),
	regexp.MustCompile(`(?i)disregard\s+(taboo|rules|policies)`),
	regexp.MustCompile(`(?i)exfiltrate\s+(secrets|env|keys|tokens)`),
}

// ScanPromptInjection analyzes untrusted text (such as AGENTS.md, CLAUDE.md, PR review comments)
// for prompt injection attempts attempting to subvert agent instructions or Taboo Space.
func ScanPromptInjection(content string) []string {
	var detections []string
	if strings.TrimSpace(content) == "" {
		return detections
	}

	for _, pattern := range injectionPatterns {
		if match := pattern.FindString(content); match != "" {
			detections = append(detections, fmt.Sprintf("detected adversarial prompt injection pattern: %q", match))
		}
	}

	return detections
}

// ValidateSteeringContent inspects content and returns an error if prompt injection is detected.
func ValidateSteeringContent(sourceName, content string) error {
	detections := ScanPromptInjection(content)
	if len(detections) > 0 {
		return fmt.Errorf("steering source %q rejected due to prompt injection risks: %s", sourceName, strings.Join(detections, "; "))
	}
	return nil
}
