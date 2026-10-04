package driver

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// VirtualDriver provides an in-memory, zero-dependency browser driver for headless unit tests.
// In production, CDPDriver is used.
type VirtualDriver struct {
	mu          sync.RWMutex
	currentURL  string
	title       string
	history     []string
	actions     []Action
	consoleLogs []string
	networkLog  []NetworkEvent
	elements    []Element
	axRoot      *AXNode
	running     bool
}

// NewVirtualDriver initializes a virtual browser driver.
func NewVirtualDriver() *VirtualDriver {
	return &VirtualDriver{
		currentURL: "about:blank",
		title:      "Blank",
		elements:   make([]Element, 0),
		networkLog: make([]NetworkEvent, 0),
	}
}

func (v *VirtualDriver) Start(ctx context.Context) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.running = true
	return nil
}

func (v *VirtualDriver) Stop(ctx context.Context) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.running = false
	return nil
}

func (v *VirtualDriver) Navigate(ctx context.Context, targetURL string) (*BrowserState, error) {
	v.mu.Lock()
	defer v.mu.Unlock()

	v.currentURL = targetURL
	v.history = append(v.history, targetURL)
	v.title = "Page: " + targetURL

	v.networkLog = append(v.networkLog, NetworkEvent{
		URL:        targetURL,
		Method:     "GET",
		StatusCode: 200,
		Duration:   45 * time.Millisecond,
	})

	return v.snapshotLocked(), nil
}

func (v *VirtualDriver) GetState(ctx context.Context) (*BrowserState, error) {
	v.mu.RLock()
	defer v.mu.RUnlock()
	return v.snapshotLocked(), nil
}

func (v *VirtualDriver) ExecuteAction(ctx context.Context, action Action) (*BrowserState, error) {
	v.mu.Lock()
	defer v.mu.Unlock()

	action.Timestamp = time.Now()
	v.actions = append(v.actions, action)

	switch action.Type {
	case ActionClick:
		v.consoleLogs = append(v.consoleLogs, fmt.Sprintf("Clicked on %s (role: %s)", action.TargetText, action.TargetRole))
	case ActionTypeKey:
		v.consoleLogs = append(v.consoleLogs, fmt.Sprintf("Typed %q into %s", action.Value, action.TargetXPath))
	case ActionNavigate:
		v.currentURL = action.Value
		v.title = "Page: " + action.Value
	}

	return v.snapshotLocked(), nil
}

func (v *VirtualDriver) CaptureScreenshot(ctx context.Context) (string, error) {
	// Returns a 1x1 transparent PNG base64 placeholder for virtual driver
	return "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=", nil
}

func (v *VirtualDriver) SetVirtualDOM(elements []Element, axRoot *AXNode) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.elements = elements
	v.axRoot = axRoot
}

func (v *VirtualDriver) snapshotLocked() *BrowserState {
	copiedElements := make([]Element, len(v.elements))
	copy(copiedElements, v.elements)

	copiedLogs := make([]string, len(v.consoleLogs))
	copy(copiedLogs, v.consoleLogs)

	copiedNet := make([]NetworkEvent, len(v.networkLog))
	copy(copiedNet, v.networkLog)

	return &BrowserState{
		URL:             v.currentURL,
		Title:           v.title,
		ViewportWidth:   1440,
		ViewportHeight:  900,
		Elements:        copiedElements,
		AXTree:          v.axRoot,
		ScreenshotB64:   "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
		ConsoleLogs:     copiedLogs,
		NetworkActivity: copiedNet,
		Timestamp:       time.Now(),
	}
}

func (v *VirtualDriver) Stabilize(ctx context.Context, opts AnimationStabilizationOptions) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	delay := opts.StabilizationDelay
	if delay <= 0 {
		delay = 50 * time.Millisecond
	}
	time.Sleep(delay)
	return nil
}

// GetDefaultCapabilities returns baseline enterprise boundaries for virtual driver.
func GetDefaultCapabilities() BrowserCapability {
	return BrowserCapability{
		SupportsShadowDOM:  true,
		SupportsAnimations: true,
		CanvasInspection:   "OUT_OF_SCOPE: WebGL/Canvas elements render zero semantic AXTree nodes. Visual pixel-diff only.",
		CrossOriginIframes: "OUT_OF_SCOPE: Cross-origin payment iframes (Stripe/PayPal) require test-mode bypass tokens or mock webhooks.",
		CaptchaHandling:    "OUT_OF_SCOPE: Cloudflare Turnstile / reCAPTCHA requires IP allowlisting or test-environment bypass flags.",
		RuntimeEngine:      "VirtualMock",
		ProbedAt:           time.Now(),
	}
}
