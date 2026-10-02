package orchestrator

import (
	"fmt"
	"regexp"
	"strings"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"
)

// Port of the Kotlin LoopDetector (git d0af8e3, orchestrator/LoopDetector.kt).

// LoopResult is the outcome of InspectLoop. Detected=false is Kotlin's LoopInspectionResult.Clean.
type LoopResult struct {
	Detected               bool
	SimilarityScore        float64
	LongestContiguousMatch string
	// DuplicatedTurnRound is the 1-based index into priorTurns of the turn that matched.
	DuplicatedTurnRound int
	Reason              string
}

// AntiLoopDirective is appended to the agent's instructions on a retry (Kotlin ANTI_LOOP_DIRECTIVE).
const AntiLoopDirective = `
[CRITICAL SYSTEM DIRECTIVE: REPETITION DETECTED]
Your prior draft was rejected because it duplicated previous debate arguments (>65% identical content or verbatim sections).
You MUST:
1. Provide completely new empirical evidence, unaddressed counter-arguments, or novel perspective.
2. DO NOT re-state your thesis or repeat your prior examples.
3. If you have nothing substantively new to contribute, you must cleanly concede or state "AGREED:" followed by your 1-2 sentence core reason.
`

// StalledConcessionText is the CONVERT_TO_CONCESSION fallback turn body.
func StalledConcessionText(label string, round int) string {
	return fmt.Sprintf("### %s — Round %d\n*Position Maintained:* Participant maintains their prior position regarding the discussion topic with no new counter-arguments to add to the council.", label, round)
}

// StalledRetryConcessionText is the RETRY_WITH_DIRECTIVE fallback turn body.
func StalledRetryConcessionText(label string, round int) string {
	return fmt.Sprintf("### %s — Round %d\n*Position Maintained:* Core position maintained.", label, round)
}

// ModeratorImpasseText is the MODERATOR_INTERVENE fallback message body.
const ModeratorImpasseText = "The deliberation between participants has reached an impasse on repetitive arguments. Let us pivot to examining the unaddressed trade-offs and concrete implementation constraints."

var (
	reCodeBlock = regexp.MustCompile("(?s)```.*?```")
	reMarkdown  = regexp.MustCompile("[#*_`~>\\[\\]()]")
	// Java's \s also matches \x0B; Go's does not.
	reSpaces = regexp.MustCompile(`[\s\x0B]+`)
)

// InspectLoop checks newText against priorTurns: Tier 1 verbatim contiguous block
// (>= maxContiguous chars), Tier 2 unigram+bigram+trigram Jaccard (>= threshold), plus a
// circular-agreement echo check. Kotlin defaults: threshold 0.65, maxContiguous 180.
func InspectLoop(newText string, priorTurns []string, threshold float64, maxContiguous int) LoopResult {
	if strings.TrimSpace(newText) == "" || len(priorTurns) == 0 {
		return LoopResult{}
	}
	normalizedNew := loopNormalize(newText)
	if utf16Len(normalizedNew) < 15 {
		return LoopResult{}
	}
	isAgreement := isAgreementStart(normalizedNew)
	newNGrams := extractNGrams(normalizedNew)

	for index, prior := range priorTurns {
		normalizedPrior := loopNormalize(prior)
		if strings.TrimSpace(normalizedPrior) == "" {
			continue
		}
		if isAgreement && isAgreementStart(normalizedPrior) {
			newWords := extractWords(normalizedNew)
			priorWords := extractWords(normalizedPrior)
			if len(newWords) > 0 && len(priorWords) > 0 {
				inter := 0
				for w := range newWords {
					if priorWords[w] {
						inter++
					}
				}
				overlap := float64(inter) / float64(len(newWords))
				if overlap > 0.85 && len(priorTurns) >= 4 {
					return LoopResult{
						Detected:               true,
						SimilarityScore:        overlap,
						LongestContiguousMatch: "Repeated agreement formula",
						DuplicatedTurnRound:    index + 1,
						Reason:                 "Circular agreement echo detected across multiple turns",
					}
				}
			}
			continue
		}

		lcs := longestCommonSubstring16(utf16.Encode([]rune(normalizedNew)), utf16.Encode([]rune(normalizedPrior)))
		if len(lcs) >= maxContiguous {
			return LoopResult{
				Detected:               true,
				SimilarityScore:        1.0,
				LongestContiguousMatch: take16(lcs, 100),
				DuplicatedTurnRound:    index + 1,
				Reason:                 fmt.Sprintf("Verbatim cloned block detected (%d chars >= %d chars)", len(lcs), maxContiguous),
			}
		}

		priorNGrams := extractNGrams(normalizedPrior)
		if len(newNGrams) > 0 && len(priorNGrams) > 0 {
			inter := 0
			for g := range newNGrams {
				if priorNGrams[g] {
					inter++
				}
			}
			union := len(newNGrams) + len(priorNGrams) - inter
			jaccard := 0.0
			if union > 0 {
				jaccard = float64(inter) / float64(union)
			}
			if jaccard >= threshold {
				return LoopResult{
					Detected:               true,
					SimilarityScore:        jaccard,
					LongestContiguousMatch: take16(lcs, 100),
					DuplicatedTurnRound:    index + 1,
					Reason:                 fmt.Sprintf("Structural loop detected: Jaccard similarity %d%% >= %d%%", int(jaccard*100), int(threshold*100)),
				}
			}
		}
	}
	return LoopResult{}
}

