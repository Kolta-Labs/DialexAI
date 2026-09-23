package model

// User is one account in the engine's multi-user store (round-2 decision: real accounts,
// not one shared credential — see ENGINE_SPEC_REVIEW.md / ROADMAP_AND_OPTIMIZATIONS.md).
// Every account currently has equal access — no role/permission tiers yet.
type User struct {
	Username string `json:"username"`
	// bcrypt hash — never the plaintext password. See pkg/store/crypto.go for hashing.
	PasswordHash string `json:"passwordHash"`
}
