package orchestrator

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Port of the Kotlin SemanticEvaluator.similarity (git d0af8e3). The Flesch/syllable helpers
// in the Kotlin object were not ported (not used by the settings being wired).

// Mirrors Kotlin's list verbatim. Entries with apostrophes can never match, since the
// tokenizer turns ' into a space first — kept for fidelity.
var semanticStopwords = func() map[string]bool {
	m := map[string]bool{}
	for _, w := range strings.Fields(`a about above after again against all am an and
any are aren't as at be because been before being
below between both but by can cannot could couldn't
did didn't do does doesn't doing don't down during
each few for from further had hadn't has hasn't
have haven't having he he'd he'll he's her here
here's hers herself him himself his how how's i
i'd i'll i'm i've if in into is isn't it it's
its itself let's me more most mustn't my myself
no nor not of off on once only or other ought
our ours ourselves out over own same shan't she
she'd she'll she's should shouldn't so some such
than that that's the their theirs them themselves
then there there's these they they'd they'll they're
they've this those through to too under until up
very was wasn't we we'd we'll we're we've were
weren't what what's when when's where where's which
while who who's whom why why's with won't would
wouldn't you you'd you'll you're you've your yours
yourself yourselves`) {
		m[w] = true
	}
	return m
}()

var reSemanticPunct = regexp.MustCompile("[#*_`~>\\[\\](){}|\\\\.,!?:;\"'/\\-]")

// SemanticSimilarity returns topical coverage of topic by text in [0,1] (unigram + adjacent
// bigram coverage averaged with stem/prefix word coverage). 0 when either side is blank.
func SemanticSimilarity(text, topic string) float64 {
	if strings.TrimSpace(text) == "" || strings.TrimSpace(topic) == "" {
		return 0
	}
	textTokens := semanticTerms(text)
	topicTokens := semanticTerms(topic)
	if len(textTokens) == 0 || len(topicTokens) == 0 {
		return 0
	}
	textFreq := map[string]bool{}
	for _, t := range textTokens {
		textFreq[t] = true
	}
	// Weighted by topic term count, so a repeated topic term counts per occurrence.
	matched, total := 0.0, 0.0
	for _, t := range topicTokens {
		total++
		if textFreq[t] {
			matched++
		}
	}
	topicCoverage := matched / total

	textWords := map[string]bool{}
	for _, t := range textTokens {
		if !strings.Contains(t, "_") {
			textWords[t] = true
		}
	}
	var topicWords []string
	for _, t := range topicTokens {
		if !strings.Contains(t, "_") {
			topicWords = append(topicWords, t)
		}
	}
	wordMatches := 0
	for _, tw := range topicWords {
		for w := range textWords {
			if w == tw || (utf8.RuneCountInString(w) >= 4 && utf8.RuneCountInString(tw) >= 4 &&
				(strings.HasPrefix(w, tw) || strings.HasPrefix(tw, w))) {
				wordMatches++
				break
			}
		}
	}
	wordCoverage := 0.0
	if len(topicWords) > 0 {
		wordCoverage = float64(wordMatches) / float64(len(topicWords))
	}
	return clamp(topicCoverage*0.5+wordCoverage*0.5, 0, 1)
}

func semanticTerms(raw string) []string {
	clean := reCodeBlock.ReplaceAllString(raw, "")
	clean = strings.ToLower(reSemanticPunct.ReplaceAllString(clean, " "))
	var words []string
	for _, f := range reSpaces.Split(clean, -1) {
		w := strings.Map(func(r rune) rune {
			if unicode.IsLetter(r) || unicode.IsDigit(r) {
				return r
			}
			return -1
		}, strings.TrimSpace(f))
		if utf8.RuneCountInString(w) > 2 && !semanticStopwords[w] {
			words = append(words, w)
		}
	}
	terms := append([]string{}, words...)
	for i := 0; i+1 < len(words); i++ {
		terms = append(terms, words[i]+"_"+words[i+1])
	}
	return terms
}
