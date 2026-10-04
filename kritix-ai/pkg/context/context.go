package context

import (
	"fmt"
	"strings"
)

// DomainType identifies the high-level industry domain of the system under test.
type DomainType string

const (
	DomainECommerce DomainType = "ecommerce"
	DomainFintech   DomainType = "fintech"
	DomainHealth    DomainType = "healthcare"
	DomainSaaS      DomainType = "b2b_saas"
)

// FlowTransition defines a legal state transition within an application journey.
type FlowTransition struct {
	FromState   string   `json:"from_state"`
	ToState     string   `json:"to_state"`
	Trigger     string   `json:"trigger"` // e.g. "click_submit_payment"
	Constraints []string `json:"constraints,omitempty"`
}

// FlowContext holds domain understanding and flow state machines built by the Test Architect.
type FlowContext struct {
	Domain             DomainType       `json:"domain"`
	FlowName           string           `json:"flow_name"` // e.g. "Checkout & Order Placement"
	InitialState       string           `json:"initial_state"`
	TerminalStates     []string         `json:"terminal_states"`
	Transitions        []FlowTransition `json:"transitions"`
	BusinessInvariants []string         `json:"business_invariants"`
	Prerequisites      []string         `json:"prerequisites"` // e.g. "User must have active cart with > 0 items"
}

// TestArchitect represents the persona responsible for authoring application domain context.
type TestArchitect struct {
	Name string `json:"name"`
}

// NewTestArchitect constructs a Test Architect persona.
func NewTestArchitect(name string) *TestArchitect {
	if name == "" {
		name = "Lead QA Architect"
	}
	return &TestArchitect{Name: name}
}

// BuildECommerceCheckoutContext provides pre-configured domain intelligence for checkout flows.
func (a *TestArchitect) BuildECommerceCheckoutContext() *FlowContext {
	return &FlowContext{
		Domain:         DomainECommerce,
		FlowName:       "Checkout & Payment Journey",
		InitialState:   "cart_view",
		TerminalStates: []string{"order_confirmed", "payment_declined", "abandoned"},
		Transitions: []FlowTransition{
			{FromState: "cart_view", ToState: "shipping_entry", Trigger: "click_checkout"},
			{FromState: "shipping_entry", ToState: "payment_entry", Trigger: "submit_valid_address"},
			{FromState: "payment_entry", ToState: "order_confirmed", Trigger: "submit_successful_payment"},
			{FromState: "payment_entry", ToState: "payment_declined", Trigger: "submit_expired_card"},
		},
		BusinessInvariants: []string{
			"Cart total must never be negative",
			"Orders above $0 require valid payment transaction ID",
			"Discounts cannot exceed total cart value",
		},
		Prerequisites: []string{
			"Authenticated user or valid guest session",
			"Active shopping cart with at least 1 in-stock item",
		},
	}
}

// ValidateTransition verifies whether a proposed agent action conforms to the domain flow.
func (fc *FlowContext) ValidateTransition(current, next, action string) (bool, string) {
	for _, t := range fc.Transitions {
		if t.FromState == current && t.ToState == next {
			if strings.EqualFold(t.Trigger, action) {
				return true, ""
			}
			return false, fmt.Sprintf("Trigger %q does not match expected transition trigger %q", action, t.Trigger)
		}
	}
	return false, fmt.Sprintf("Illegal flow transition from %q to %q in domain %q", current, next, fc.Domain)
}
