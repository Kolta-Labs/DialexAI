package retrieval

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"

	"dialex/pkg/graph"
	"dialex/pkg/model"
	"dialex/pkg/runner"
)

// DynamicRetriever orchestrates in-debate RAG across the local epistemic knowledge graph,
// attached project reference files, and workspace documentation.
type DynamicRetriever struct {
	GraphStore graph.GraphStore
}

// NewDynamicRetriever constructs a dynamic retriever backed by an optional graph store.
func NewDynamicRetriever(gs graph.GraphStore) *DynamicRetriever {
	return &DynamicRetriever{
		GraphStore: gs,
	}
}

// ExtractRoundQueries extracts 1-3 targeted search queries from the delta of claims raised in a round.
func (dr *DynamicRetriever) ExtractRoundQueries(
	ctx context.Context,
	r runner.AgentRunner,
	agent model.Agent,
	topic string,
	round int,
	roundMessages []model.DebateMessage,
	modelOverride string,
) ([]string, error) {
	if r == nil || len(roundMessages) == 0 {
		return dr.HeuristicExtractQueries(topic, roundMessages), nil
	}

	var sb strings.Builder
	for _, m := range roundMessages {
		if !m.IsError && !m.IsSystem && !m.IsUserComment {
			speaker := m.AuthorDisplayName
			if speaker == "" {
				speaker = string(m.Provider)
			}
			fmt.Fprintf(&sb, "[%s]: %s\n\n", speaker, m.Content)
		}
	}
	content := sb.String()
	if strings.TrimSpace(content) == "" {
		return nil, nil
	}

	prompt := fmt.Sprintf(`You are an authoritative fact-checking retrieval planner for a technical deliberation council.
Topic: %s
Deliberation Round: %d

Analyze the arguments, contested assertions, benchmark figures, and technology trade-offs raised by participants in this round:
%s

Extract 1 to 3 distinct, high-precision search queries to retrieve authoritative grounding evidence (past ADRs, benchmark numbers, architecture specs, API constraints) from the project's knowledge graph and attached files.

Respond ONLY with valid JSON in this exact structure:
{
  "queries": [
    "query 1",
    "query 2"
  ]
}`, topic, round, content)

	agentCopy := agent
	agentCopy.SystemPrompt = prompt

	resp, err := r.Respond(ctx, agentCopy, topic, "Extract retrieval queries for empirical grounding", "", nil, modelOverride)
	if err != nil {
		return dr.HeuristicExtractQueries(topic, roundMessages), nil
	}

	cleaned := strings.TrimSpace(resp.Content)
	if strings.HasPrefix(cleaned, "```") {
		lines := strings.Split(cleaned, "\n")
		if len(lines) >= 2 {
			end := len(lines) - 1
			if strings.HasPrefix(lines[end], "```") {
				cleaned = strings.Join(lines[1:end], "\n")
			}
		}
	}

	var parsed struct {
		Queries []string `json:"queries"`
	}
	if err := json.Unmarshal([]byte(cleaned), &parsed); err == nil && len(parsed.Queries) > 0 {
		var valid []string
		for _, q := range parsed.Queries {
			t := strings.TrimSpace(q)
			if t != "" {
				valid = append(valid, t)
			}
		}
		if len(valid) > 0 {
			if len(valid) > 3 {
				valid = valid[:3]
			}
			return valid, nil
		}
	}

	return dr.HeuristicExtractQueries(topic, roundMessages), nil
}

