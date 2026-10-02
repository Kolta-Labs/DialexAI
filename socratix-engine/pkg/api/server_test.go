package api

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"socratix/pkg/model"
	"socratix/pkg/runner"
	"socratix/pkg/store"
)

// fakeRunner is a fast, in-memory AgentRunner — no network/process involved, same pattern
// the orchestrator package's own tests use.
type fakeRunner struct {
	respond func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error)
}

func (f *fakeRunner) Respond(ctx context.Context, agent model.Agent, topic, commonContext, commonInstructions string, transcript []model.DebateMessage, modelOverride string) (runner.AgentReply, error) {
	if f.respond != nil {
		return f.respond(agent, transcript)
	}
	return runner.AgentReply{Content: fmt.Sprintf("%s-turn-%d", agent.Provider, len(transcript))}, nil
}

func newTestServer(t *testing.T) (*Server, *store.Store) {
	t.Helper()
	st, err := store.New(t.TempDir())
	if err != nil {
		t.Fatalf("store.New() error = %v", err)
	}
	s := NewServer(st)
	s.RunnerFor = func(model.Agent) runner.AgentRunner { return &fakeRunner{} }
	return s, st
}

func mustJSON(t *testing.T, v any) *bytes.Reader {
	t.Helper()
	data, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	return bytes.NewReader(data)
}

func loginAndGetToken(t *testing.T, srv *httptest.Server, s *Server, st *store.Store) string {
	t.Helper()
	if _, err := CreateFirstUser(st, "admin", "hunter22"); err != nil {
		t.Fatalf("CreateFirstUser() error = %v", err)
	}
	resp, err := http.Post(srv.URL+"/auth/login", "application/json", mustJSON(t, loginRequest{Username: "admin", Password: "hunter22"}))
	if err != nil {
		t.Fatalf("login request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("login status = %d, want 200", resp.StatusCode)
	}
	var body loginResponse
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode login response: %v", err)
	}
	if body.Token == "" {
		t.Fatal("login returned an empty token")
	}
	return body.Token
}

