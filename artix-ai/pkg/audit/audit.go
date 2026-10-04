package audit

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"artix/pkg/policy"
)

// EventType categorizes an enterprise audit event.
type EventType string

const (
	EventSpecDeliberation EventType = "spec.deliberation"
	EventCodeConvergence  EventType = "code.convergence"
	EventReviewerVerdict   EventType = "reviewer.verdict"
	EventSteeringBind      EventType = "steering.bind"
	EventWorktreeGC        EventType = "worktree.gc"
	EventSandboxExecution  EventType = "sandbox.execution"
	EventBudgetExhausted   EventType = "budget.exhausted"
	EventSecurityViolation EventType = "security.violation"
)

// GenesisHash is the root parent hash for the first record in an audit stream.
const GenesisHash = "0000000000000000000000000000000000000000000000000000000000000000"

// AuditEvent represents a structured, SIEM/OTEL-compatible, hash-chained audit record.
type AuditEvent struct {
	EventID      string         `json:"eventId"`
	Timestamp    time.Time      `json:"timestamp"`
	EventType    EventType      `json:"eventType"`
	Actor        string         `json:"actor"`
	Workspace    string         `json:"workspace"`
	Status       string         `json:"status"` // "SUCCESS", "REJECTED", "FAILED", "INFO"
	DurationMs   int64          `json:"durationMs,omitempty"`
	ModelID      string         `json:"modelId,omitempty"`
	PromptHash   string         `json:"promptHash,omitempty"`
	ResponseHash string         `json:"responseHash,omitempty"`
	StorySpecID  string         `json:"storySpecId,omitempty"`
	DiffHash     string         `json:"diffHash,omitempty"`
	Round        int            `json:"round,omitempty"`
	Cost         float64        `json:"cost,omitempty"`
	Details      map[string]any `json:"details,omitempty"`
	PrevHash     string         `json:"prevHash"`
	RecordHash   string         `json:"recordHash"`
	Signature    string         `json:"signature,omitempty"`
}

// ComputeRecordHash deterministically calculates the SHA-256 hash of the audit record.
func ComputeRecordHash(e *AuditEvent) string {
	var detailsJSON []byte
	if e.Details != nil {
		detailsJSON, _ = json.Marshal(e.Details)
	}
	payload := fmt.Sprintf("%s|%s|%s|%s|%s|%s|%s|%d|%s|%s|%s|%s|%s|%d|%.6f|%s",
		e.PrevHash,
		e.EventID,
		e.Timestamp.UTC().Format(time.RFC3339Nano),
		e.EventType,
		e.Actor,
		e.Workspace,
		e.Status,
		e.DurationMs,
		e.ModelID,
		e.PromptHash,
		e.ResponseHash,
		e.StorySpecID,
		e.DiffHash,
		e.Round,
		e.Cost,
		string(detailsJSON),
	)
	h := sha256.Sum256([]byte(payload))
	return hex.EncodeToString(h[:])
}

// SignRecord produces an HMAC-SHA256 signature for a record hash.
func SignRecord(recordHash string, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(recordHash))
	return hex.EncodeToString(mac.Sum(nil))
}

// VerifyRecordSignature validates the HMAC signature of a record.
func VerifyRecordSignature(recordHash, signature, secret string) bool {
	expected := SignRecord(recordHash, secret)
	return hmac.Equal([]byte(expected), []byte(signature))
}

// Logger persists, hash-chains, and emits structured audit records.
type Logger struct {
	workspaceDir string
	logPath      string
	signingKey   string
	remoteSinks  []policy.RemoteSinkConfig
	lastHash     string
	mu           sync.Mutex
	httpClient   *http.Client
}

var (
	defaultLogger *Logger
	loggerMu      sync.Mutex
)

// Default returns the process-wide default audit logger.
func Default(workspaceDir string) *Logger {
	loggerMu.Lock()
	defer loggerMu.Unlock()

	// Recreate if workspaceDir changed or not yet initialized
	if defaultLogger == nil || (workspaceDir != "" && defaultLogger.workspaceDir != workspaceDir) {
		defaultLogger = NewLogger(workspaceDir)
	}
	return defaultLogger
}