// HeuristicExtractQueries is a fast, deterministic fallback extracting key technical terms and claims.
func (dr *DynamicRetriever) HeuristicExtractQueries(topic string, roundMessages []model.DebateMessage) []string {
	var combined strings.Builder
	for _, m := range roundMessages {
		if !m.IsError && !m.IsSystem && !m.IsUserComment {
			combined.WriteString(m.Content)
			combined.WriteString(" ")
		}
	}
	text := combined.String()
	if strings.TrimSpace(text) == "" {
		return nil
	}

	techKeywords := []string{
		"benchmark", "latency", "throughput", "concurrency", "consistency", "replication",
		"postgres", "sqlite", "mysql", "redis", "kafka", "grpc", "graphql", "rest",
		"auth", "jwt", "oauth", "zero-trust", "security", "encryption", "paxos", "raft",
		"scalability", "sharding", "acid", "wal", "lock", "simd", "cache", "memory", "goroutine",
	}

	foundKeywords := make(map[string]bool)
	lower := strings.ToLower(text)
	for _, kw := range techKeywords {
		if strings.Contains(lower, kw) {
			foundKeywords[kw] = true
		}
	}

	var queries []string
	if len(foundKeywords) > 0 {
		var kws []string
		for kw := range foundKeywords {
			kws = append(kws, kw)
		}
		sort.Strings(kws)

		// Create 1-2 focused queries
		if len(kws) <= 2 {
			queries = append(queries, strings.Join(kws, " "))
		} else {
			queries = append(queries, strings.Join(kws[:2], " "))
			queries = append(queries, strings.Join(kws[2:min(4, len(kws))], " "))
		}
	}

	// Also extract capitalized technical terms / acronyms (e.g. SQLite, ACID, Kafka, PostgreSQL, WAL)
	acronymRegex := regexp.MustCompile(`\b[A-Z][A-Za-z0-9_]{2,}\b`)
	matches := acronymRegex.FindAllString(text, -1)
	capitalizedSeen := make(map[string]int)
	for _, m := range matches {
		if !isCommonEnglishWord(m) {
			capitalizedSeen[m]++
		}
	}

	type countPair struct {
		term  string
		count int
	}
	var pairs []countPair
	for t, c := range capitalizedSeen {
		pairs = append(pairs, countPair{term: t, count: c})
	}
	sort.Slice(pairs, func(i, j int) bool {
		return pairs[i].count > pairs[j].count
	})

	if len(pairs) > 0 && len(queries) < 3 {
		var topTerms []string
		for i := 0; i < min(3, len(pairs)); i++ {
			topTerms = append(topTerms, pairs[i].term)
		}
		candidate := strings.Join(topTerms, " ")
		isDup := false
		for _, q := range queries {
			if strings.EqualFold(q, candidate) {
				isDup = true
				break
			}
		}
		if !isDup {
			queries = append(queries, candidate)
		}
	}

	if len(queries) == 0 && strings.TrimSpace(topic) != "" {
		queries = append(queries, strings.TrimSpace(topic))
	}

	if len(queries) > 3 {
		queries = queries[:3]
	}
	return queries
}

// RetrieveForRound orchestrates multi-source dynamic retrieval, deduplication, and context formatting for Round N+1.
func (dr *DynamicRetriever) RetrieveForRound(
	ctx context.Context,
	r runner.AgentRunner,
	agent model.Agent,
	projectID string,
	topic string,
	round int,
	roundMessages []model.DebateMessage,
	attachedFiles []model.AttachedFile,
	existingEvidence []model.RoundEvidence,
) (model.RoundEvidence, error) {
	queries, err := dr.ExtractRoundQueries(ctx, r, agent, topic, round, roundMessages, "")
	if err != nil || len(queries) == 0 {
		return model.RoundEvidence{Round: round + 1}, nil
	}

	// Build set of previously retrieved source IDs across all prior rounds to guarantee deduplication
	seenSources := make(map[string]bool)
	for _, re := range existingEvidence {
		for _, item := range re.Items {
			seenSources[item.SourceID] = true
		}
	}

	var candidates []model.EvidenceItem
	now := time.Now().UTC()

	// 1. Search Knowledge Graph if available
	if dr.GraphStore != nil && projectID != "" {
		for _, q := range queries {
			nodes, err := dr.GraphStore.SearchFTS(ctx, projectID, q, 5, now)
			if err == nil {
				for _, n := range nodes {
					if seenSources[n.ID] {
						continue
					}
					score := n.CurrentWeight
					if score <= 0 {
						score = 0.5
					}
					snippet := cleanSnippet(n.Content, 280)
					candidates = append(candidates, model.EvidenceItem{
						ID:               fmt.Sprintf("ev_g_%s", n.ID),
						Round:            round + 1,
						Query:            q,
						SourceType:       model.EvidenceSourceKnowledgeGraph,
						SourceID:         n.ID,
						SourceTitle:      n.Title,
						Snippet:          snippet,
						Score:            score,
						AttributionBadge: fmt.Sprintf("[Evidence: Graph Node \"%s\"]", n.Title),
						TimestampMs:      now.UnixMilli(),
					})
				}
			}
		}
	}

	// 2. Search Attached Files and Documentation
	for _, file := range attachedFiles {
		if strings.TrimSpace(file.Content) == "" {
			continue
		}
		passages := splitIntoPassages(file.Content, 400)
		for _, q := range queries {
			qTokens := tokenize(q)
			if len(qTokens) == 0 {
				continue
			}
			for pIdx, passage := range passages {
				srcID := fmt.Sprintf("%s_p%d", file.ID, pIdx)
				if seenSources[srcID] {
					continue
				}
				score := computePassageScore(passage, qTokens)
				if score > 0.35 {
					snippet := cleanSnippet(passage, 280)
					candidates = append(candidates, model.EvidenceItem{
						ID:               fmt.Sprintf("ev_f_%s", srcID),
						Round:            round + 1,
						Query:            q,
						SourceType:       model.EvidenceSourceAttachedFile,
						SourceID:         srcID,
						SourceTitle:      file.Name,
						Snippet:          snippet,
						Score:            score,
						AttributionBadge: fmt.Sprintf("[Evidence: File \"%s\"]", file.Name),
						TimestampMs:      now.UnixMilli(),
					})
				}
			}
		}
	}

	// Deduplicate candidates among themselves by SourceID, keeping highest score
	dedupMap := make(map[string]model.EvidenceItem)
	for _, c := range candidates {
		existing, ok := dedupMap[c.SourceID]
		if !ok || c.Score > existing.Score {
			dedupMap[c.SourceID] = c
		}
	}

	var uniqueCandidates []model.EvidenceItem
	for _, item := range dedupMap {
		uniqueCandidates = append(uniqueCandidates, item)
	}

	// Sort candidates by score descending
	sort.Slice(uniqueCandidates, func(i, j int) bool {
		return uniqueCandidates[i].Score > uniqueCandidates[j].Score
	})

	// Select top-3 items
	const maxEvidencePerRound = 3
	var selected []model.EvidenceItem
	if len(uniqueCandidates) > maxEvidencePerRound {
		selected = uniqueCandidates[:maxEvidencePerRound]
	} else {
		selected = uniqueCandidates
	}

	summaryContext := FormatEvidenceContext(round+1, selected)

	return model.RoundEvidence{
		Round:          round + 1,
		TriggerQueries: queries,
		Items:          selected,
		SummaryContext: summaryContext,
	}, nil
}

