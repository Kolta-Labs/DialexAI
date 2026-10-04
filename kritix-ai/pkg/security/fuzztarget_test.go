package security

import "testing"

func TestValidateFuzzTarget(t *testing.T) {
	allow := []string{".staging.example.com"}
	if ValidateFuzzTarget("https://api.staging.example.com/x", allow) != nil {
		t.Fatal("staging host should be allowed")
	}
	for _, u := range []string{"https://example.com", "https://evilstaging.example.com", "https://api.staging.example.com.evil.io"} {
		if ValidateFuzzTarget(u, allow) == nil {
			t.Fatalf("%s must be rejected", u)
		}
	}
	if ValidateFuzzTarget("https://api.staging.example.com", nil) == nil {
		t.Fatal("empty allowlist must deny")
	}
}
