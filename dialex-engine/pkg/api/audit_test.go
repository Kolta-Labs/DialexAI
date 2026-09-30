package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"dialex/pkg/model"
)

func do(t *testing.T, method, url, token string, body any) *http.Response {
	t.Helper()
	req := authedRequest(t, method, url, token, mustJSON(t, body))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp
}

func login(t *testing.T, base, user, pass string) (int, string) {
	t.Helper()
	resp, err := http.Post(base+"/auth/login", "application/json", mustJSON(t, loginRequest{Username: user, Password: pass}))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var b loginResponse
	_ = jsonDecode(resp, &b)
	return resp.StatusCode, b.Token
}

func TestRolesGateAdminRoutesAndAuditRecordsActions(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	admin := loginAndGetToken(t, srv, s, st)

	if r := do(t, "POST", srv.URL+"/api/v1/admin/users", admin, createUserRequest{Username: "bob", Password: "pw123456"}); r.StatusCode != 201 && r.StatusCode != 200 {
		t.Fatalf("admin create user = %d", r.StatusCode)
	}
	state, _ := st.Load()
	for _, u := range state.Users {
		if u.Username == "bob" && u.Role != "user" {
			t.Fatalf("new users default to role user, got %q", u.Role)
		}
	}
	_, bob := login(t, srv.URL, "bob", "pw123456")
	if r := do(t, "GET", srv.URL+"/api/v1/admin/users", bob, nil); r.StatusCode != http.StatusForbidden {
		t.Fatalf("non-admin list users = %d, want 403", r.StatusCode)
	}
	if r := do(t, "POST", srv.URL+"/api/v1/admin/users", bob, createUserRequest{Username: "eve", Password: "pw123456"}); r.StatusCode != http.StatusForbidden {
		t.Fatalf("non-admin create user = %d, want 403", r.StatusCode)
	}
	if r := do(t, "GET", srv.URL+"/api/v1/admin/audit", bob, nil); r.StatusCode != http.StatusForbidden {
		t.Fatalf("non-admin audit = %d, want 403", r.StatusCode)
	}

	resp := do(t, "GET", srv.URL+"/api/v1/admin/audit", admin, nil)
	var entries []auditEntry
	if err := jsonDecode(resp, &entries); err != nil {
		t.Fatal(err)
	}
	var sawLogin, sawCreate bool
	for _, e := range entries {
		sawLogin = sawLogin || (e.Event == "login.ok" && e.User == "bob")
		sawCreate = sawCreate || (e.Event == "request" && e.User == "admin" && strings.HasSuffix(e.Path, "/admin/users"))
	}
	if !sawLogin || !sawCreate {
		t.Fatalf("audit missing events (login=%v create=%v): %+v", sawLogin, sawCreate, entries)
	}
	if fi, err := os.Stat(s.auditPath); err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("audit file should be 0600: %v %v", fi, err)
	}
}

func TestLegacyUserWithoutRoleIsAdmin(t *testing.T) {
	if !(model.User{}).IsAdmin() || (model.User{Role: "user"}).IsAdmin() {
		t.Fatal("empty role must be admin; explicit user must not")
	}
}

func TestLoginLockoutAfterRepeatedFailures(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	loginAndGetToken(t, srv, s, st)
	for i := 0; i < userMaxFails; i++ {
		if code, _ := login(t, srv.URL, "admin", "wrong"); code != http.StatusUnauthorized {
			t.Fatalf("attempt %d = %d, want 401", i, code)
		}
	}
	if code, _ := login(t, srv.URL, "admin", "hunter22"); code != http.StatusTooManyRequests {
		t.Fatalf("after lockout even the right password must be refused, got %d", code)
	}
}

func TestProxySSO(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	get := func(header string) int {
		req, _ := http.NewRequest("GET", srv.URL+"/debates", nil)
		if header != "" {
			req.Header.Set("X-Forwarded-Email", header)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if get("a@x.com") != http.StatusUnauthorized {
		t.Fatal("header must be ignored unless SSO env is configured")
	}
	t.Setenv("DIALEX_SSO_USER_HEADER", "X-Forwarded-Email")
	t.Setenv("DIALEX_SSO_TRUSTED_CIDR", "10.9.9.0/24") // test client is 127.0.0.1: not trusted
	if get("a@x.com") != http.StatusUnauthorized {
		t.Fatal("header from an untrusted address must be ignored")
	}
	t.Setenv("DIALEX_SSO_TRUSTED_CIDR", "127.0.0.0/8")
	t.Setenv("DIALEX_SSO_ADMINS", "boss@x.com")
	if get("a@x.com") != http.StatusOK {
		t.Fatal("trusted proxy identity should authenticate")
	}
	if get("") != http.StatusUnauthorized {
		t.Fatal("no header and no token must still be 401")
	}
	get("boss@x.com")
	state, _ := s.Store.Load()
	roles := map[string]string{}
	for _, u := range state.Users {
		roles[u.Username] = u.Role
	}
	if roles["a@x.com"] != "user" || roles["boss@x.com"] != "admin" {
		t.Fatalf("provisioned roles wrong: %v", roles)
	}
}

func jsonDecode(r *http.Response, v any) error { return json.NewDecoder(r.Body).Decode(v) }
