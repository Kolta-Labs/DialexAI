package store

import (
	"os"
	"testing"
)

// Keep tests off the developer's real keychain; keyring_test.go swaps in a fake.
func TestMain(m *testing.M) {
	os.Setenv("DIALEX_KEY_STORAGE", "file")
	os.Exit(m.Run())
}
