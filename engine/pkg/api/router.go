package api

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"embed"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

//go:embed web/*
var webFS embed.FS

// Router builds the full route table. Every route except /health, /auth/login, and the Web UI
// requires a valid Bearer token, cookie, or query param.
func (s *Server) Router() http.Handler {
	mux := http.NewServeMux()

	// Web UI
	mux.HandleFunc("GET /{$}", s.handleServeWeb)
	mux.HandleFunc("GET /admin", s.handleServeWeb)

	mux.HandleFunc("GET /health", s.handleHealth)
	mux.HandleFunc("POST /auth/login", s.handleLogin)

	// Admin & Telemetry APIs
	mux.HandleFunc("GET /api/v1/admin/stats", s.handleAdminStats)
	mux.HandleFunc("POST /api/v1/admin/password", s.requireAuth(s.handleAdminPassword))
	mux.HandleFunc("GET /api/v1/admin/pairing", s.requireAuth(s.handleAdminPairing))
	mux.HandleFunc("GET /api/v1/admin/logs", s.requireAuth(s.handleAdminLogs))
	mux.HandleFunc("GET /api/v1/admin/users", s.requireAuth(s.handleListUsers))
	mux.HandleFunc("POST /api/v1/admin/users", s.requireAuth(s.handleCreateUser))

	mux.HandleFunc("GET /projects", s.requireAuth(s.handleListProjects))
	mux.HandleFunc("POST /projects", s.requireAuth(s.handleCreateProject))
	mux.HandleFunc("DELETE /projects/{id}", s.requireAuth(s.handleDeleteProject))

	mux.HandleFunc("GET /debates", s.requireAuth(s.handleListDebates))
	mux.HandleFunc("POST /debates", s.requireAuth(s.handleCreateDebate))
	mux.HandleFunc("GET /debates/{id}", s.requireAuth(s.handleGetDebate))
	mux.HandleFunc("PUT /debates/{id}", s.requireAuth(s.handleUpdateDebate))
	mux.HandleFunc("DELETE /debates/{id}", s.requireAuth(s.handleDeleteDebate))
	mux.HandleFunc("GET /debates/{id}/stream", s.requireAuth(s.handleStream))
	mux.HandleFunc("POST /debates/{id}/start", s.requireAuth(s.handleStartDebate))
	mux.HandleFunc("POST /debates/{id}/resume", s.requireAuth(s.handleResumeDebate))
	mux.HandleFunc("POST /debates/{id}/pause", s.requireAuth(s.handlePause))
	mux.HandleFunc("POST /debates/{id}/stop", s.requireAuth(s.handleHardStop))
	mux.HandleFunc("POST /debates/{id}/handoff", s.requireAuth(s.handleHandoff))
	mux.HandleFunc("POST /debates/{id}/deliverable", s.requireAuth(s.handleDeliverable))
	mux.HandleFunc("POST /debates/{id}/generate-title", s.requireAuth(s.handleGenerateDebateTitle))
	mux.HandleFunc("POST /api/v1/debates/{id}/generate-title", s.requireAuth(s.handleGenerateDebateTitle))
	mux.HandleFunc("POST /debates/ai-setup", s.requireAuth(s.handleAISetup))
	mux.HandleFunc("POST /api/v1/debates/ai-setup", s.requireAuth(s.handleAISetup))
	mux.HandleFunc("POST /debates/decompose", s.requireAuth(s.handleDecomposeProblem))
	mux.HandleFunc("POST /api/v1/discussions/decompose", s.requireAuth(s.handleDecomposeProblem))
	mux.HandleFunc("POST /api/v1/debates/decompose", s.requireAuth(s.handleDecomposeProblem))

	mux.HandleFunc("GET /settings", s.requireAuth(s.handleGetSettings))
	mux.HandleFunc("PUT /settings", s.requireAuth(s.handleUpdateSettings))

	// Personas
	mux.HandleFunc("GET /api/v1/personas", s.requireAuth(s.handleListPersonas))
	mux.HandleFunc("POST /api/v1/personas", s.requireAuth(s.handleCreatePersona))
	mux.HandleFunc("DELETE /api/v1/personas/{id}", s.requireAuth(s.handleDeletePersona))
	mux.HandleFunc("POST /api/v1/personas/import", s.requireAuth(s.handleImportPersonas))
	mux.HandleFunc("GET /api/v1/personas/export", s.requireAuth(s.handleExportPersonas))
	mux.HandleFunc("POST /api/v1/personas/chat", s.requireAuth(s.handleChatPersona))


	// File attachments
	mux.HandleFunc("POST /api/v1/debates/{id}/files", s.requireAuth(s.handleUploadFile))
	mux.HandleFunc("DELETE /api/v1/files/{fileId}", s.requireAuth(s.handleDeleteFile))

	// Usage
	mux.HandleFunc("GET /api/v1/debates/{id}/usage", s.requireAuth(s.handleGetDebateUsage))
	mux.HandleFunc("GET /api/v1/projects/{id}/usage", s.requireAuth(s.handleGetProjectUsage))

	// Dynamic model discovery
	mux.HandleFunc("GET /api/v1/models", s.requireAuth(s.handleListModels))

	// Duplicate discussion
	mux.HandleFunc("POST /api/v1/debates/{id}/duplicate", s.requireAuth(s.handleDuplicateDebate))

	// CLI status on system running engine (environmental capability status like /health)
	mux.HandleFunc("GET /api/v1/cli/status", s.handleCliStatus)
	mux.HandleFunc("GET /api/v1/cli/logins", s.handleCliLogins)

	// Knowledge Graph APIs
	mux.HandleFunc("GET /api/v1/projects/{id}/graph", s.requireAuth(s.handleGetGraph))
	mux.HandleFunc("GET /api/v1/projects/{id}/graph/search", s.requireAuth(s.handleSearchGraph))
	mux.HandleFunc("POST /api/v1/projects/{id}/graph/nodes", s.requireAuth(s.handleUpsertNode))
	mux.HandleFunc("DELETE /api/v1/projects/{id}/graph/nodes/{nodeId}", s.requireAuth(s.handleDeleteNode))
	mux.HandleFunc("POST /api/v1/projects/{id}/graph/edges", s.requireAuth(s.handleUpsertEdge))
	mux.HandleFunc("POST /api/v1/graph/decay", s.requireAuth(s.handleTriggerDecay))

	return mux
}

