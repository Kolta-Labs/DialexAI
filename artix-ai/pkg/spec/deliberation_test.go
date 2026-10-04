package spec

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"artix/pkg/persona"
	"socratix/pkg/model"
)

func TestCouncilMultiPersonaDeliberation(t *testing.T) {
	tempDir := t.TempDir()
	reg := persona.NewRegistry(tempDir)
	council := NewCouncil(reg)

	var calls []string
	council.SetDeliberator(func(ctx context.Context, role string, p model.Persona, sys, prompt string) (string, error) {
		calls = append(calls, role)
		if role == "Council Synthesizer" {
			draft := DeliberationDraft{
				Title:     "Add OAuth2 Google Authentication",
				UserStory: "As an end user, I want to authenticate via Google OAuth2 so I can access my workspace securely.",
				InScope: []string{
					"Google OAuth2 token exchange",
					"Session persistence and refresh tokens",
				},
				OutOfScope: []string{
					"SAML enterprise integration",
				},
				AcceptanceCriteria: []Scenario{
					{
						Name:  "Successful Google OAuth Callback",
						Given: "a valid authorization code from Google",
						When:  "exchanged at /auth/google/callback",
						Then:  "creates a valid user session and JWT cookie",
					},
					{
						Name:  "Invalid or Tampered State Token",
						Given: "a callback request with mismatched CSRF state",
						When:  "the request is processed",
						Then:  "rejects authentication with HTTP 403 Forbidden",
					},
				},
				Decisions: []ArchitectureDecision{
					{
						ID:       "ADR-AUTH-01",
						Title:    "PKCE OAuth Flow & Session Cookie Isolation",
						Context:  "Implementing secure OAuth login.",
						Decision: "Use PKCE flow with HttpOnly Secure SameSite=Strict cookies.",
						Consequences: []string{
							"Mitigates authorization code injection and CSRF",
						},
					},
				},
				TestCommands: []string{"go test -v ./pkg/auth/..."},
			}
			bytes, _ := json.Marshal(draft)
			return string(bytes), nil
		}
		return "Deliberation contribution from " + role, nil
	})

	pCtx := &PlanningContext{
		StoryPrompt: "Add OAuth2 Google Authentication",
		Style:       StyleStandard,
	}

	spec, err := council.Plan(context.Background(), pCtx)
	if err != nil {
		t.Fatalf("unexpected council deliberation error: %v", err)
	}

	if len(calls) == 0 {
		t.Fatalf("expected council members to be called, got 0 calls")
	}

	if spec.Title != "Add OAuth2 Google Authentication" {
		t.Errorf("expected Title %q, got %q", "Add OAuth2 Google Authentication", spec.Title)
	}

	if len(spec.AcceptanceCriteria) != 2 {
		t.Errorf("expected 2 acceptance criteria from deliberation, got %d", len(spec.AcceptanceCriteria))
	}

	if len(spec.Decisions) != 1 || spec.Decisions[0].ID != "ADR-AUTH-01" {
		t.Errorf("expected ADR-AUTH-01, got %+v", spec.Decisions)
	}
}

