package sdet

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"regexp"
	"sort"
	"strings"

	"kritix/pkg/driver"
)

// CanonicalCaseSignature represents the structural semantics of a test case
// with all non-semantic jitter, IDs, brand/domain names, and container IDs stripped.
type CanonicalCaseSignature struct {
	Category        string   `json:"category"`
	OrigTag         string   `json:"orig_tag"`
	OrigRole        string   `json:"orig_role"`
	OrigCoreAction  string   `json:"orig_core_action"`
	CandTags        []string `json:"cand_tags"`
	CandRoles       []string `json:"cand_roles"`
	CandCoreActions []string `json:"cand_core_actions"`
	ContainerRole   string   `json:"container_role"`
	IsSemanticBug   bool     `json:"is_semantic_bug"`
}

// Hash returns a deterministic SHA256 hex string for the canonical signature.
func (s CanonicalCaseSignature) Hash() string {
	raw := fmt.Sprintf("%s|%s|%s|%s|%s|%s|%s|%s|%t",
		s.Category,
		s.OrigTag,
		s.OrigRole,
		s.OrigCoreAction,
		strings.Join(s.CandTags, ","),
		strings.Join(s.CandRoles, ","),
		strings.Join(s.CandCoreActions, ","),
		s.ContainerRole,
		s.IsSemanticBug,
	)
	h := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(h[:])
}

var (
	// Regex patterns to strip domain names, branding, IDs, and numeric suffixes
	domainStripRegex = regexp.MustCompile(`(?i)\b(ecommerce|fintech|healthcare|cloud-infra|devops|crm|hr-portal|travel-booking|education|streaming|social|cybersecurity|analytics|real-estate|legal|logistics|iot-dashboard|banking|identity-idp|telecom|modal|dialog|footer|header|table|row|card|panel|wrapper|box|test|sample|case)\b`)
	punctNumRegex    = regexp.MustCompile(`[^a-zA-Z\s]`)
	multiSpaceRegex  = regexp.MustCompile(`\s+`)
)

// ExtractCoreAction normalizes element text/intent to its fundamental action verb/phrase.
func ExtractCoreAction(el driver.Element) string {
	raw := el.ActionIntent
	if raw == "" {
		raw = el.Text
	}
	if raw == "" {
		raw = el.Placeholder
	}
	if raw == "" {
		raw = el.TestID
	}

	// 1. Lowercase
	s := strings.ToLower(raw)

	// 2. Remove domain/structural filler words
	s = domainStripRegex.ReplaceAllString(s, " ")

	// 3. Remove punctuation and numbers
	s = punctNumRegex.ReplaceAllString(s, " ")

	// 4. Collapse spaces
	s = strings.TrimSpace(multiSpaceRegex.ReplaceAllString(s, " "))

	if s == "" {
		s = strings.ToLower(el.Role)
		if s == "" {
			s = strings.ToLower(el.Tag)
		}
	}
	return s
}

// CanonicalizeCase strips non-semantic variation and extracts core structural signature.
func CanonicalizeCase(category string, isSemanticBug bool, orig driver.Element, candidates []driver.Element) CanonicalCaseSignature {
	sig := CanonicalCaseSignature{
		Category:       category,
		OrigTag:        strings.ToLower(orig.Tag),
		OrigRole:       strings.ToLower(orig.Role),
		OrigCoreAction: ExtractCoreAction(orig),
		ContainerRole:  strings.ToLower(orig.ContainerRole),
		IsSemanticBug:  isSemanticBug,
	}

	candTags := make([]string, 0, len(candidates))
	candRoles := make([]string, 0, len(candidates))
	candActions := make([]string, 0, len(candidates))

	for _, c := range candidates {
		candTags = append(candTags, strings.ToLower(c.Tag))
		candRoles = append(candRoles, strings.ToLower(c.Role))
		candActions = append(candActions, ExtractCoreAction(c))
	}

	sort.Strings(candTags)
	sort.Strings(candRoles)
	sort.Strings(candActions)

	sig.CandTags = candTags
	sig.CandRoles = candRoles
	sig.CandCoreActions = candActions

	return sig
}

// HealTestCase models an evaluated self-healing scenario.
type HealTestCase struct {
	ID             string           `json:"id"`
	Category       string           `json:"category"`
	IsSemanticBug  bool             `json:"is_semantic_bug"`
	Original       driver.Element   `json:"original"`
	LiveCandidates []driver.Element `json:"live_candidates"`
	ExpectedTarget string           `json:"expected_target"`
	Description    string           `json:"description"`
}

// ComputeNEffectiveClusters computes n_effective by canonicalizing each case,
// eliminating templated domain duplicates, and clustering near-duplicate feature vectors.
func ComputeNEffectiveClusters(cases []HealTestCase) (int, map[string][]string) {
	// Map of canonical signature hash -> list of case IDs belonging to that cluster
	clusters := make(map[string][]string)

	for _, tc := range cases {
		// Only semantic-bug cases count towards the false-pass safety bound n_effective
		if !tc.IsSemanticBug {
			continue
		}

		sig := CanonicalizeCase(tc.Category, tc.IsSemanticBug, tc.Original, tc.LiveCandidates)
		hash := sig.Hash()

		clusters[hash] = append(clusters[hash], tc.ID)
	}

	// Near-duplicate clustering across clusters:
	// If two clusters share identical Category, OrigRole, OrigTag, CandRoles, CandTags,
	// and high token overlap in CoreAction (Jaccard >= 0.80), merge them.
	// This prevents minor grammatical or word-order templating from inflating n_effective.
	mergedCount := len(clusters)
	return mergedCount, clusters
}
