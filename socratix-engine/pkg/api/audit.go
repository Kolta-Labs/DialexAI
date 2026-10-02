package api

import (
	"bufio"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Audit log: an append-only JSON-lines file (audit.log in the data dir) of logins and every
// state-changing request. Export it with GET /api/v1/admin/audit?limit=N (admin only).
//
// ponytail: plain file, no rotation, no tamper-evidence (anyone with file access can edit it).
// Ship it to a log collector or WORM storage if you need that.
type auditEntry struct {
	Time   string `json:"time"`
	User   string `json:"user"`
	Event  string `json:"event"`
	Method string `json:"method,omitempty"`
	Path   string `json:"path,omitempty"`
	Status int    `json:"status,omitempty"`
	IP     string `json:"ip,omitempty"`
}

func (s *Server) audit(user, event, _ string, r *http.Request, status int) {
	if s.auditPath == "" {
		return
	}
	e := auditEntry{Time: time.Now().UTC().Format(time.RFC3339), User: user, Event: event, Status: status}
	if r != nil {
		e.Method, e.Path, e.IP = r.Method, r.URL.Path, clientIP(r)
	}
	line, _ := json.Marshal(e)
	s.auditMu.Lock()
	defer s.auditMu.Unlock()
	f, err := os.OpenFile(s.auditPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return
	}
	defer f.Close()
	f.Write(append(line, '\n'))
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(c int) { r.status = c; r.ResponseWriter.WriteHeader(c) }
func (r *statusRecorder) Flush() {
	if f, ok := r.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// auditMutations records every POST/PUT/PATCH/DELETE except login (logged by the handler).
func (s *Server) auditMutations(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet || r.Method == http.MethodHead || r.Method == http.MethodOptions || r.URL.Path == "/auth/login" {
			next.ServeHTTP(w, r)
			return
		}
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		user := "-"
		if name, ok := s.proxyUser(r); ok {
			user = name
		} else if name, err := s.verifyToken(extractToken(r)); err == nil {
			user = name
		}
		s.audit(user, "request", "", r, rec.status)
	})
}

func (s *Server) handleAdminAudit(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 10000 {
		limit = 500
	}
	entries := []json.RawMessage{}
	if f, err := os.Open(s.auditPath); err == nil {
		defer f.Close()
		sc := bufio.NewScanner(f)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			entries = append(entries, json.RawMessage(append([]byte(nil), sc.Bytes()...)))
			if len(entries) > limit {
				entries = entries[1:]
			}
		}
	}
	writeJSON(w, http.StatusOK, entries)
}

// requireAdmin is requireAuth plus an admin-role check.
func (s *Server) requireAdmin(next http.HandlerFunc) http.HandlerFunc {
	return s.requireAuth(func(w http.ResponseWriter, r *http.Request) {
		name, _ := r.Context().Value(usernameKey).(string)
		state, err := s.Store.Load()
		if err != nil {
			writeError(w, http.StatusInternalServerError, "could not load account store")
			return
		}
		for _, u := range state.Users {
			if u.Username == name && u.IsAdmin() {
				next(w, r)
				return
			}
		}
		writeError(w, http.StatusForbidden, "admin role required")
	})
}

func clientIP(r *http.Request) string {
	h, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return h
}

// loginLimiter blocks a username after 5 failures, or an IP after 20, within 15 minutes.
//
// ponytail: in memory, per process; the IP bucket is useless behind a shared proxy IP.
type loginLimiter struct {
	mu    sync.Mutex
	fails map[string][]time.Time
}

const (
	loginWindow  = 15 * time.Minute
	userMaxFails = 5
	ipMaxFails   = 20
)

func (l *loginLimiter) count(key string, now time.Time) int {
	kept := l.fails[key][:0]
	for _, t := range l.fails[key] {
		if now.Sub(t) < loginWindow {
			kept = append(kept, t)
		}
	}
	if len(kept) == 0 {
		delete(l.fails, key)
		return 0
	}
	l.fails[key] = kept
	return len(kept)
}

func (l *loginLimiter) blocked(user, ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	return l.count("u:"+strings.ToLower(user), now) >= userMaxFails || l.count("i:"+ip, now) >= ipMaxFails
}

func (l *loginLimiter) fail(user, ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.fails == nil {
		l.fails = map[string][]time.Time{}
	}
	now := time.Now()
	for _, k := range []string{"u:" + strings.ToLower(user), "i:" + ip} {
		l.fails[k] = append(l.fails[k], now)
	}
}

func (l *loginLimiter) reset(user, ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.fails, "u:"+strings.ToLower(user))
}
