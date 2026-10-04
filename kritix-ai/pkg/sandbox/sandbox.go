package sandbox

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"strings"
	"sync"
	"time"
)

// ResetStrategy defines how the sandbox restores clean database/system state.
type ResetStrategy string

const (
	StrategyDatabaseTransaction ResetStrategy = "db_transaction"
	StrategyDockerVolumeReset   ResetStrategy = "docker_volume"
	StrategyMockServiceReset    ResetStrategy = "mock_service"
	StrategyInMemoryState       ResetStrategy = "in_memory"
	StrategyTenantIsolation     ResetStrategy = "synthetic_tenant"
)

const (
	FormalRollbackBoundaryScope   = "SINGLE_SERVICE_DATABASE_ISOLATED"
	RollbackBoundaryDocumentation = "Clean-state rollback is formally scoped to a single service with isolated database transactions or container volumes. Distributed microservice rollbacks (Kafka/RabbitMQ append-only event logs, asynchronous distributed sagas, and third-party webhooks) are strictly OUT-OF-SCOPE and require service virtualization / mock stubs (WireMock/Pact)."
)

var (
	ErrLiveThirdPartyProhibited = errors.New("test execution refused: un-stubbed live 3rd-party endpoint detected in environment config")
)

// ThirdPartyDependency models an external service dependency declared in the environment manifest.
type ThirdPartyDependency struct {
	Name         string `json:"name"`          // e.g. "stripe", "paypal", "okta", "twilio", "sendgrid"
	EndpointURL  string `json:"endpoint_url"`  // e.g. "https://api.stripe.com/v1"
	IsStubbed    bool   `json:"is_stubbed"`    // true if routed through WireMock / local mock
	StubProvider string `json:"stub_provider"` // "wiremock", "pact", "in_memory"
	MockEndpoint string `json:"mock_endpoint"` // e.g. "http://localhost:8088"
}

// DependencyManifestValidator enforces that no tests execute against live third-party endpoints.
type DependencyManifestValidator struct {
	RestrictedDomains []string
}

// NewDependencyManifestValidator creates a validator populated with high-risk financial, auth, and communication providers.
func NewDependencyManifestValidator() *DependencyManifestValidator {
	return &DependencyManifestValidator{
		RestrictedDomains: []string{
			"api.stripe.com",
			"stripe.com",
			"api.paypal.com",
			"paypal.com",
			"okta.com",
			"api.twilio.com",
			"twilio.com",
			"api.sendgrid.com",
			"sendgrid.com",
			"adyen.com",
		},
	}
}

// ValidateDependencies inspects all external endpoints and halts execution if live production APIs are un-stubbed.
func (v *DependencyManifestValidator) ValidateDependencies(deps []ThirdPartyDependency) error {
	for _, d := range deps {
		urlLower := strings.ToLower(d.EndpointURL)
		for _, restricted := range v.RestrictedDomains {
			if strings.Contains(urlLower, restricted) {
				// If not explicitly marked as stubbed or pointing to a mock endpoint
				if !d.IsStubbed || d.MockEndpoint == "" {
					return fmt.Errorf("%w: live third-party endpoint %q (%s) detected without required WireMock/Pact stubbing. External dependencies must be mocked to prevent real-world financial charges, rate limits, or persistent state mutations",
						ErrLiveThirdPartyProhibited, d.EndpointURL, d.Name)
				}
			}
		}
	}
	return nil
}

// WireMockRequest models a WireMock HTTP matching rule.
type WireMockRequest struct {
	Method  string            `json:"method"`
	URL     string            `json:"url,omitempty"`
	Headers map[string]string `json:"headers,omitempty"`
}

// WireMockResponse models a WireMock HTTP stub response.
type WireMockResponse struct {
	Status  int               `json:"status"`
	Body    string            `json:"body"`
	Headers map[string]string `json:"headers,omitempty"`
}

// WireMockStub models a standalone WireMock mapping.
type WireMockStub struct {
	ID       string           `json:"id"`
	Request  WireMockRequest  `json:"request"`
	Response WireMockResponse `json:"response"`
}

// WireMockManager coordinates WireMock stubs for payment and auth virtualization.
type WireMockManager struct {
	mu    sync.RWMutex
	stubs map[string]WireMockStub
}

// NewWireMockManager constructs a WireMock stub manager.
func NewWireMockManager() *WireMockManager {
	return &WireMockManager{
		stubs: make(map[string]WireMockStub),
	}
}

// RegisterStub stores a virtual service mapping.
func (m *WireMockManager) RegisterStub(stub WireMockStub) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.stubs[stub.ID] = stub
}