func authedRequest(t *testing.T, method, url, token string, body *bytes.Reader) *http.Request {
	t.Helper()
	var r io.Reader
	if body != nil {
		r = body
	}
	req, err := http.NewRequest(method, url, r)
	if err != nil {
		t.Fatalf("NewRequest: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	return req
}

// A real bug: a discussion left RUNNING on disk (the process was killed, or crashed, mid-run
// — there's no clean-shutdown path that would have paused it first) stayed RUNNING forever
// after a restart, even though the in-memory bookkeeping (runs map, SSE subscribers) that
// would make it actually resumable never survives a restart to begin with. A client had no
// way to tell "genuinely live" apart from "stuck", Pause/Hard Stop had nothing to act on, and
// Resume refused because the discussion "looked" already running.
func TestNewServerSanitizesStaleRunningDiscussionsToPaused(t *testing.T) {
	st, err := store.New(t.TempDir())
	if err != nil {
		t.Fatalf("store.New() error = %v", err)
	}
	seeded := model.NewAppState()
	seeded.Discussions = []model.Discussion{
		{ID: "d1", ProjectID: "p1", Name: "Stuck mid-run", Status: model.DiscussionRunning},
		{ID: "d2", ProjectID: "p1", Name: "Actually done", Status: model.DiscussionDone},
	}
	if err := st.Save(seeded); err != nil {
		t.Fatalf("Save() error = %v", err)
	}

	NewServer(st) // the sanitization under test happens as a side effect of construction

	loaded, err := st.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	byID := map[string]model.DiscussionStatus{}
	for _, d := range loaded.Discussions {
		byID[d.ID] = d.Status
	}
	if byID["d1"] != model.DiscussionPaused {
		t.Errorf("stale RUNNING discussion status = %s, want PAUSED", byID["d1"])
	}
	if byID["d2"] != model.DiscussionDone {
		t.Errorf("a genuinely DONE discussion's status changed to %s, should be left alone", byID["d2"])
	}
}

func TestHealthNeedsNoAuth(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/health")
	if err != nil {
		t.Fatalf("GET /health: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Errorf("status = %d, want 200", resp.StatusCode)
	}
}

func TestLoginRejectsWrongPassword(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	if _, err := CreateFirstUser(st, "admin", "correct-password"); err != nil {
		t.Fatalf("CreateFirstUser() error = %v", err)
	}
	resp, err := http.Post(srv.URL+"/auth/login", "application/json", mustJSON(t, loginRequest{Username: "admin", Password: "wrong"}))
	if err != nil {
		t.Fatalf("login request: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", resp.StatusCode)
	}
}

func TestProtectedRoutesRejectMissingOrBadToken(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	resp, _ := http.Get(srv.URL + "/debates")
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("no-token status = %d, want 401", resp.StatusCode)
	}

	req, _ := http.NewRequest("GET", srv.URL+"/debates", nil)
	req.Header.Set("Authorization", "Bearer not-a-real-token")
	resp2, _ := http.DefaultClient.Do(req)
	if resp2.StatusCode != http.StatusUnauthorized {
		t.Errorf("bad-token status = %d, want 401", resp2.StatusCode)
	}
}

func TestCreateAndListProjectsAndDebates(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	projReq := authedRequest(t, "POST", srv.URL+"/projects", token, mustJSON(t, model.Project{Name: "Test Project"}))
	projResp, err := client.Do(projReq)
	if err != nil || projResp.StatusCode != http.StatusCreated {
		t.Fatalf("create project: err=%v status=%d", err, projResp.StatusCode)
	}
	var project model.Project
	json.NewDecoder(projResp.Body).Decode(&project)
	if project.ID == "" {
		t.Fatal("created project has no ID")
	}

	config := model.DebateConfig{Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"), RoundMode: model.RoundModeFixed, MaxRounds: 1}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{ProjectID: project.ID, Name: "D1", Config: config}))
	discResp, err := client.Do(discReq)
	if err != nil || discResp.StatusCode != http.StatusCreated {
		t.Fatalf("create debate: err=%v status=%d", err, discResp.StatusCode)
	}
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)
	if discussion.ID == "" || discussion.Status != model.DiscussionDraft {
		t.Fatalf("created discussion = %+v, want an ID and DRAFT status", discussion)
	}

	listReq := authedRequest(t, "GET", srv.URL+"/debates", token, nil)
	listResp, _ := client.Do(listReq)
	var list []model.Discussion
	json.NewDecoder(listResp.Body).Decode(&list)
	if len(list) != 1 || list[0].ID != discussion.ID {
		t.Errorf("list = %+v, want exactly the discussion just created", list)
	}
}

func TestSettingsNeverRoundTripApiKeyValues(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	key := model.ApiKeys{Anthropic: "sk-ant-super-secret"}
	putReq := authedRequest(t, "PUT", srv.URL+"/settings", token, mustJSON(t, settingsUpdateRequest{ApiKeys: &key}))
	putResp, err := client.Do(putReq)
	if err != nil || putResp.StatusCode != http.StatusNoContent {
		t.Fatalf("PUT /settings: err=%v status=%d", err, putResp.StatusCode)
	}

	getReq := authedRequest(t, "GET", srv.URL+"/settings", token, nil)
	getResp, _ := client.Do(getReq)
	body, _ := io.ReadAll(getResp.Body)
	if strings.Contains(string(body), "sk-ant-super-secret") {
		t.Error("GET /settings must never echo back the actual key value")
	}
	var parsed map[string]any
	json.Unmarshal(body, &parsed)
	configured, _ := parsed["apiKeysConfigured"].(map[string]any)
	if configured["anthropic"] != true {
		t.Errorf("apiKeysConfigured.anthropic = %v, want true", configured["anthropic"])
	}

	// The underlying store really did save it (encrypted) — confirm via a fresh Load().
	saved, err := st.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if saved.ApiKeys.Anthropic != "sk-ant-super-secret" {
		t.Errorf("stored key = %q, want it actually persisted", saved.ApiKeys.Anthropic)
	}
}

func TestStartDebateStreamsLiveTurnsThenCompletes(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{
		Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"),
		Secondary: agentPtr(model.NewAgent(model.ProviderGemini, "gemini-x")),
		RoundMode: model.RoundModeFixed, MaxRounds: 3,
	}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "D1", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	startReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/start", token, nil)
	startResp, err := client.Do(startReq)
	if err != nil || startResp.StatusCode != http.StatusAccepted {
		t.Fatalf("start: err=%v status=%d", err, startResp.StatusCode)
	}

	streamReq := authedRequest(t, "GET", srv.URL+"/debates/"+discussion.ID+"/stream", token, nil)
	streamCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	streamReq = streamReq.WithContext(streamCtx)
	streamResp, err := client.Do(streamReq)
	if err != nil {
		t.Fatalf("stream request: %v", err)
	}
	defer streamResp.Body.Close()

	scanner := bufio.NewScanner(streamResp.Body)
	events := 0
	for scanner.Scan() {
		line := scanner.Text()
		if strings.HasPrefix(line, "event: turn") {
			events++
		}
		if events >= 2 {
			break
		}
	}
	if events < 2 {
		t.Fatalf("received %d turn events over SSE, want at least 2 (one per agent)", events)
	}

	// Give the background goroutine a moment to persist the final DONE status.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		final, err := st.Load()
		if err == nil {
			for _, d := range final.Discussions {
				if d.ID == discussion.ID && (d.Status == model.DiscussionDone || d.Status == model.DiscussionCompleted) {
					return // success
				}
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("discussion never reached DONE status after streaming completed")
}

// The stream must close on its own once the run finishes — not hang until the client gives
// up. A prior version of handleStream had no way to learn a run had ended, so it blocked
// forever on the subscriber channel; this asserts scanner.Scan() actually returns false
// (a real EOF from the server closing the response) within a bounded time, not merely that
// a client-side context deadline eventually fires.
func TestStreamClosesOnceTheRunCompletes(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"), RoundMode: model.RoundModeFixed, MaxRounds: 1}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "D1", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	startReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/start", token, nil)
	client.Do(startReq)

	streamReq := authedRequest(t, "GET", srv.URL+"/debates/"+discussion.ID+"/stream", token, nil)
	// Deliberately no client-side timeout here — a hang would block forever if the server
	// doesn't close the stream itself, which is exactly the bug this guards against. The
	// test harness's own timeout is the real backstop.
	streamResp, err := client.Do(streamReq)
	if err != nil {
		t.Fatalf("stream request: %v", err)
	}
	defer streamResp.Body.Close()

	scanner := bufio.NewScanner(streamResp.Body)
	for scanner.Scan() {
		// drain everything until the server closes the response
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("scanner error = %v, want a clean EOF", err)
	}
}

func TestHardStopCancelsAQuicklyRunningDebate(t *testing.T) {
	s, st := newTestServer(t)
	started := make(chan struct{}, 1)
	blockUntilCancelled := make(chan struct{})
	s.RunnerFor = func(model.Agent) runner.AgentRunner {
		return &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error) {
			select {
			case started <- struct{}{}:
			default:
			}
			<-blockUntilCancelled
			return runner.AgentReply{}, context.Canceled
		}}
	}
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"), RoundMode: model.RoundModeFixed, MaxRounds: 1}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "D1", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	startReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/start", token, nil)
	client.Do(startReq)

	select {
	case <-started:
	case <-time.After(2 * time.Second):
		t.Fatal("the fake runner never started")
	}

	stopReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/stop", token, nil)
	stopResp, err := client.Do(stopReq)
	if err != nil || stopResp.StatusCode != http.StatusOK {
		t.Fatalf("stop: err=%v status=%d", err, stopResp.StatusCode)
	}
	close(blockUntilCancelled)

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		s.mu.Lock()
		_, running := s.runs[discussion.ID]
		s.mu.Unlock()
		if !running {
			return // success — the run controller cleaned itself up
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("run controller was never cleaned up after hard stop")
}

func TestIsLoopbackAddr(t *testing.T) {
	cases := map[string]bool{
		"127.0.0.1:8080":   true,
		"localhost:8080":   true,
		"[::1]:8080":       true,
		"0.0.0.0:8080":     false,
		"192.168.1.5:8080": false,
		":8080":            false,
	}
	for addr, want := range cases {
		if got := isLoopbackAddr(addr); got != want {
			t.Errorf("isLoopbackAddr(%q) = %v, want %v", addr, got, want)
		}
	}
}

func TestListenAndServeRefusesNonLoopbackWithoutFlag(t *testing.T) {
	s, _ := newTestServer(t)
	err := s.ListenAndServe("192.168.1.5:0", ServeOptions{})
	if err != ErrInsecureBindRefused {
		t.Errorf("error = %v, want ErrInsecureBindRefused", err)
	}
}

func TestSelfSignedCertIsGeneratedAndReused(t *testing.T) {
	dir := t.TempDir()
	cert1, key1, err := ensureSelfSignedCert(dir)
	if err != nil {
		t.Fatalf("ensureSelfSignedCert() error = %v", err)
	}
	cert2, key2, err := ensureSelfSignedCert(dir)
	if err != nil {
		t.Fatalf("ensureSelfSignedCert() second call error = %v", err)
	}
	if cert1 != cert2 || key1 != key2 {
		t.Errorf("paths changed between calls: (%s,%s) vs (%s,%s)", cert1, key1, cert2, key2)
	}
}

func TestUpdateAndDeleteDebate(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{Topic: "original", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"), RoundMode: model.RoundModeFixed, MaxRounds: 1}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "D1", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	discussion.Config.Topic = "edited"
	putReq := authedRequest(t, "PUT", srv.URL+"/debates/"+discussion.ID, token, mustJSON(t, discussion))
	putResp, err := client.Do(putReq)
	if err != nil || putResp.StatusCode != http.StatusOK {
		t.Fatalf("update debate: err=%v status=%d", err, putResp.StatusCode)
	}

	getReq := authedRequest(t, "GET", srv.URL+"/debates/"+discussion.ID, token, nil)
	getResp, _ := client.Do(getReq)
	var updated model.Discussion
	json.NewDecoder(getResp.Body).Decode(&updated)
	if updated.Config.Topic != "edited" {
		t.Errorf("Config.Topic = %q, want edited", updated.Config.Topic)
	}

	delReq := authedRequest(t, "DELETE", srv.URL+"/debates/"+discussion.ID, token, nil)
	delResp, err := client.Do(delReq)
	if err != nil || delResp.StatusCode != http.StatusNoContent {
		t.Fatalf("delete debate: err=%v status=%d", err, delResp.StatusCode)
	}

	getReq2 := authedRequest(t, "GET", srv.URL+"/debates/"+discussion.ID, token, nil)
	getResp2, _ := client.Do(getReq2)
	if getResp2.StatusCode != http.StatusNotFound {
		t.Errorf("GET after delete status = %d, want 404", getResp2.StatusCode)
	}
}

func TestDeleteProjectCascadesToItsDiscussions(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	projReq := authedRequest(t, "POST", srv.URL+"/projects", token, mustJSON(t, model.Project{Name: "P1"}))
	projResp, _ := client.Do(projReq)
	var project model.Project
	json.NewDecoder(projResp.Body).Decode(&project)

	config := model.DebateConfig{Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x")}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{ProjectID: project.ID, Name: "D1", Config: config}))
	client.Do(discReq)

	delReq := authedRequest(t, "DELETE", srv.URL+"/projects/"+project.ID, token, nil)
	delResp, err := client.Do(delReq)
	if err != nil || delResp.StatusCode != http.StatusNoContent {
		t.Fatalf("delete project: err=%v status=%d", err, delResp.StatusCode)
	}

	listReq := authedRequest(t, "GET", srv.URL+"/debates", token, nil)
	listResp, _ := client.Do(listReq)
	var list []model.Discussion
	json.NewDecoder(listResp.Body).Decode(&list)
	if len(list) != 0 {
		t.Errorf("discussions after cascade delete = %+v, want none left", list)
	}
}

func TestHandoffPromptIsGeneratedAndPersisted(t *testing.T) {
	s, st := newTestServer(t)
	s.RunnerFor = func(model.Agent) runner.AgentRunner {
		return &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error) {
			return runner.AgentReply{Content: "handoff prompt text"}, nil
		}}
	}
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x")}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "D1", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	handoffReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/handoff", token, nil)
	handoffResp, err := client.Do(handoffReq)
	if err != nil || handoffResp.StatusCode != http.StatusOK {
		t.Fatalf("handoff: err=%v status=%d", err, handoffResp.StatusCode)
	}
	var body handoffResponse
	json.NewDecoder(handoffResp.Body).Decode(&body)
	if body.Prompt != "handoff prompt text" {
		t.Errorf("Prompt = %q, want handoff prompt text", body.Prompt)
	}

	final, _ := st.Load()
	if len(final.Discussions) != 1 || final.Discussions[0].HandoffPrompt == nil || *final.Discussions[0].HandoffPrompt != "handoff prompt text" {
		t.Errorf("persisted HandoffPrompt not set correctly: %+v", final.Discussions)
	}
}