// FormatEvidenceContext formats evidence items into a high-priority prompt grounding block.
func FormatEvidenceContext(round int, items []model.EvidenceItem) string {
	if len(items) == 0 {
		return ""
	}

	var sb strings.Builder
	fmt.Fprintf(&sb, "[DYNAMIC GROUNDING EVIDENCE FOR ROUND %d]\n", round)
	sb.WriteString("The following authoritative evidence was retrieved based on disputed claims from the previous round:\n")
	for i, item := range items {
		fmt.Fprintf(&sb, "%d. %s (Relevance: %.2f):\n   \"%s\"\n", i+1, item.AttributionBadge, item.Score, item.Snippet)
	}
	sb.WriteString("\nMANDATE: You MUST anchor your arguments to this retrieved evidence where applicable. Cite authoritative points using attribution tags like [Evidence: ...]. Challenge any ungrounded assertions made by peers that conflict with this evidence.\n")

	return sb.String()
}

func cleanSnippet(text string, maxLen int) string {
	cleaned := strings.Join(strings.Fields(text), " ")
	if len(cleaned) <= maxLen {
		return cleaned
	}
	return cleaned[:maxLen] + "..."
}

func splitIntoPassages(text string, approxChars int) []string {
	paragraphs := strings.Split(text, "\n\n")
	var passages []string
	var cur strings.Builder

	for _, p := range paragraphs {
		trimmed := strings.TrimSpace(p)
		if trimmed == "" {
			continue
		}
		if cur.Len()+len(trimmed) > approxChars && cur.Len() > 0 {
			passages = append(passages, cur.String())
			cur.Reset()
		}
		if cur.Len() > 0 {
			cur.WriteString("\n\n")
		}
		cur.WriteString(trimmed)
	}
	if cur.Len() > 0 {
		passages = append(passages, cur.String())
	}
	return passages
}

func tokenize(query string) []string {
	fields := strings.Fields(strings.ToLower(query))
	var tokens []string
	for _, f := range fields {
		f = strings.Trim(f, ",.:;!?\"'()[]{}")
		if len(f) >= 3 && !isCommonEnglishWord(f) {
			tokens = append(tokens, f)
		}
	}
	return tokens
}

func computePassageScore(passage string, queryTokens []string) float64 {
	lower := strings.ToLower(passage)
	hits := 0
	for _, tok := range queryTokens {
		if strings.Contains(lower, tok) {
			hits++
		}
	}
	if len(queryTokens) == 0 {
		return 0
	}
	ratio := float64(hits) / float64(len(queryTokens))
	return mathMin(1.0, ratio*0.95+0.05)
}

func mathMin(a, b float64) float64 {
	if a < b {
		return a
	}
	return b
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func isCommonEnglishWord(w string) bool {
	switch strings.ToLower(w) {
	case "the", "and", "that", "have", "for", "not", "with", "you", "this", "but", "his", "from",
		"they", "say", "her", "she", "will", "one", "all", "would", "there", "their", "what",
		"out", "about", "who", "get", "which", "go", "me", "when", "make", "can", "like", "time",
		"no", "just", "him", "know", "take", "people", "into", "year", "your", "good", "some",
		"could", "them", "see", "other", "than", "then", "now", "look", "only", "come", "its",
		"over", "think", "also", "back", "after", "use", "two", "how", "our", "work", "first",
		"well", "way", "even", "new", "want", "because", "any", "these", "give", "day", "most", "us":
		return true
	default:
		return false
	}
}