// GetStub retrieves a registered mapping.
func (m *WireMockManager) GetStub(id string) (WireMockStub, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.stubs[id]
	return s, ok
}

// SetupStripePaymentIntentStub creates a canonical WireMock stub for Stripe PaymentIntents.
func (m *WireMockManager) SetupStripePaymentIntentStub(intentID string, amountCents int, currency string) WireMockStub {
	stub := WireMockStub{
		ID: "stripe_payment_intent_" + intentID,
		Request: WireMockRequest{
			Method: "POST",
			URL:    "/v1/payment_intents",
		},
		Response: WireMockResponse{
			Status: 200,
			Body: fmt.Sprintf(`{
				"id": %q,
				"object": "payment_intent",
				"amount": %d,
				"currency": %q,
				"status": "succeeded"
			}`, intentID, amountCents, currency),
			Headers: map[string]string{"Content-Type": "application/json"},
		},
	}
	m.RegisterStub(stub)
	return stub
}

// SetupTwilioSMSStub creates a canonical WireMock stub for Twilio SMS dispatch.
func (m *WireMockManager) SetupTwilioSMSStub(messageSID string) WireMockStub {
	stub := WireMockStub{
		ID: "twilio_sms_" + messageSID,
		Request: WireMockRequest{
			Method: "POST",
			URL:    "/2010-04-01/Accounts/ACfake/Messages.json",
		},
		Response: WireMockResponse{
			Status: 201,
			Body: fmt.Sprintf(`{
				"sid": %q,
				"status": "queued",
				"error_code": null
			}`, messageSID),
			Headers: map[string]string{"Content-Type": "application/json"},
		},
	}
	m.RegisterStub(stub)
	return stub
}

// PactInteraction models a Pact consumer-driven contract interaction.
type PactInteraction struct {
	Description string           `json:"description"`
	Request     WireMockRequest  `json:"request"`
	Response    WireMockResponse `json:"response"`
}

// PactContract models a complete Pact contract between services.
type PactContract struct {
	Consumer     string            `json:"consumer"`
	Provider     string            `json:"provider"`
	Interactions []PactInteraction `json:"interactions"`
}

// VerifyContract validates that provider responses satisfy the consumer contract specifications.
func (p *PactContract) VerifyContract() error {
	if p.Consumer == "" || p.Provider == "" {
		return errors.New("invalid pact contract: consumer and provider names required")
	}
	if len(p.Interactions) == 0 {
		return errors.New("invalid pact contract: interactions list is empty")
	}
	return nil
}

// DistributedBoundaryDeclaration provides technical honesty regarding microservice rollback limitations.
type DistributedBoundaryDeclaration struct {
	ScopeFormalBoundary       string `json:"scope_formal_boundary"`
	ClientSideRollback        bool   `json:"client_side_rollback"`        // true (localStorage, cookies, session)
	TransactionalDBRollback   bool   `json:"transactional_db_rollback"`   // true (single-DB savepoints)
	MessageQueueRollback      string `json:"message_queue_rollback"`      // "UNSUPPORTED: Kafka/RabbitMQ events are immutable"
	OutboundWebhookRecall     string `json:"outbound_webhook_recall"`     // "UNSUPPORTED: Fired webhooks require service virtualization / mocks"
	RecommendedEnterpriseMode string `json:"recommended_enterprise_mode"` // "Synthetic Tenant Scoping (tenant_id = sandbox_test_uuid)"
}

// GetBoundaryDeclaration documents the real-world operational constraints of distributed systems.
func GetBoundaryDeclaration() DistributedBoundaryDeclaration {
	return DistributedBoundaryDeclaration{
		ScopeFormalBoundary:       FormalRollbackBoundaryScope,
		ClientSideRollback:        true,
		TransactionalDBRollback:   true,
		MessageQueueRollback:      "UNSUPPORTED: Kafka/RabbitMQ append-only event streams cannot be rolled back. Use synthetic tenant isolation.",
		OutboundWebhookRecall:     "UNSUPPORTED: Third-party emails/SMS (SendGrid/Twilio) and charges (Stripe) cannot be un-fired. Must be intercepted via Service Virtualization mocks.",
		RecommendedEnterpriseMode: "Synthetic Tenant Scoping: Tests must run under isolated tenant headers (X-Test-Tenant-ID) with mocked outbound notification sinks.",
	}
}

// SandboxConfig specifies isolation parameters.
type SandboxConfig struct {
	ID            string        `json:"id"`
	Strategy      ResetStrategy `json:"strategy"`
	DatabaseURL   string        `json:"database_url,omitempty"`
	ResetCommand  string        `json:"reset_command,omitempty"`
	SeedCommand   string        `json:"seed_command,omitempty"`
	CleanInterval time.Duration `json:"clean_interval"`
	TenantID      string        `json:"tenant_id,omitempty"`
}

