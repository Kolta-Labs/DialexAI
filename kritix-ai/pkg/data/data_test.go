package data

import (
	"strings"
	"testing"
)

func TestSyntheticFactoryGeneratesValidTypes(t *testing.T) {
	factory := NewSyntheticFactory()

	email := factory.Generate(TypeEmail)
	if !strings.Contains(email, "@") || !strings.Contains(email, ".") {
		t.Errorf("expected valid synthetic email, got %s", email)
	}

	phone := factory.Generate(TypePhoneNumber)
	if !strings.HasPrefix(phone, "+1-555-") {
		t.Errorf("expected standard mock phone format, got %s", phone)
	}

	name := factory.Generate(TypeFullName)
	if len(strings.Fields(name)) < 2 {
		t.Errorf("expected full name with first and last, got %s", name)
	}

	cc := factory.Generate(TypeCreditCard)
	if !strings.HasPrefix(cc, "4111") || len(cc) != 16 {
		t.Errorf("expected 16-digit test visa starting with 4111, got %s", cc)
	}
}

func TestGenerateBoundaryValues(t *testing.T) {
	factory := NewSyntheticFactory()
	boundaries := factory.GenerateBoundaryValues()

	if len(boundaries) < 15 {
		t.Errorf("expected at least 15 edge case boundaries, got %d", len(boundaries))
	}

	// Verify crucial boundaries exist
	hasEmpty := false
	hasXSS := false
	hasSQLi := false
	for _, b := range boundaries {
		if b == "" {
			hasEmpty = true
		}
		if strings.Contains(b, "<script>") {
			hasXSS = true
		}
		if strings.Contains(b, "' OR '1'='1") {
			hasSQLi = true
		}
	}

	if !hasEmpty || !hasXSS || !hasSQLi {
		t.Errorf("missing critical edge case boundaries: empty=%v, xss=%v, sqli=%v", hasEmpty, hasXSS, hasSQLi)
	}
}

func TestAnonymizePII(t *testing.T) {
	factory := NewSyntheticFactory()
	input := "User john.doe@realcorp.com placed order with card 4111-2222-3333-4444"
	anonymized := factory.AnonymizePII(input)

	if strings.Contains(anonymized, "john.doe@realcorp.com") {
		t.Errorf("PII email was not scrubbed: %s", anonymized)
	}
	if strings.Contains(anonymized, "4111-2222-3333-4444") {
		t.Errorf("PII credit card was not masked: %s", anonymized)
	}
	if !strings.Contains(anonymized, "anon-user@kritix.internal") {
		t.Errorf("synthetic email placeholder missing: %s", anonymized)
	}
}