// SetDefaultLogger sets the global default logger (useful for tests).
func SetDefaultLogger(l *Logger) {
	loggerMu.Lock()
	defer loggerMu.Unlock()
	defaultLogger = l
}

// NewLogger creates a structured audit logger for a workspace honoring policy.
func NewLogger(workspaceDir string) *Logger {
	logFile := policy.EffectiveAuditLogPath(workspaceDir)

	pol := policy.Active()
	signingKey := pol.AuditSigningKey
	if signingKey == "" {
		signingKey = os.Getenv("ARTIX_AUDIT_SIGNING_KEY")
	}

	l := &Logger{
		workspaceDir: workspaceDir,
		logPath:      logFile,
		signingKey:   signingKey,
		remoteSinks:  pol.AuditRemoteSinks,
		lastHash:     GenesisHash,
		httpClient:   &http.Client{Timeout: 5 * time.Second},
	}

	// Initialize lastHash from existing log file if available
	l.initLastHash()
	return l
}

func (l *Logger) initLastHash() {
	if l.logPath == "" {
		return
	}
	data, err := os.ReadFile(l.logPath)
	if err != nil || len(data) == 0 {
		return
	}

	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) == 0 {
		return
	}

	lastLine := strings.TrimSpace(lines[len(lines)-1])
	if lastLine == "" {
		return
	}

	var evt AuditEvent
	if err := json.Unmarshal([]byte(lastLine), &evt); err == nil && evt.RecordHash != "" {
		l.lastHash = evt.RecordHash
	}
}

// SetSigningKey configures an HMAC key for cryptographic record signatures.
func (l *Logger) SetSigningKey(key string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.signingKey = key
}

// AddRemoteSink adds an HTTP or syslog remote audit sink.
func (l *Logger) AddRemoteSink(sink policy.RemoteSinkConfig) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.remoteSinks = append(l.remoteSinks, sink)
}

// LogPath returns the path to the active audit log file.
func (l *Logger) LogPath() string {
	return l.logPath
}

// Emit writes an AuditEvent to the JSONL log stream with hash chaining and dispatches to remote sinks.
func (l *Logger) Emit(event AuditEvent) error {
	if event.EventID == "" {
		event.EventID = fmt.Sprintf("evt-%d", time.Now().UnixNano())
	}
	if event.Timestamp.IsZero() {
		event.Timestamp = time.Now()
	}
	if event.Actor == "" {
		event.Actor = os.Getenv("USER")
		if event.Actor == "" {
			event.Actor = "artix-agent"
		}
	}
	if event.Workspace == "" {
		event.Workspace = l.workspaceDir
	}

	l.mu.Lock()
	defer l.mu.Unlock()

	// Hash Chaining
	if l.lastHash == "" {
		l.lastHash = GenesisHash
	}
	event.PrevHash = l.lastHash
	event.RecordHash = ComputeRecordHash(&event)

	// Optional signing
	if l.signingKey != "" {
		event.Signature = SignRecord(event.RecordHash, l.signingKey)
	}

	data, err := json.Marshal(event)
	if err != nil {
		return fmt.Errorf("failed to marshal audit event: %w", err)
	}
	logLine := append(data, '\n')

	if l.logPath != "" {
		_ = os.MkdirAll(filepath.Dir(l.logPath), 0755)
		f, err := os.OpenFile(l.logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0644)
		if err != nil {
			return fmt.Errorf("failed to open audit log %s: %w", l.logPath, err)
		}
		if _, err := f.Write(logLine); err != nil {
			_ = f.Close()
			return fmt.Errorf("failed to write audit record: %w", err)
		}
		_ = f.Close()
	}

	l.lastHash = event.RecordHash

	// Dispatch to remote sinks asynchronously
	for _, sink := range l.remoteSinks {
		go l.sendToRemoteSink(sink, data)
	}

	return nil
}