func TestStoryProvenanceSidecar(t *testing.T) {
	tempDir := t.TempDir()
	specsDir := filepath.Join(tempDir, "docs", "specs")

	spec := &StorySpec{
		ID:        "STORY-12345",
		Title:     "Secure Payment Gateway",
		UserStory: "As a customer, I want to checkout securely.",
		AcceptanceCriteria: []Scenario{
			{Name: "Charge Card", Given: "valid card", When: "submitted", Then: "returns 200 OK"},
		},
		Decisions: []ArchitectureDecision{
			{ID: "ADR-01", Title: "Stripe Tokenization", Context: "PCI compliance", Decision: "Never store raw PAN"},
		},
		RawMarkdown: "# Story Spec: Secure Payment Gateway\n...",
	}

	prov := &StoryProvenance{
		SchemaVersion:      1,
		SpecID:             spec.ID,
		Title:              spec.Title,
		GeneratingUser:     "lead_architect",
		DeliberationMethod: "multi_persona_deliberation",
		DeliberationRounds: 3,
		ModelProvider:      "anthropic",
		ModelName:          "claude-3-5-sonnet-20241022",
		CouncilMembers: []CouncilMemberProvenance{
			{Role: "Product Owner", PersonaID: "product_owner_lead", Name: "Morgan Vance"},
			{Role: "Senior Architect", PersonaID: "senior_software_architect", Name: "Dr. Aris Thorne"},
		},
		ActiveSteeringHashes: []string{"sha256:abc123"},
	}

	specPath, provPath, err := WriteSpecWithProvenance(specsDir, spec, prov)
	if err != nil {
		t.Fatalf("WriteSpecWithProvenance failed: %v", err)
	}

	if _, err := os.Stat(specPath); err != nil {
		t.Errorf("spec file not created at %s", specPath)
	}
	if _, err := os.Stat(provPath); err != nil {
		t.Errorf("provenance sidecar not created at %s", provPath)
	}

	data, err := os.ReadFile(provPath)
	if err != nil {
		t.Fatalf("failed to read provenance sidecar: %v", err)
	}

	var readProv StoryProvenance
	if err := json.Unmarshal(data, &readProv); err != nil {
		t.Fatalf("failed to parse provenance JSON: %v", err)
	}

	if readProv.SchemaVersion != 1 || readProv.SpecID != "STORY-12345" || readProv.DeliberationMethod != "multi_persona_deliberation" {
		t.Errorf("provenance content mismatch: %+v", readProv)
	}
	if readProv.AcceptanceCriteriaCount != 1 || readProv.DecisionsCount != 1 {
		t.Errorf("expected counts 1 and 1, got %d and %d", readProv.AcceptanceCriteriaCount, readProv.DecisionsCount)
	}
}

func TestCouncilDeliberation_PersonaOwnershipAndCritiqueFailure(t *testing.T) {
	tempDir := t.TempDir()
	reg := persona.NewRegistry(tempDir)
	council := NewCouncil(reg)

	var personasUsed = make(map[string]string) // role -> persona.ID
	council.SetDeliberator(func(ctx context.Context, role string, p model.Persona, sys, prompt string) (string, error) {
		personasUsed[role] = p.ID
		if role == "Council Synthesis" {
			// Simulate Round 2 model failure (timeout / rate limit)
			return "", os.ErrDeadlineExceeded
		}
		if role == "Council Synthesizer" {
			draft := DeliberationDraft{
				Title:     "Feature with Failed Critique",
				UserStory: "As an engineer, implement resilient handling.",
				AcceptanceCriteria: []Scenario{
					{Name: "Fallback Flow", Given: "failure", When: "handled", Then: "recovers"},
				},
			}
			b, _ := json.Marshal(draft)
			return string(b), nil
		}
		return "Member input", nil
	})

	spec, err := council.Plan(context.Background(), &PlanningContext{
		StoryPrompt: "Feature with Failed Critique",
		Style:       StyleStandard,
	})
	if err != nil {
		t.Fatalf("Plan failed: %v", err)
	}

	// Verify persona ownership: Round 2 must use senior_software_architect, not product_owner_lead!
	if personasUsed["Council Synthesis"] != "senior_software_architect" {
		t.Errorf("expected Council Synthesis to use senior_software_architect, got %s", personasUsed["Council Synthesis"])
	}
	// Round 3 must use lead_council_synthesizer
	if personasUsed["Council Synthesizer"] != "lead_council_synthesizer" {
		t.Errorf("expected Council Synthesizer to use lead_council_synthesizer, got %s", personasUsed["Council Synthesizer"])
	}

	// Verify Round 2 error is recorded in DeliberationRounds
	if len(spec.DeliberationRounds) < 2 {
		t.Fatalf("expected at least 2 deliberation rounds, got %d", len(spec.DeliberationRounds))
	}
	round2Transcript := spec.DeliberationRounds[1].Transcript
	if !strings.Contains(round2Transcript, "CRITIQUE_FAILED") {
		t.Errorf("expected CRITIQUE_FAILED in Round 2 transcript, got: %q", round2Transcript)
	}
}
