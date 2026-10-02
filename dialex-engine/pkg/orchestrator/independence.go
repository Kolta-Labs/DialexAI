package orchestrator

import (
	"fmt"
	"regexp"
	"sort"
	"strings"

	"dialex/pkg/model"
)

// applyIndependence returns the transcript view a seat is allowed to see. It never mutates the
// persisted transcript: blind and anonymized views are built per turn.
//
//   - BlindFirstRound drops other seats' round-1 answers while round 1 is being played.
//   - AnonymizeTranscript renames other seats "Participant A/B/C" by their fixed seat order, so
//     the same seat keeps the same letter for everyone.
//
// Moderator interventions, system notes, user comments and the seat's own turns are untouched.
func applyIndependence(views []model.DebateMessage, forAgent model.Agent, round int, cfg *model.IndependenceConfig, seats []model.Agent) []model.DebateMessage {
	if cfg == nil || (!cfg.BlindFirstRound && !cfg.AnonymizeTranscript) {
		return views
	}
	index := make(map[string]int, len(seats))
	for i, a := range seats {
		index[a.ID] = i
	}
	out := make([]model.DebateMessage, 0, len(views))
	for _, m := range views {
		i, isSeatTurn := index[m.SeatID]
		peerTurn := isSeatTurn && !m.IsSystem && !m.IsUserComment && !m.IsModeratorIntervention && !ownedBySeat(m, forAgent)
		if peerTurn && cfg.BlindFirstRound && round == 1 && m.Round == 1 {
			continue
		}
		if peerTurn && cfg.AnonymizeTranscript {
			m.AuthorDisplayName = seatLabel(i)
		}
		if cfg.AnonymizeTranscript && isSeatTurn && !m.IsSystem && !m.IsUserComment {
			m.Content = scrubIdentity(m.Content, forAgent, seats)
		}
		out = append(out, m)
	}
	return out
}

// seatLabel is the anonymous name a seat gets in anonymized views.
func seatLabel(i int) string { return fmt.Sprintf("Participant %c", 'A'+rune(i)) }

// identityNames lists the names and roles that give a seat away, longest first so "Neutral
// Chair" is replaced before "Chair".
func identityNames(a model.Agent) []string {
	var out []string
	for _, n := range []string{a.DisplayName, a.Role} {
		if n = strings.TrimSpace(n); len(n) >= 3 {
			out = append(out, n)
		}
	}
	return out
}

// scrubIdentity replaces other seats' names and roles inside a message with their anonymous
// labels, so "As the Skeptic, I..." cannot undo the anonymization. The viewer's own name is left
// alone. It removes the explicit leak only; writing style still carries identity.
func scrubIdentity(text string, viewer model.Agent, seats []model.Agent) string {
	type rep struct {
		re    *regexp.Regexp
		label string
	}
	var reps []rep
	for i, a := range seats {
		if a.ID == viewer.ID {
			continue
		}
		for _, n := range identityNames(a) {
			reps = append(reps, rep{regexp.MustCompile(`(?i)\b` + regexp.QuoteMeta(n) + `\b`), seatLabel(i)})
		}
	}
	sort.SliceStable(reps, func(i, j int) bool { return len(reps[i].re.String()) > len(reps[j].re.String()) })
	for _, r := range reps {
		text = r.re.ReplaceAllString(text, r.label)
	}
	return text
}

// CountIdentityLeaks counts how many seat turns name a seat (their own or another's) in their
// own text. Run it on the raw transcript to see how often personas give themselves away.
func CountIdentityLeaks(transcript []model.DebateMessage, seats []model.Agent) int {
	leaks := 0
	for _, m := range transcript {
		lower := strings.ToLower(m.Content)
		for _, a := range seats {
			hit := false
			for _, n := range identityNames(a) {
				if strings.Contains(lower, strings.ToLower(n)) {
					hit = true
				}
			}
			if hit {
				leaks++
				break
			}
		}
	}
	return leaks
}

// ApplyIndependence is applyIndependence for callers outside the orchestrator, such as the
// benchmark runner, which plays its own council loop.
func ApplyIndependence(views []model.DebateMessage, forAgent model.Agent, round int, cfg *model.IndependenceConfig, seats []model.Agent) []model.DebateMessage {
	return applyIndependence(views, forAgent, round, cfg, seats)
}