func (l *Logger) sendToRemoteSink(sink policy.RemoteSinkConfig, data []byte) {
	switch strings.ToLower(sink.Type) {
	case "http", "https":
		req, err := http.NewRequest("POST", sink.Endpoint, bytes.NewReader(data))
		if err != nil {
			return
		}
		req.Header.Set("Content-Type", "application/json")
		if sink.AuthKey != "" {
			req.Header.Set("Authorization", "Bearer "+sink.AuthKey)
		}
		for k, v := range sink.Headers {
			req.Header.Set(k, v)
		}
		resp, err := l.httpClient.Do(req)
		if err == nil {
			_ = resp.Body.Close()
		}

	case "syslog":
		// Send over UDP / TCP or local syslog socket
		endpoint := sink.Endpoint
		network := "udp"
		if strings.HasPrefix(endpoint, "tcp://") {
			network = "tcp"
			endpoint = strings.TrimPrefix(endpoint, "tcp://")
		} else if strings.HasPrefix(endpoint, "udp://") {
			endpoint = strings.TrimPrefix(endpoint, "udp://")
		}

		conn, err := net.DialTimeout(network, endpoint, 2*time.Second)
		if err == nil {
			syslogMsg := fmt.Sprintf("<14>%s artix-audit: %s\n", time.Now().Format(time.RFC3339), string(data))
			_, _ = conn.Write([]byte(syslogMsg))
			_ = conn.Close()
		}
	}
}

// VerificationResult summarizes the audit log chain verification.
type VerificationResult struct {
	TotalRecords int    `json:"totalRecords"`
	ValidRecords int    `json:"validRecords"`
	GenesisHash  string `json:"genesisHash"`
	LastHash     string `json:"lastHash"`
}

// VerifyLog verifies the cryptographic hash chain and optional signatures of an audit log file.
// It detects any tampering, record modification, reordering, or truncation.
func VerifyLog(logPath string, signingKey ...string) (*VerificationResult, error) {
	data, err := os.ReadFile(logPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read audit log: %w", err)
	}

	lines := strings.Split(strings.TrimSpace(string(data)), "\n")
	if len(lines) == 0 || (len(lines) == 1 && lines[0] == "") {
		return &VerificationResult{TotalRecords: 0, ValidRecords: 0}, nil
	}

	expectedPrevHash := GenesisHash
	key := ""
	if len(signingKey) > 0 {
		key = signingKey[0]
	}

	for i, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}

		var event AuditEvent
		if err := json.Unmarshal([]byte(line), &event); err != nil {
			return nil, fmt.Errorf("tampering detected at record #%d: invalid JSON format: %w", i+1, err)
		}

		// 1. Check prev_hash linkage
		if event.PrevHash != expectedPrevHash {
			return nil, fmt.Errorf("tampering detected at record #%d (event ID %s): broken hash chain: expected prev_hash %s, got %s",
				i+1, event.EventID, expectedPrevHash, event.PrevHash)
		}

		// 2. Check record_hash recalculation
		computedHash := ComputeRecordHash(&event)
		if event.RecordHash != computedHash {
			return nil, fmt.Errorf("tampering detected at record #%d (event ID %s): record payload altered: expected hash %s, got %s",
				i+1, event.EventID, computedHash, event.RecordHash)
		}

		// 3. Optional signature verification
		if key != "" && event.Signature != "" {
			if !VerifyRecordSignature(event.RecordHash, event.Signature, key) {
				return nil, fmt.Errorf("tampering detected at record #%d (event ID %s): invalid cryptographic signature", i+1, event.EventID)
			}
		}

		expectedPrevHash = event.RecordHash
	}

	return &VerificationResult{
		TotalRecords: len(lines),
		ValidRecords: len(lines),
		GenesisHash:  GenesisHash,
		LastHash:     expectedPrevHash,
	}, nil
}