func TestDeliverableEndpoint(t *testing.T) {
	s, st := newTestServer(t)
	s.RunnerFor = func(model.Agent) runner.AgentRunner {
		return &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error) {
			return runner.AgentReply{Content: "# Action Plan\n\n1. Step one"}, nil
		}}
	}
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"), RoundMode: model.RoundModeFixed, MaxRounds: 1}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "D1", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	delReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/deliverable", token, mustJSON(t, deliverableRequest{Format: "action_plan"}))
	delResp, err := client.Do(delReq)
	if err != nil || delResp.StatusCode != http.StatusOK {
		t.Fatalf("deliverable: err=%v status=%d", err, delResp.StatusCode)
	}
	var body deliverableResponse
	json.NewDecoder(delResp.Body).Decode(&body)
	if body.Content != "# Action Plan\n\n1. Step one" {
		t.Errorf("Content = %q, want '# Action Plan\\n\\n1. Step one'", body.Content)
	}
}

func TestRenameDebateWhileRunning(t *testing.T) {
	s, st := newTestServer(t)
	turnHold := make(chan struct{})
	s.RunnerFor = func(model.Agent) runner.AgentRunner {
		return &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error) {
			<-turnHold
			return runner.AgentReply{Content: "Turn reply"}, nil
		}}
	}
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{Topic: "t", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"), RoundMode: model.RoundModeFixed, MaxRounds: 1}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "Old Name", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	startReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/start", token, nil)
	startResp, err := client.Do(startReq)
	if err != nil || startResp.StatusCode != http.StatusAccepted {
		t.Fatalf("start: err=%v status=%d", err, startResp.StatusCode)
	}

	// While the debate is in-flight, update its name
	updateReq := authedRequest(t, "PUT", srv.URL+"/debates/"+discussion.ID, token, mustJSON(t, model.Discussion{Name: "Renamed Mid-Flight"}))
	updateResp, err := client.Do(updateReq)
	if err != nil || updateResp.StatusCode != http.StatusOK {
		t.Fatalf("update mid-flight: err=%v status=%d", err, updateResp.StatusCode)
	}

	// Release turn
	close(turnHold)

	// Wait for completion
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		final, err := st.Load()
		if err == nil {
			for _, d := range final.Discussions {
				if d.ID == discussion.ID && (d.Status == model.DiscussionDone || d.Status == model.DiscussionCompleted) {
					if d.Name != "Renamed Mid-Flight" {
						t.Errorf("Discussion name after run = %q, want 'Renamed Mid-Flight'", d.Name)
					}
					return // success
				}
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("discussion never reached DONE status")
}

func agentPtr(a model.Agent) *model.Agent { return &a }

func TestInjectUserCommentWhileRunning(t *testing.T) {
	s, st := newTestServer(t)
	turnHold := make(chan struct{})
	s.RunnerFor = func(model.Agent) runner.AgentRunner {
		return &fakeRunner{respond: func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error) {
			<-turnHold
			return runner.AgentReply{Content: "Turn reply"}, nil
		}}
	}
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)
	client := http.DefaultClient

	config := model.DebateConfig{Topic: "Topic", Primary: model.NewAgent(model.ProviderAnthropic, "claude-x"), RoundMode: model.RoundModeFixed, MaxRounds: 1}
	discReq := authedRequest(t, "POST", srv.URL+"/debates", token, mustJSON(t, model.Discussion{Name: "Debate", Config: config}))
	discResp, _ := client.Do(discReq)
	var discussion model.Discussion
	json.NewDecoder(discResp.Body).Decode(&discussion)

	startReq := authedRequest(t, "POST", srv.URL+"/debates/"+discussion.ID+"/start", token, nil)
	startResp, err := client.Do(startReq)
	if err != nil || startResp.StatusCode != http.StatusAccepted {
		t.Fatalf("start: err=%v status=%d", err, startResp.StatusCode)
	}

	// While in-flight, inject a human user comment via PUT /debates/{id}
	userMsg := model.DebateMessage{
		AgentID:       model.ProviderCustom,
		Round:         1,
		Content:       "Wait, consider security risks!",
		IsUserComment: true,
		TimestampMs:   time.Now().UnixMilli(),
	}
	updateReq := authedRequest(t, "PUT", srv.URL+"/debates/"+discussion.ID, token, mustJSON(t, model.Discussion{
		Transcript: []model.DebateMessage{userMsg},
	}))
	updateResp, err := client.Do(updateReq)
	if err != nil || updateResp.StatusCode != http.StatusOK {
		t.Fatalf("update mid-flight: err=%v status=%d", err, updateResp.StatusCode)
	}

	// Release turn
	close(turnHold)

	// Wait for completion
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		final, err := st.Load()
		if err == nil {
			for _, d := range final.Discussions {
				if d.ID == discussion.ID && (d.Status == model.DiscussionDone || d.Status == model.DiscussionCompleted) {
					hasUserComment := false
					for _, m := range d.Transcript {
						if m.IsUserComment && m.Content == "Wait, consider security risks!" {
							hasUserComment = true
							break
						}
					}
					if !hasUserComment {
						t.Errorf("Discussion transcript missing injected user comment: %+v", d.Transcript)
					}
					return // success
				}
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("discussion never reached DONE status")
}

func TestChatPersona(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	s.RunnerFor = func(model.Agent) runner.AgentRunner {
		return &fakeRunner{
			respond: func(agent model.Agent, transcript []model.DebateMessage) (runner.AgentReply, error) {
				replyText := "Sure, here is the requested persona:\n\n```json\n" +
					`{
  "name": "Cloud Security Lead",
  "category": "Software Engineering",
  "role": "IAM & OAuth Auditor",
  "description": "Scrutinizes access policies and token lifetimes.",
  "systemPrompt": "Focus strictly on IAM least privilege.",
  "ponytail": false
}` + "\n```\nLet me know if you need tweaks."
				return runner.AgentReply{Content: replyText}, nil
			},
		}
	}

	token := loginAndGetToken(t, srv, s, st)

	reqPayload := personaChatRequest{
		Provider: model.ProviderAnthropic,
		Model:    "claude-sonnet-5",
		RunMode:  model.RunModeAPI,
		Messages: []personaChatMessageDto{
			{Role: "user", Content: "Create an IAM security persona"},
		},
	}

	req := authedRequest(t, "POST", srv.URL+"/api/v1/personas/chat", token, mustJSON(t, reqPayload))
	resp, err := srv.Client().Do(req)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var chatResp personaChatResponse
	if err := json.NewDecoder(resp.Body).Decode(&chatResp); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if chatResp.ParsedPersona == nil {
		t.Fatal("expected parsedPersona to be non-nil")
	}
	if chatResp.ParsedPersona.Name != "Cloud Security Lead" {
		t.Errorf("expected persona name 'Cloud Security Lead', got '%s'", chatResp.ParsedPersona.Name)
	}
}