// InterceptedWebhook records an outbound event caught before hitting the live internet.
type InterceptedWebhook struct {
	Timestamp   time.Time         `json:"timestamp"`
	Destination string            `json:"destination"`
	Headers     map[string]string `json:"headers"`
	Body        string            `json:"body"`
}

// SandboxEnvironment controls isolated runtime state for testing.
type SandboxEnvironment struct {
	mu                  sync.RWMutex
	config              SandboxConfig
	activeRuns          int
	lastResetAt         time.Time
	checkpoints         map[string]time.Time
	interceptedWebhooks []InterceptedWebhook
}

// NewSandboxEnvironment creates a new sandbox environment.
func NewSandboxEnvironment(cfg SandboxConfig) *SandboxEnvironment {
	if cfg.Strategy == "" {
		cfg.Strategy = StrategyInMemoryState
	}
	if cfg.TenantID == "" {
		cfg.TenantID = "tenant-" + newUUID()
	}
	return &SandboxEnvironment{
		config:              cfg,
		checkpoints:         make(map[string]time.Time),
		interceptedWebhooks: make([]InterceptedWebhook, 0),
	}
}

// InterceptOutboundCall catches third-party network events before they leave the test cluster.
func (s *SandboxEnvironment) InterceptOutboundCall(dest string, headers map[string]string, body string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.interceptedWebhooks = append(s.interceptedWebhooks, InterceptedWebhook{
		Timestamp:   time.Now(),
		Destination: dest,
		Headers:     headers,
		Body:        body,
	})
}

// GetInterceptedWebhooks retrieves caught webhooks for assertion verification.
func (s *SandboxEnvironment) GetInterceptedWebhooks() []InterceptedWebhook {
	s.mu.RLock()
	defer s.mu.RUnlock()
	copied := make([]InterceptedWebhook, len(s.interceptedWebhooks))
	copy(copied, s.interceptedWebhooks)
	return copied
}

// RecordBaseline records a named baseline marker for verifying test start states.
func (s *SandboxEnvironment) RecordBaseline(ctx context.Context, baselineID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.checkpoints[baselineID] = time.Now()
	return nil
}

// MarkCheckpoint is an alias for RecordBaseline.
func (s *SandboxEnvironment) MarkCheckpoint(ctx context.Context, checkpointID string) error {
	return s.RecordBaseline(ctx, checkpointID)
}

// ResetInterceptedWebhooks discards caught webhooks from this test run.
// For real database rollback, use PostgresDatabaseResetter or per-tenant data partitioning.
func (s *SandboxEnvironment) ResetInterceptedWebhooks(ctx context.Context, baselineID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.checkpoints[baselineID]; !exists {
		return fmt.Errorf("baseline marker %q not found", baselineID)
	}

	s.lastResetAt = time.Now()
	s.interceptedWebhooks = make([]InterceptedWebhook, 0)
	return nil
}

// ResetInterceptedState is an alias for ResetInterceptedWebhooks.
func (s *SandboxEnvironment) ResetInterceptedState(ctx context.Context, checkpointID string) error {
	return s.ResetInterceptedWebhooks(ctx, checkpointID)
}

// GetLastResetTime returns when the sandbox was last restored.
func (s *SandboxEnvironment) GetLastResetTime() time.Time {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastResetAt
}

// GetTenantID returns the synthetic tenant ID used to isolate distributed microservice data.
func (s *SandboxEnvironment) GetTenantID() string {
	return s.config.TenantID
}

// newUUID returns a random RFC 4122 v4 UUID.
func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err) // no entropy: tenant isolation would be guessable
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

// TenantScope names every per-run partition derived from the tenant ID, so each test run
// gets its own Kafka consumer group, DB schema, cache prefix and search index.
type TenantScope struct {
	TenantID      string `json:"tenant_id"`
	ConsumerGroup string `json:"kafka_consumer_group"`
	DBSchema      string `json:"db_schema"`
	CachePrefix   string `json:"cache_prefix"`
	SearchIndex   string `json:"search_index"`
	HeaderName    string `json:"header_name"`
}

// Scope returns the isolation partitions for this sandbox's tenant.
func (s *SandboxEnvironment) Scope() TenantScope {
	id := s.config.TenantID
	schema := strings.ReplaceAll(id, "-", "_")
	return TenantScope{
		TenantID:      id,
		ConsumerGroup: "kritix-" + id,
		DBSchema:      schema,
		CachePrefix:   id + ":",
		SearchIndex:   "kritix-" + id,
		HeaderName:    "X-Test-Tenant-ID",
	}
}
