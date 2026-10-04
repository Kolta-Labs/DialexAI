package sandbox

import (
	"context"
	"strings"
	"testing"
)

func TestSandboxCheckpointAndResetLifecycle(t *testing.T) {
	env := NewSandboxEnvironment(SandboxConfig{
		ID:       "staging-sb-01",
		Strategy: StrategyDatabaseTransaction,
		TenantID: "tenant-sandbox-1234",
	})

	ctx := context.Background()

	// 1. Prepare pristine checkpoint
	if err := env.MarkCheckpoint(ctx, "pristine_01"); err != nil {
		t.Fatalf("failed to prepare checkpoint: %v", err)
	}

	// 2. Intercept an outbound notification/webhook
	env.InterceptOutboundCall("https://api.sendgrid.com/v3/mail/send", map[string]string{
		"Authorization": "Bearer fake_token",
	}, `{"to": "customer@example.com", "subject": "Order Confirmed"}`)

	intercepted := env.GetInterceptedWebhooks()
	if len(intercepted) != 1 {
		t.Fatalf("expected 1 intercepted webhook, got %d", len(intercepted))
	}

	// 3. Perform rollback
	if err := env.ResetInterceptedState(ctx, "pristine_01"); err != nil {
		t.Fatalf("failed to reset to clean state: %v", err)
	}

	// After clean state reset, outbound webhook sink is flushed
	if len(env.GetInterceptedWebhooks()) != 0 {
		t.Errorf("expected intercepted webhooks to be cleared after reset")
	}

	// 4. Verify boundary declaration transparency
	decl := GetBoundaryDeclaration()
	if !decl.ClientSideRollback || !decl.TransactionalDBRollback {
		t.Errorf("expected client-side and DB rollback to be supported")
	}
	if !strings.Contains(decl.MessageQueueRollback, "UNSUPPORTED") {
		t.Errorf("expected message queue rollback to be explicitly declared unsupported")
	}
	if !strings.Contains(decl.OutboundWebhookRecall, "UNSUPPORTED") {
		t.Errorf("expected outbound webhooks to be declared unsupported without mocks")
	}

	// 5. Verify tenant scoping
	if env.GetTenantID() != "tenant-sandbox-1234" {
		t.Errorf("unexpected tenant ID: %s", env.GetTenantID())
	}

	// 6. Verify formal rollback boundary declaration
	if decl.ScopeFormalBoundary != FormalRollbackBoundaryScope {
		t.Errorf("unexpected boundary scope: %s", decl.ScopeFormalBoundary)
	}
}

func TestDependencyManifestValidatorRejectsLiveThirdParty(t *testing.T) {
	validator := NewDependencyManifestValidator()

	// 1. Un-stubbed live Stripe endpoint -> MUST REJECT!
	dangerousConfig := []ThirdPartyDependency{
		{
			Name:        "stripe",
			EndpointURL: "https://api.stripe.com/v1/charges",
			IsStubbed:   false,
		},
	}

	err := validator.ValidateDependencies(dangerousConfig)
	if err == nil || !strings.Contains(err.Error(), "test execution refused") {
		t.Errorf("expected rejection for live un-stubbed Stripe endpoint, got: %v", err)
	}

	// 2. Properly stubbed Stripe via WireMock -> ALLOWED
	safeConfig := []ThirdPartyDependency{
		{
			Name:         "stripe",
			EndpointURL:  "https://api.stripe.com/v1/charges",
			IsStubbed:    true,
			StubProvider: "wiremock",
			MockEndpoint: "http://localhost:8088",
		},
	}

	if err := validator.ValidateDependencies(safeConfig); err != nil {
		t.Errorf("expected stubbed Stripe endpoint to pass validation, got: %v", err)
	}
}

func TestWireMockStubsAndPactContract(t *testing.T) {
	wm := NewWireMockManager()

	// 1. Setup Stripe PaymentIntent stub
	stripeStub := wm.SetupStripePaymentIntentStub("pi_12345_test", 5000, "usd")
	loadedStub, ok := wm.GetStub(stripeStub.ID)
	if !ok || loadedStub.Response.Status != 200 {
		t.Fatalf("failed to retrieve Stripe PaymentIntent stub")
	}
	if !strings.Contains(loadedStub.Response.Body, "succeeded") {
		t.Errorf("unexpected response body in Stripe stub: %s", loadedStub.Response.Body)
	}

	// 2. Setup Twilio SMS stub
	smsStub := wm.SetupTwilioSMSStub("SM_fake_sid_789")
	if smsStub.Response.Status != 201 {
		t.Errorf("unexpected SMS stub status: %d", smsStub.Response.Status)
	}

	// 3. Pact Consumer-Driven Contract Verification
	pact := PactContract{
		Consumer: "checkout-frontend",
		Provider: "payment-service",
		Interactions: []PactInteraction{
			{
				Description: "create payment intent",
				Request:     WireMockRequest{Method: "POST", URL: "/v1/payment_intents"},
				Response:    WireMockResponse{Status: 200, Body: `{"status": "succeeded"}`},
			},
		},
	}

	if err := pact.VerifyContract(); err != nil {
		t.Errorf("expected pact contract verification to succeed: %v", err)
	}
}
