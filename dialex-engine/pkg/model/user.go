package model

// User is one account in the engine's multi-user store. Role is "admin" or "user"; an empty
// role (accounts created before roles existed) counts as admin so upgrades never lock anyone out.
type User struct {
	Username string `json:"username"`
	Role     string `json:"role,omitempty"`
	// bcrypt hash — never the plaintext password. See pkg/store/crypto.go for hashing.
	PasswordHash string `json:"passwordHash"`
}

func (u User) IsAdmin() bool { return u.Role == "" || u.Role == "admin" }