// ServeOptions controls how ListenAndServe binds and whether it encrypts the connection.
type ServeOptions struct {
	// AllowInsecureLAN must be true to bind to anything other than loopback (Task 3.1.1) —
	// binding to 0.0.0.0 or a public IP over plain HTTP + Bearer tokens exposes API keys
	// and debate data to whoever else is on that network. Loopback (127.0.0.1/::1/
	// localhost) never needs this.
	AllowInsecureLAN bool
	// UseTLS, when true, serves HTTPS. If TLSCertFile/TLSKeyFile are both empty, a
	// self-signed certificate is generated (or reused, if one already exists) in certDir.
	UseTLS      bool
	TLSCertFile string
	TLSKeyFile  string
	CertDir     string
}

// ErrInsecureBindRefused is returned by ListenAndServe when addr isn't loopback and
// AllowInsecureLAN wasn't set.
var ErrInsecureBindRefused = errors.New("refusing to bind to a non-loopback address without --allow-insecure-lan")

// ListenAndServe starts the HTTP(S) server. Blocks until the listener errors or the process
// is killed — callers wanting graceful shutdown should run this in a goroutine and use
// http.Server.Shutdown via a lower-level call instead, if that's ever needed; this is the
// straightforward path the CLI's `serve` command uses.
func (s *Server) ListenAndServe(addr string, opts ServeOptions) error {
	if !isLoopbackAddr(addr) && !opts.AllowInsecureLAN {
		return ErrInsecureBindRefused
	}
	if !isLoopbackAddr(addr) {
		log.Printf("WARNING: binding to %s — reachable from your LAN/VPN, not just this machine. "+
			"Make sure that network is actually trusted (see ENGINE_SPEC_REVIEW.md, auth model).", addr)
	}

	handler := s.Router()
	if !opts.UseTLS {
		return http.ListenAndServe(addr, handler)
	}

	certFile, keyFile := opts.TLSCertFile, opts.TLSKeyFile
	if certFile == "" || keyFile == "" {
		var err error
		certFile, keyFile, err = ensureSelfSignedCert(opts.CertDir)
		if err != nil {
			return fmt.Errorf("could not prepare TLS certificate: %w", err)
		}
	}
	return http.ListenAndServeTLS(addr, certFile, keyFile, handler)
}

func isLoopbackAddr(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		host = addr
	}
	if host == "" {
		return false // ":PORT" means all interfaces
	}
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// ensureSelfSignedCert generates a self-signed ECDSA certificate valid for 1 year, once,
// reusing it on subsequent calls if it's already there.
func ensureSelfSignedCert(dir string) (certFile, keyFile string, err error) {
	if dir == "" {
		dir = "."
	}
	certFile = filepath.Join(dir, "cert.pem")
	keyFile = filepath.Join(dir, "key.pem")
	if _, err := os.Stat(certFile); err == nil {
		if _, err := os.Stat(keyFile); err == nil {
			return certFile, keyFile, nil
		}
	}

	priv, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", "", err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return "", "", err
	}
	template := x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "roundtable-engine"},
		NotBefore:    time.Now(),
		NotAfter:     time.Now().AddDate(1, 0, 0),
		KeyUsage:     x509.KeyUsageKeyEncipherment | x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IsCA:         true,
		DNSNames:     []string{"localhost"},
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1"), net.ParseIP("::1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &priv.PublicKey, priv)
	if err != nil {
		return "", "", err
	}
	certOut, err := os.OpenFile(certFile, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
	if err != nil {
		return "", "", err
	}
	defer certOut.Close()
	if err := pem.Encode(certOut, &pem.Block{Type: "CERTIFICATE", Bytes: der}); err != nil {
		return "", "", err
	}

	keyBytes, err := x509.MarshalECPrivateKey(priv)
	if err != nil {
		return "", "", err
	}
	keyOut, err := os.OpenFile(keyFile, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o600)
	if err != nil {
		return "", "", err
	}
	defer keyOut.Close()
	if err := pem.Encode(keyOut, &pem.Block{Type: "EC PRIVATE KEY", Bytes: keyBytes}); err != nil {
		return "", "", err
	}

	// Sanity check the pair actually loads as a usable tls.Certificate before handing the
	// paths back — cheap insurance against a subtly malformed generation.
	if _, err := tls.LoadX509KeyPair(certFile, keyFile); err != nil {
		return "", "", err
	}
	return certFile, keyFile, nil
}

func (s *Server) handleServeWeb(w http.ResponseWriter, r *http.Request) {
	data, err := webFS.ReadFile("web/index.html")
	if err != nil {
		http.Error(w, "Web UI not available", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

