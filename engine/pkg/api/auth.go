package api

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"dialex/pkg/model"
	"dialex/pkg/store"
)

// Minimal HS256 JWT — issued by POST /auth/login, checked as a Bearer token on every other
// route. Hand-rolled instead of a dependency: three base64url segments (header.payload.sig),
// stdlib crypto/hmac + crypto/sha256 only (Task 3.1.2 — upgrading from raw Basic Auth per
// request, which was replayable, to a short-lived signed token).

type jwtClaims struct {
	Sub string `json:"sub"` // username
	Exp int64  `json:"exp"` // unix seconds
}

const tokenLifetime = 24 * time.Hour

func base64url(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

func (s *Server) issueToken(username string) (string, error) {
	header := base64url([]byte(`{"alg":"HS256","typ":"JWT"}`))
	claims, err := json.Marshal(jwtClaims{Sub: username, Exp: time.Now().Add(tokenLifetime).Unix()})
	if err != nil {
		return "", err
	}
	payload := base64url(claims)
	signingInput := header + "." + payload
	sig := s.sign(signingInput)
	return signingInput + "." + base64url(sig), nil
}

func (s *Server) sign(input string) []byte {
	mac := hmac.New(sha256.New, s.jwtSecret)
	mac.Write([]byte(input))
	return mac.Sum(nil)
}

// verifyToken checks the signature and expiry and returns the username it was issued for.
func (s *Server) verifyToken(token string) (string, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return "", errors.New("malformed token")
	}
	signingInput := parts[0] + "." + parts[1]
	wantSig := s.sign(signingInput)
	gotSig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || !hmac.Equal(wantSig, gotSig) {
		return "", errors.New("invalid signature")
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return "", errors.New("invalid payload")
	}
	var claims jwtClaims
	if err := json.Unmarshal(payload, &claims); err != nil {
		return "", errors.New("invalid claims")
	}
	if time.Now().Unix() > claims.Exp {
		return "", errors.New("token expired")
	}
	return claims.Sub, nil
}

// randomSecret generates a fresh random HMAC key — used when the caller doesn't supply one
// (e.g. a single-process run where tokens never need to survive a restart).
func randomSecret() []byte {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return b
}

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type loginResponse struct {
	Token string `json:"token"`
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not load account store")
		return
	}
	for _, u := range state.Users {
		// subtle.ConstantTimeCompare on the username too — avoids a timing side-channel
		// that could distinguish "wrong username" from "wrong password" by response time.
		if subtle.ConstantTimeCompare([]byte(u.Username), []byte(req.Username)) == 1 {
			if !store.VerifyPassword(u.PasswordHash, req.Password) {
				break
			}
			token, err := s.issueToken(u.Username)
			if err != nil {
				writeError(w, http.StatusInternalServerError, "could not issue token")
				return
			}
			writeJSON(w, http.StatusOK, loginResponse{Token: token})
			return
		}
	}
	writeError(w, http.StatusUnauthorized, "invalid username or password")
}

// requireAuth wraps a handler so it 401s without a valid, non-expired Bearer token, cookie, or query param.
func (s *Server) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := extractToken(r)
		if token == "" {
			writeError(w, http.StatusUnauthorized, "missing bearer token")
			return
		}
		username, err := s.verifyToken(token)
		if err != nil {
			writeError(w, http.StatusUnauthorized, fmt.Sprintf("invalid token: %v", err))
			return
		}
		r = r.WithContext(withUsername(r.Context(), username))
		next(w, r)
	}
}

func extractToken(r *http.Request) string {
	// 1. Authorization: Bearer <token>
	authHeader := r.Header.Get("Authorization")
	if token, ok := strings.CutPrefix(authHeader, "Bearer "); ok && token != "" {
		return strings.TrimSpace(token)
	}
	// 2. Cookie: dialex_token
	if cookie, err := r.Cookie("dialex_token"); err == nil && cookie.Value != "" {
		return strings.TrimSpace(cookie.Value)
	}
	// 3. Query: ?token=
	if qToken := r.URL.Query().Get("token"); qToken != "" {
		return strings.TrimSpace(qToken)
	}
	return ""
}

// CreateFirstUser is what the CLI wizard's first run (or `roundtable users add`) calls to
// seed the account store — every account is currently equal, no role tiers (round-2
// decision).
func CreateFirstUser(s *store.Store, username, password string) (model.User, error) {
	hash, err := store.HashPassword(password)
	if err != nil {
		return model.User{}, err
	}
	user := model.User{Username: username, PasswordHash: hash}
	state, err := s.Load()
	if err != nil {
		return model.User{}, err
	}
	for _, u := range state.Users {
		if u.Username == username {
			return model.User{}, fmt.Errorf("user %q already exists", username)
		}
	}
	state.Users = append(state.Users, user)
	if err := s.Save(state); err != nil {
		return model.User{}, err
	}
	return user, nil
}
