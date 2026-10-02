package api

import (
	"net"
	"net/http"
	"os"
	"strings"

	"dialex/pkg/model"
)

// Single sign-on through a trusted identity-aware proxy (oauth2-proxy, Tailscale serve,
// Cloudflare Access, Authelia...). Native OIDC is not implemented.
//
//	DIALEX_SSO_USER_HEADER=X-Forwarded-Email   header the proxy sets with the verified identity
//	DIALEX_SSO_TRUSTED_CIDR=127.0.0.1/32       only requests from these addresses are believed
//	DIALEX_SSO_ADMINS=alice@x.com,bob@x.com    identities provisioned as admin (others: user)
//
// Both of the first two must be set. The header is ignored from any other source address, so
// the proxy must be the only path to the engine (bind the engine to loopback or a private
// interface). A first-seen identity is created as a passwordless account.
func (s *Server) proxyUser(r *http.Request) (string, bool) {
	header, cidr := os.Getenv("DIALEX_SSO_USER_HEADER"), os.Getenv("DIALEX_SSO_TRUSTED_CIDR")
	if header == "" || cidr == "" {
		return "", false
	}
	name := strings.TrimSpace(r.Header.Get(header))
	if name == "" {
		return "", false
	}
	_, nets, err := net.ParseCIDR(cidr)
	ip := net.ParseIP(clientIP(r))
	if err != nil || ip == nil || !nets.Contains(ip) {
		return "", false
	}
	s.provisionSSOUser(name)
	return name, true
}

func (s *Server) provisionSSOUser(name string) {
	state, err := s.Store.Load()
	if err != nil {
		return
	}
	for _, u := range state.Users {
		if u.Username == name {
			return
		}
	}
	role := "user"
	for _, a := range strings.Split(os.Getenv("DIALEX_SSO_ADMINS"), ",") {
		if strings.TrimSpace(a) == name {
			role = "admin"
		}
	}
	state.Users = append(state.Users, model.User{Username: name, Role: role})
	_ = s.Store.Save(state)
}
