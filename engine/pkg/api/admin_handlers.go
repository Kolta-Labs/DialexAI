package api

import (
	"encoding/json"
	"fmt"
	"net/http"
	"runtime"
	"time"

	"dialex/pkg/model"
	"dialex/pkg/store"
)

type adminStatsResponse struct {
	Status           string  `json:"status"`
	Version          string  `json:"version"`
	UptimeSeconds    int64   `json:"uptimeSeconds"`
	ProjectsCount    int     `json:"projectsCount"`
	DiscussionsCount int     `json:"discussionsCount"`
	ActiveRunsCount  int     `json:"activeRunsCount"`
	Goroutines       int     `json:"goroutines"`
	MemoryAllocMB    float64 `json:"memoryAllocMb"`
	GoVersion        string  `json:"goVersion"`
	OS               string  `json:"os"`
}

func (s *Server) handleAdminStats(w http.ResponseWriter, r *http.Request) {
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to load store")
		return
	}

	var mem runtime.MemStats
	runtime.ReadMemStats(&mem)

	s.mu.Lock()
	activeRuns := len(s.runs)
	s.mu.Unlock()

	uptime := int64(time.Since(s.startTime).Seconds())
	if s.startTime.IsZero() {
		uptime = 0
	}

	allocMB := float64(mem.Alloc) / (1024 * 1024)

	stats := adminStatsResponse{
		Status:           "healthy",
		Version:          "1.0.0",
		UptimeSeconds:    uptime,
		ProjectsCount:    len(state.Projects),
		DiscussionsCount: len(state.Discussions),
		ActiveRunsCount:  activeRuns,
		Goroutines:       runtime.NumGoroutine(),
		MemoryAllocMB:    allocMB,
		GoVersion:        runtime.Version(),
		OS:               fmt.Sprintf("%s/%s", runtime.GOOS, runtime.GOARCH),
	}

	writeJSON(w, http.StatusOK, stats)
}

type changePasswordRequest struct {
	CurrentPassword string `json:"currentPassword"`
	NewPassword     string `json:"newPassword"`
}

func (s *Server) handleAdminPassword(w http.ResponseWriter, r *http.Request) {
	username, _ := r.Context().Value(usernameKey).(string)
	if username == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized")
		return
	}

	var req changePasswordRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.NewPassword == "" {
		writeError(w, http.StatusBadRequest, "new password is required")
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not load store")
		return
	}

	userIndex := -1
	for i, u := range state.Users {
		if u.Username == username {
			userIndex = i
			break
		}
	}

	if userIndex == -1 {
		writeError(w, http.StatusNotFound, "user not found")
		return
	}

	// Verify current password if user has an existing password
	if state.Users[userIndex].PasswordHash != "" {
		if req.CurrentPassword == "" || !store.VerifyPassword(state.Users[userIndex].PasswordHash, req.CurrentPassword) {
			writeError(w, http.StatusUnauthorized, "incorrect current password")
			return
		}
	}

	newHash, err := store.HashPassword(req.NewPassword)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to hash password")
		return
	}

	state.Users[userIndex].PasswordHash = newHash
	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save updated password")
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{"status": "password updated successfully"})
}

type pairingResponse struct {
	ServerURL      string `json:"serverUrl"`
	Username       string `json:"username"`
	Token          string `json:"token"`
	PairingPayload string `json:"pairingPayload"`
	ExpiresAt      string `json:"expiresAt"`
}

func (s *Server) handleAdminPairing(w http.ResponseWriter, r *http.Request) {
	username, _ := r.Context().Value(usernameKey).(string)
	if username == "" {
		username = "admin"
	}

	token, err := s.issueToken(username)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to issue pairing token")
		return
	}

	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	host := r.Host
	if host == "" {
		host = "127.0.0.1:7890"
	}

	serverURL := fmt.Sprintf("%s://%s", scheme, host)
	pairingPayload := fmt.Sprintf("dialex://pair?url=%s&user=%s&token=%s", serverURL, username, token)

	resp := pairingResponse{
		ServerURL:      serverURL,
		Username:       username,
		Token:          token,
		PairingPayload: pairingPayload,
		ExpiresAt:      time.Now().Add(tokenLifetime).UTC().Format(time.RFC3339),
	}

	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleAdminLogs(w http.ResponseWriter, r *http.Request) {
	entries := GlobalLogBuffer.Entries()
	writeJSON(w, http.StatusOK, map[string]any{
		"count": len(entries),
		"logs":  entries,
	})
}

func (s *Server) handleListUsers(w http.ResponseWriter, r *http.Request) {
	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not load store")
		return
	}

	users := make([]map[string]string, 0, len(state.Users))
	for _, u := range state.Users {
		users = append(users, map[string]string{
			"username": u.Username,
		})
	}

	writeJSON(w, http.StatusOK, users)
}

type createUserRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (s *Server) handleCreateUser(w http.ResponseWriter, r *http.Request) {
	var req createUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Username == "" || req.Password == "" {
		writeError(w, http.StatusBadRequest, "username and password are required")
		return
	}

	state, err := s.Store.Load()
	if err != nil {
		writeError(w, http.StatusInternalServerError, "could not load store")
		return
	}

	for _, u := range state.Users {
		if u.Username == req.Username {
			writeError(w, http.StatusConflict, fmt.Sprintf("user %q already exists", req.Username))
			return
		}
	}

	hash, err := store.HashPassword(req.Password)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "failed to hash password")
		return
	}

	state.Users = append(state.Users, model.User{
		Username:     req.Username,
		PasswordHash: hash,
	})

	if err := s.Store.Save(state); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save new user")
		return
	}

	writeJSON(w, http.StatusCreated, map[string]string{"username": req.Username})
}
