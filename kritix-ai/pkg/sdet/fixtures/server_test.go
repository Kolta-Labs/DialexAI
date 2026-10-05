package fixtures

import (
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestFixtureServer_AllRoutesOperational(t *testing.T) {
	srv := StartFixtureServer()
	defer srv.Close()

	routes := []struct {
		path            string
		expectedContent string
	}{
		{"/", "Kritix Taxonomy Fixture Suite"},
		{"/same-role", "Pay $149.00"},
		{"/ab-reorder", "Confirm & Submit"},
		{"/duplicate-rows", "Cancel Subscription"},
		{"/moved-renamed", "Place Order"},
		{"/hidden-overlay", "Session Expired"},
		{"/animation", "Claim 20% Discount"},
		{"/shadow-dom", "custom-checkout-card"},
		{"/iframes", "payment-iframe"},
		{"/i18n", "Completar compra"},
	}

	client := &http.Client{}

	for _, route := range routes {
		resp, err := client.Get(srv.URL + route.path)
		if err != nil {
			t.Fatalf("failed to GET %s: %v", route.path, err)
		}
		body, err := io.ReadAll(resp.Body)
		_ = resp.Body.Close()
		if err != nil {
			t.Fatalf("failed to read body for %s: %v", route.path, err)
		}

		if resp.StatusCode != http.StatusOK {
			t.Errorf("expected 200 OK for %s, got %d", route.path, resp.StatusCode)
		}
		if !strings.Contains(string(body), route.expectedContent) {
			t.Errorf("route %s missing expected snippet %q", route.path, route.expectedContent)
		}
	}
}