// LongestCommonSubstring returns the longest common substring of a and b (exported as in Kotlin).
func LongestCommonSubstring(a, b string) string {
	return take16(longestCommonSubstring16(utf16.Encode([]rune(a)), utf16.Encode([]rune(b))), -1)
}

func loopNormalize(text string) string {
	t := reCodeBlock.ReplaceAllString(text, "")
	t = reMarkdown.ReplaceAllString(t, " ")
	t = reSpaces.ReplaceAllString(t, " ")
	return strings.ToLower(strings.TrimSpace(t))
}

func isAgreementStart(s string) bool {
	return strings.HasPrefix(s, "agreed") || strings.HasPrefix(s, "concur")
}

func utf16Len(s string) int { return len(utf16.Encode([]rune(s))) }

func take16(u []uint16, n int) string {
	if n >= 0 && len(u) > n {
		u = u[:n]
	}
	return string(utf16.Decode(u))
}

// alnumWords splits on single spaces and strips every non letter/digit rune, like Kotlin's
// split(" ").map { filter(isLetterOrDigit) }.
func alnumWords(normalized string) []string {
	parts := strings.Split(normalized, " ")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		out = append(out, strings.Map(func(r rune) rune {
			if unicode.IsLetter(r) || unicode.IsDigit(r) {
				return r
			}
			return -1
		}, strings.TrimSpace(p)))
	}
	return out
}

func extractWords(normalized string) map[string]bool {
	set := map[string]bool{}
	for _, w := range alnumWords(normalized) {
		if utf8.RuneCountInString(w) > 2 {
			set[w] = true
		}
	}
	return set
}

func extractNGrams(normalized string) map[string]bool {
	var words []string
	for _, w := range alnumWords(normalized) {
		if w != "" {
			words = append(words, w)
		}
	}
	set := map[string]bool{}
	for i, w := range words {
		set[w] = true
		if i+1 < len(words) {
			set[w+" "+words[i+1]] = true
		}
		if i+2 < len(words) {
			set[w+" "+words[i+1]+" "+words[i+2]] = true
		}
	}
	return set
}

// longestCommonSubstring16 is the rolling-row DP over UTF-16 units (matches Kotlin's String
// indexing, including first-found tie-breaking).
func longestCommonSubstring16(a, b []uint16) []uint16 {
	if len(a) == 0 || len(b) == 0 {
		return nil
	}
	prev := make([]int, len(b)+1)
	curr := make([]int, len(b)+1)
	maxLen, end := 0, 0
	for i := 1; i <= len(a); i++ {
		for j := 1; j <= len(b); j++ {
			if a[i-1] == b[j-1] {
				curr[j] = prev[j-1] + 1
				if curr[j] > maxLen {
					maxLen = curr[j]
					end = i
				}
			} else {
				curr[j] = 0
			}
		}
		prev, curr = curr, prev
	}
	if maxLen == 0 {
		return nil
	}
	return a[end-maxLen : end]
}
