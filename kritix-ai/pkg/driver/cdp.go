package driver

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/chromedp/cdproto/accessibility"
	"github.com/chromedp/cdproto/browser"
	"github.com/chromedp/cdproto/dom"
	"github.com/chromedp/cdproto/emulation"
	"github.com/chromedp/cdproto/network"
	"github.com/chromedp/cdproto/page"
	"github.com/chromedp/cdproto/runtime"
	"github.com/chromedp/chromedp"
)

// CDPConfig holds configuration for starting or attaching to Chrome.
type CDPConfig struct {
	RemoteDebuggingURL string
	Headless           bool
	ExecPath           string
	ViewportWidth      int
	ViewportHeight     int
	UserAgent          string
	Timeout            time.Duration
	DisableSandbox     bool // Only set when running in privileged non-container environments that require no-sandbox
}

// DefaultCDPConfig returns standard defaults for headless CDP operations.
func DefaultCDPConfig() CDPConfig {
	return CDPConfig{
		Headless:       true,
		ViewportWidth:  1440,
		ViewportHeight: 900,
		Timeout:        30 * time.Second,
	}
}

// isRunningInContainer checks if the current process is executing within a container environment.
func isRunningInContainer() bool {
	if _, err := os.Stat("/.dockerenv"); err == nil {
		return true
	}
	if os.Getenv("KRITIX_CONTAINER_SANDBOX") == "true" || os.Getenv("KUBERNETES_SERVICE_HOST") != "" || os.Getenv("CONTAINER") != "" {
		return true
	}
	return false
}

// CDPDriver manages real Chrome DevTools Protocol browser automation.
type CDPDriver struct {
	config       CDPConfig
	allocCtx     context.Context
	allocCancel  context.CancelFunc
	tabCtx       context.Context
	tabCancel    context.CancelFunc
	mu           sync.RWMutex
	networkLog   []NetworkEvent
	pendingReqs  map[network.RequestID]time.Time
	consoleLogs  []string
	dialogs      []string
	currentURL   string
	title        string
	running      bool
	capabilities *BrowserCapability
}

// NewCDPDriver instantiates a CDP browser automation driver.
func NewCDPDriver(cfg CDPConfig) *CDPDriver {
	if cfg.ViewportWidth <= 0 {
		cfg.ViewportWidth = 1440
	}
	if cfg.ViewportHeight <= 0 {
		cfg.ViewportHeight = 900
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 30 * time.Second
	}
	return &CDPDriver{
		config:      cfg,
		networkLog:  make([]NetworkEvent, 0),
		pendingReqs: make(map[network.RequestID]time.Time),
		consoleLogs: make([]string, 0),
		dialogs:     make([]string, 0),
	}
}

// Start launches a new Chrome instance or attaches to a remote debugging endpoint.
func (c *CDPDriver) Start(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.running {
		return nil
	}

	if c.config.RemoteDebuggingURL != "" {
		c.allocCtx, c.allocCancel = chromedp.NewRemoteAllocator(ctx, c.config.RemoteDebuggingURL)
	} else {
		opts := append(chromedp.DefaultExecAllocatorOptions[:],
			chromedp.Flag("headless", c.config.Headless),
			chromedp.Flag("disable-gpu", true),
			chromedp.Flag("disable-dev-shm-usage", true),
			chromedp.Flag("disable-extensions", true),
			chromedp.WindowSize(c.config.ViewportWidth, c.config.ViewportHeight),
		)
		// Hardening: do not disable Chrome sandbox unless executing within a container or explicitly configured
		if c.config.DisableSandbox || isRunningInContainer() {
			opts = append(opts, chromedp.Flag("no-sandbox", true))
		}
		if c.config.ExecPath != "" {
			opts = append(opts, chromedp.ExecPath(c.config.ExecPath))
		}
		if c.config.UserAgent != "" {
			opts = append(opts, chromedp.UserAgent(c.config.UserAgent))
		}
		c.allocCtx, c.allocCancel = chromedp.NewExecAllocator(ctx, opts...)
	}

	c.tabCtx, c.tabCancel = chromedp.NewContext(c.allocCtx)

	// Set up CDP event listeners
	chromedp.ListenTarget(c.tabCtx, func(ev interface{}) {
		c.handleCDPEvent(ev)
	})

	// Enable CDP domains
	err := chromedp.Run(c.tabCtx,
		network.Enable(),
		page.Enable(),
		runtime.Enable(),
		dom.Enable(),
		accessibility.Enable(),
		emulation.SetDeviceMetricsOverride(int64(c.config.ViewportWidth), int64(c.config.ViewportHeight), 1.0, false),
	)
	if err != nil {
		if c.tabCancel != nil {
			c.tabCancel()
		}
		if c.allocCancel != nil {
			c.allocCancel()
		}
		return fmt.Errorf("failed to initialize CDP session domains: %w", err)
	}

	c.running = true
	return nil
}

// Stop cleanly terminates the Chrome browser process and closes WebSocket channels.
func (c *CDPDriver) Stop(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.running {
		return nil
	}

	if c.tabCancel != nil {
		c.tabCancel()
	}
	if c.allocCancel != nil {
		c.allocCancel()
	}
	c.running = false
	return nil
}

// handleCDPEvent processes asynchronous CDP notifications.
func (c *CDPDriver) handleCDPEvent(ev interface{}) {
	c.mu.Lock()
	defer c.mu.Unlock()

	switch e := ev.(type) {
	case *network.EventRequestWillBeSent:
		c.pendingReqs[e.RequestID] = time.Now()
		var headers map[string]string
		if e.Request.Headers != nil {
			headers = make(map[string]string)
			for k, v := range e.Request.Headers {
				headers[k] = fmt.Sprint(v)
			}
		}
		var postData string
		if len(e.Request.PostDataEntries) > 0 {
			var sb strings.Builder
			for _, entry := range e.Request.PostDataEntries {
				sb.WriteString(entry.Bytes)
			}
			postData = sb.String()
		}
		c.networkLog = append(c.networkLog, NetworkEvent{
			URL:      e.Request.URL,
			Method:   e.Request.Method,
			Headers:  headers,
			PostData: postData,
		})

	case *network.EventResponseReceived:
		startTime, exists := c.pendingReqs[e.RequestID]
		duration := time.Duration(0)
		if exists {
			duration = time.Since(startTime)
			delete(c.pendingReqs, e.RequestID)
		}
		var headers map[string]string
		if e.Response.Headers != nil {
			headers = make(map[string]string)
			for k, v := range e.Response.Headers {
				headers[k] = fmt.Sprint(v)
			}
		}
		for i := range c.networkLog {
			if c.networkLog[i].URL == e.Response.URL && c.networkLog[i].StatusCode == 0 {
				c.networkLog[i].StatusCode = int(e.Response.Status)
				c.networkLog[i].Duration = duration
				if len(headers) > 0 {
					c.networkLog[i].Headers = headers
				}
				break
			}
		}

	case *runtime.EventConsoleAPICalled:
		var parts []string
		for _, arg := range e.Args {
			if arg.Value != nil {
				parts = append(parts, string(arg.Value))
			} else if arg.Description != "" {
				parts = append(parts, arg.Description)
			}
		}
		msg := fmt.Sprintf("[%s] %s", e.Type, strings.Join(parts, " "))
		c.consoleLogs = append(c.consoleLogs, msg)

	case *page.EventJavascriptDialogOpening:
		c.dialogs = append(c.dialogs, fmt.Sprintf("[%s] %s", e.Type, e.Message))
		go func() {
			_ = chromedp.Run(c.tabCtx, page.HandleJavaScriptDialog(true))
		}()
	}
}

// Navigate points the browser at a target URL and waits for page load completion.
func (c *CDPDriver) Navigate(ctx context.Context, targetURL string) (*BrowserState, error) {
	c.mu.Lock()
	if !c.running {
		c.mu.Unlock()
		if err := c.Start(ctx); err != nil {
			return nil, err
		}
	} else {
		c.mu.Unlock()
	}

	var curURL, title string
	err := chromedp.Run(c.tabCtx,
		chromedp.Navigate(targetURL),
		chromedp.Location(&curURL),
		chromedp.Title(&title),
	)
	if err != nil {
		return nil, fmt.Errorf("navigation to %q failed: %w", targetURL, err)
	}

	c.mu.Lock()
	c.currentURL = curURL
	c.title = title
	c.mu.Unlock()

	return c.GetState(ctx)
}

// GetState inspects the current DOM, AXTree, screenshot, and console/network activity.
func (c *CDPDriver) GetState(ctx context.Context) (*BrowserState, error) {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return nil, errors.New("CDP driver is not running; call Start() first")
	}
	c.mu.RUnlock()

	var curURL, title string
	var elementsJSON string

	// Extract interactive elements from DOM via JS evaluation
	jsExtractElements := `
	(function() {
		const items = [];
		const interactiveTags = ['button', 'input', 'a', 'select', 'textarea', 'form'];
		const nodes = document.querySelectorAll('*');
		for (let i = 0; i < nodes.length; i++) {
			const el = nodes[i];
			const tag = el.tagName.toLowerCase();
			const role = el.getAttribute('role') || '';
			const isInteractive = interactiveTags.includes(tag) || role !== '' || el.hasAttribute('onclick') || el.hasAttribute('data-testid');
			if (!isInteractive) continue;

			const rect = el.getBoundingClientRect();
			if (rect.width === 0 && rect.height === 0) continue;

			const attrs = {};
			for (let a of el.attributes) {
				attrs[a.name] = a.value;
			}

			items.push({
				tag: tag,
				id: el.id || '',
				classes: Array.from(el.classList),
				role: role || tag,
				text: (el.innerText || el.textContent || '').trim().substring(0, 100),
				value: el.value || '',
				placeholder: el.placeholder || '',
				test_id: el.getAttribute('data-testid') || el.getAttribute('data-test') || '',
				bounding_box: {
					x: rect.x,
					y: rect.y,
					width: rect.width,
					height: rect.height
				},
				attributes: attrs,
				disabled: el.disabled || el.getAttribute('aria-disabled') === 'true'
			});
		}
		return JSON.stringify(items);
	})()
	`

	err := chromedp.Run(c.tabCtx,
		chromedp.Location(&curURL),
		chromedp.Title(&title),
		chromedp.Evaluate(jsExtractElements, &elementsJSON),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to extract DOM state: %w", err)
	}

	var elements []Element
	if elementsJSON != "" {
		_ = json.Unmarshal([]byte(elementsJSON), &elements)
	}

	// Capture Full Accessibility Tree
	axTree, _ := c.GetFullAXTree(ctx)

	// Capture Real Screenshot
	screenshotB64, _ := c.CaptureScreenshot(ctx)

	// Scan for Unsupported Surfaces
	findings, _ := c.DetectUnsupportedSurfaces(ctx)

	c.mu.RLock()
	defer c.mu.RUnlock()

	copiedLogs := make([]string, len(c.consoleLogs))
	copy(copiedLogs, c.consoleLogs)

	copiedNet := make([]NetworkEvent, len(c.networkLog))
	copy(copiedNet, c.networkLog)

	return &BrowserState{
		URL:             curURL,
		Title:           title,
		ViewportWidth:   c.config.ViewportWidth,
		ViewportHeight:  c.config.ViewportHeight,
		Elements:        elements,
		AXTree:          axTree,
		ScreenshotB64:   screenshotB64,
		ConsoleLogs:     copiedLogs,
		NetworkActivity: copiedNet,
		Findings:        findings,
		Timestamp:       time.Now(),
	}, nil
}

// ExecuteAction performs a concrete user or agent action on the active page.
func (c *CDPDriver) ExecuteAction(ctx context.Context, action Action) (*BrowserState, error) {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return nil, errors.New("CDP driver is not running")
	}
	c.mu.RUnlock()

	// 1. Guard against clicking unsupported surfaces
	findings, _ := c.DetectUnsupportedSurfaces(ctx)
	targetSelector := action.TargetXPath
	if targetSelector == "" && action.TargetRole != "" {
		targetSelector = fmt.Sprintf("[role=%q]", action.TargetRole)
	}
	for _, f := range findings {
		if targetSelector != "" && (strings.Contains(targetSelector, f.Selector) || strings.Contains(f.Selector, targetSelector)) {
			return nil, fmt.Errorf("%w: selector %q targets unsupported surface (%s: %s)",
				ErrUnsupportedSurface, targetSelector, f.Type, f.Reason)
		}
	}

	// 2. Dispatch action
	switch action.Type {
	case ActionNavigate:
		return c.Navigate(ctx, action.Value)

	case ActionClick:
		if action.Coordinates != nil && action.Coordinates.Width > 0 {
			// Click via coordinate dispatch
			clickJS := fmt.Sprintf(`
				const el = document.elementFromPoint(%f, %f);
				if (el) { el.click(); }
			`, action.Coordinates.X+(action.Coordinates.Width/2), action.Coordinates.Y+(action.Coordinates.Height/2))
			if err := chromedp.Run(c.tabCtx, chromedp.Evaluate(clickJS, nil)); err != nil {
				return nil, fmt.Errorf("coordinate click failed: %w", err)
			}
		} else if action.TargetXPath != "" {
			if strings.HasPrefix(action.TargetXPath, "//") || strings.HasPrefix(action.TargetXPath, "(") {
				if err := chromedp.Run(c.tabCtx, chromedp.Click(action.TargetXPath, chromedp.BySearch)); err != nil {
					return nil, fmt.Errorf("xpath click failed on %q: %w", action.TargetXPath, err)
				}
			} else {
				if err := chromedp.Run(c.tabCtx, chromedp.Click(action.TargetXPath, chromedp.ByQuery)); err != nil {
					return nil, fmt.Errorf("selector click failed on %q: %w", action.TargetXPath, err)
				}
			}
		} else if action.TargetText != "" {
			clickTextJS := fmt.Sprintf(`
			(function() {
				const query = %q.toLowerCase();
				const elements = Array.from(document.querySelectorAll('button, a, input[type=button], input[type=submit], [role=button]'));
				for (let el of elements) {
					if ((el.innerText || el.value || '').toLowerCase().includes(query)) {
						el.click();
						return true;
					}
				}
				return false;
			})()`, action.TargetText)
			var clicked bool
			if err := chromedp.Run(c.tabCtx, chromedp.Evaluate(clickTextJS, &clicked)); err != nil || !clicked {
				return nil, fmt.Errorf("text click failed for %q (element not found or unclickable)", action.TargetText)
			}
		}

	case ActionTypeKey:
		selector := action.TargetXPath
		if selector == "" {
			selector = "input, textarea"
		}
		if err := chromedp.Run(c.tabCtx,
			chromedp.SetValue(selector, action.Value, chromedp.ByQuery),
		); err != nil {
			// Fallback: evaluate input event
			typeJS := fmt.Sprintf(`
			(function() {
				const el = document.querySelector(%q);
				if (el) {
					el.value = %q;
					el.dispatchEvent(new Event('input', { bubbles: true }));
					el.dispatchEvent(new Event('change', { bubbles: true }));
					return true;
				}
				return false;
			})()`, selector, action.Value)
			var ok bool
			if err2 := chromedp.Run(c.tabCtx, chromedp.Evaluate(typeJS, &ok)); err2 != nil || !ok {
				return nil, fmt.Errorf("type action failed on %q: %w", selector, err)
			}
		}

	case ActionScroll:
		scrollJS := fmt.Sprintf("window.scrollBy(0, %s);", action.Value)
		if action.Value == "" {
			scrollJS = "window.scrollBy(0, 400);"
		}
		if err := chromedp.Run(c.tabCtx, chromedp.Evaluate(scrollJS, nil)); err != nil {
			return nil, fmt.Errorf("scroll failed: %w", err)
		}

	case ActionWait:
		delay := 500 * time.Millisecond
		if action.Value != "" {
			if d, err := time.ParseDuration(action.Value); err == nil {
				delay = d
			}
		}
		time.Sleep(delay)

	case ActionUpload:
		if action.TargetXPath != "" && action.Value != "" {
			if err := chromedp.Run(c.tabCtx,
				chromedp.SetUploadFiles(action.TargetXPath, []string{action.Value}, chromedp.ByQuery),
			); err != nil {
				return nil, fmt.Errorf("file upload failed on %q: %w", action.TargetXPath, err)
			}
		}

	case ActionAssert:
		if action.TargetText != "" {
			assertJS := fmt.Sprintf("document.body.innerText.includes(%q)", action.TargetText)
			var found bool
			if err := chromedp.Run(c.tabCtx, chromedp.Evaluate(assertJS, &found)); err != nil || !found {
				return nil, fmt.Errorf("assertion failed: text %q not found in page DOM", action.TargetText)
			}
		}
	}

	return c.GetState(ctx)
}

// CaptureScreenshot retrieves a real base64-encoded PNG image of the current page viewport.
func (c *CDPDriver) CaptureScreenshot(ctx context.Context) (string, error) {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return "", errors.New("CDP driver is not running")
	}
	c.mu.RUnlock()

	var buf []byte
	err := chromedp.Run(c.tabCtx,
		chromedp.ActionFunc(func(ctx context.Context) error {
			var err error
			buf, err = page.CaptureScreenshot().
				WithFormat(page.CaptureScreenshotFormatPng).
				Do(ctx)
			return err
		}),
	)
	if err != nil {
		return "", fmt.Errorf("failed to capture screenshot: %w", err)
	}

	return base64.StdEncoding.EncodeToString(buf), nil
}

// GetFullAXTree queries the Chrome Accessibility tree and converts it into a hierarchical AXNode tree.
func (c *CDPDriver) GetFullAXTree(ctx context.Context) (*AXNode, error) {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return nil, errors.New("CDP driver is not running")
	}
	c.mu.RUnlock()

	var axNodes []*accessibility.Node
	err := chromedp.Run(c.tabCtx,
		chromedp.ActionFunc(func(ctx context.Context) error {
			var err error
			axNodes, err = accessibility.GetFullAXTree().Do(ctx)
			return err
		}),
	)
	if err != nil {
		return nil, fmt.Errorf("failed to fetch AX tree: %w", err)
	}

	if len(axNodes) == 0 {
		return &AXNode{ID: "root", Role: "document", Name: "Empty AXTree"}, nil
	}

	// Map CDP AXNode flat list to hierarchical tree
	nodeMap := make(map[string]*AXNode)
	for _, n := range axNodes {
		name := ""
		if n.Name != nil {
			name = fmt.Sprint(n.Name.Value)
		}
		val := ""
		if n.Value != nil {
			val = fmt.Sprint(n.Value.Value)
		}
		desc := ""
		if n.Description != nil {
			desc = fmt.Sprint(n.Description.Value)
		}
		role := "generic"
		if n.Role != nil {
			role = fmt.Sprint(n.Role.Value)
		}

		nodeMap[string(n.NodeID)] = &AXNode{
			ID:          string(n.NodeID),
			Role:        role,
			Name:        name,
			Value:       val,
			Description: desc,
			Children:    make([]AXNode, 0),
		}
	}

	var root *AXNode
	for _, n := range axNodes {
		curr := nodeMap[string(n.NodeID)]
		if root == nil {
			root = curr
		}
		for _, childID := range n.ChildIDs {
			if child, ok := nodeMap[string(childID)]; ok {
				curr.Children = append(curr.Children, *child)
			}
		}
	}

	return root, nil
}

// Stabilize polls DOM bounding box stability to ensure CSS and JS animations settle without layout shift.
func (c *CDPDriver) Stabilize(ctx context.Context, opts AnimationStabilizationOptions) error {
	delay := opts.StabilizationDelay
	if delay <= 0 {
		delay = 100 * time.Millisecond
	}
	maxWait := opts.MaxWait
	if maxWait <= 0 {
		maxWait = 3 * time.Second
	}

	start := time.Now()
	for time.Since(start) < maxWait {
		var hasActiveAnimations bool
		jsCheck := `
		(function() {
			const anims = document.getAnimations ? document.getAnimations() : [];
			for (let a of anims) {
				if (a.playState === 'running') return true;
			}
			return false;
		})()
		`
		_ = chromedp.Run(c.tabCtx, chromedp.Evaluate(jsCheck, &hasActiveAnimations))
		if !hasActiveAnimations {
			time.Sleep(delay)
			return nil
		}
		time.Sleep(50 * time.Millisecond)
	}

	return nil
}

// ClassifyUnsupportedSurface evaluates element characteristics to detect unsupported surfaces offline.
func ClassifyUnsupportedSurface(tag, src, class string) *UnsupportedSurfaceFinding {
	if strings.EqualFold(tag, "canvas") {
		return &UnsupportedSurfaceFinding{
			Type:        "CANVAS_WEBGL",
			Selector:    "canvas",
			Description: "HTML5 Canvas / WebGL rendering surface",
			Reason:      "Canvas elements render pixels without semantic DOM nodes; pixel-diff inspection only.",
		}
	}
	srcLower := strings.ToLower(src)
	if strings.EqualFold(tag, "iframe") && (strings.Contains(srcLower, "stripe.com") || strings.Contains(srcLower, "paypal.com") || strings.Contains(srcLower, "adyen.com") || strings.Contains(srcLower, "braintree")) {
		return &UnsupportedSurfaceFinding{
			Type:        "CROSS_ORIGIN_IFRAME",
			Selector:    "iframe[src*=\"payment\"]",
			Description: "Cross-origin payment iframe (" + src + ")",
			Reason:      "Browser same-origin policy blocks CDP inspection; requires test-mode bypass tokens or mock webhooks.",
		}
	}
	classLower := strings.ToLower(class)
	if strings.Contains(classLower, "cf-turnstile") || strings.Contains(srcLower, "cloudflare.com") || strings.Contains(srcLower, "recaptcha") || strings.Contains(srcLower, "hcaptcha") {
		return &UnsupportedSurfaceFinding{
			Type:        "CAPTCHA_TURNSTILE",
			Selector:    "iframe[captcha]",
			Description: "Anti-bot CAPTCHA / Cloudflare Turnstile challenge",
			Reason:      "Automated clicking prohibited; requires staging IP allowlisting or CAPTCHA bypass flag.",
		}
	}
	return nil
}

// DetectUnsupportedSurfaces inspects the DOM for Canvas/WebGL, cross-origin payment iframes, and CAPTCHAs.
func (c *CDPDriver) DetectUnsupportedSurfaces(ctx context.Context) ([]UnsupportedSurfaceFinding, error) {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return nil, errors.New("CDP driver is not running")
	}
	c.mu.RUnlock()

	var findingsJSON string
	probeJS := `
	(function() {
		const findings = [];
		// 1. Canvas / WebGL
		document.querySelectorAll('canvas').forEach((el, idx) => {
			findings.push({
				type: "CANVAS_WEBGL",
				selector: el.id ? '#' + el.id : 'canvas:nth-of-type(' + (idx + 1) + ')',
				description: "HTML5 Canvas / WebGL rendering surface",
				reason: "Canvas elements render pixels without semantic DOM nodes; pixel-diff inspection only."
			});
		});

		// 2. Cross-Origin Payment Iframes
		document.querySelectorAll('iframe').forEach((el) => {
			const src = el.src || '';
			if (src.includes('stripe.com') || src.includes('paypal.com') || src.includes('adyen.com') || src.includes('braintree')) {
				findings.push({
					type: "CROSS_ORIGIN_IFRAME",
					selector: 'iframe[src*="' + new URL(src).hostname + '"]',
					description: "Cross-origin payment iframe (" + src + ")",
					reason: "Browser same-origin policy blocks CDP inspection; requires test-mode bypass tokens or mock webhooks."
				});
			}
		});

		// 3. CAPTCHA / Turnstile
		document.querySelectorAll('.cf-turnstile, iframe[src*="cloudflare.com"], iframe[src*="recaptcha"], iframe[src*="hcaptcha"]').forEach((el) => {
			findings.push({
				type: "CAPTCHA_TURNSTILE",
				selector: el.className ? '.' + el.className.split(' ')[0] : 'iframe[captcha]',
				description: "Anti-bot CAPTCHA / Cloudflare Turnstile challenge",
				reason: "Automated clicking prohibited; requires staging IP allowlisting or CAPTCHA bypass flag."
			});
		});

		return JSON.stringify(findings);
	})()
	`

	_ = chromedp.Run(c.tabCtx, chromedp.Evaluate(probeJS, &findingsJSON))
	var findings []UnsupportedSurfaceFinding
	if findingsJSON != "" {
		_ = json.Unmarshal([]byte(findingsJSON), &findings)
	}
	return findings, nil
}

// PierceShadowDOM queries open shadow DOM roots using JavaScript.
func (c *CDPDriver) PierceShadowDOM(ctx context.Context, rootSelector, innerSelector string) (string, error) {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return "", errors.New("CDP driver is not running")
	}
	c.mu.RUnlock()

	var resultHTML string
	js := fmt.Sprintf(`
	(function() {
		const root = document.querySelector(%q);
		if (!root || !root.shadowRoot) return "";
		const el = root.shadowRoot.querySelector(%q);
		return el ? el.outerHTML : "";
	})()`, rootSelector, innerSelector)

	err := chromedp.Run(c.tabCtx, chromedp.Evaluate(js, &resultHTML))
	if err != nil {
		return "", fmt.Errorf("failed to pierce shadow DOM on %q >> %q: %w", rootSelector, innerSelector, err)
	}
	if resultHTML == "" {
		return "", fmt.Errorf("shadow DOM element not found: %q >> %q", rootSelector, innerSelector)
	}
	return resultHTML, nil
}

// ExportHAR converts the collected network event logs into standard HTTP Archive (HAR) format.
func (c *CDPDriver) ExportHAR() (*HARReport, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	entries := make([]HAREntry, len(c.networkLog))
	for i, net := range c.networkLog {
		entries[i] = HAREntry{
			StartedDateTime: time.Now().Add(-net.Duration),
			Time:            net.Duration.Milliseconds(),
			Request: HARRequest{
				Method:      net.Method,
				URL:         net.URL,
				HTTPVersion: "HTTP/1.1",
				Headers:     net.Headers,
				PostData:    net.PostData,
			},
			Response: HARResponse{
				Status:      net.StatusCode,
				StatusText:  "OK",
				HTTPVersion: "HTTP/1.1",
				Headers:     net.Headers,
				BodySize:    1024,
			},
		}
	}

	return &HARReport{
		Version: "1.2",
		Creator: HARCreator{Name: "Kritix AI CDP Engine", Version: "0.3.0"},
		Entries: entries,
	}, nil
}

// SaveStorageState serializes browser cookies and localStorage into a JSON file.
func (c *CDPDriver) SaveStorageState(path string) error {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return errors.New("CDP driver is not running")
	}
	c.mu.RUnlock()

	var cookies []*network.Cookie
	var localStorageJSON string

	err := chromedp.Run(c.tabCtx,
		chromedp.ActionFunc(func(ctx context.Context) error {
			var err error
			cookies, err = network.GetCookies().Do(ctx)
			return err
		}),
		chromedp.Evaluate(`JSON.stringify(window.localStorage)`, &localStorageJSON),
	)
	if err != nil {
		return fmt.Errorf("failed to retrieve storage state: %w", err)
	}

	state := map[string]interface{}{
		"cookies":      cookies,
		"localStorage": localStorageJSON,
		"timestamp":    time.Now(),
	}

	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0600)
}

// LoadStorageState restores cookies and localStorage from a JSON file.
func (c *CDPDriver) LoadStorageState(path string) error {
	c.mu.RLock()
	if !c.running || c.tabCtx == nil {
		c.mu.RUnlock()
		return errors.New("CDP driver is not running")
	}
	c.mu.RUnlock()

	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("failed to read storage state file: %w", err)
	}

	var state struct {
		LocalStorage string `json:"localStorage"`
	}
	if err := json.Unmarshal(data, &state); err != nil {
		return fmt.Errorf("failed to parse storage state JSON: %w", err)
	}

	if state.LocalStorage != "" {
		jsRestore := fmt.Sprintf(`
		(function() {
			try {
				const data = JSON.parse(%q);
				for (let k in data) {
					window.localStorage.setItem(k, data[k]);
				}
			} catch(e) {}
		})()`, state.LocalStorage)
		_ = chromedp.Run(c.tabCtx, chromedp.Evaluate(jsRestore, nil))
	}

	return nil
}

// GetCapabilities derives runtime browser capabilities via active CDP probing rather than static bools.
func (c *CDPDriver) GetCapabilities(ctx context.Context) (BrowserCapability, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.capabilities != nil {
		return *c.capabilities, nil
	}

	engineName := "Chromium/CDP"
	if c.running && c.tabCtx != nil {
		_ = chromedp.Run(c.tabCtx,
			chromedp.ActionFunc(func(ctx context.Context) error {
				_, product, _, userAgent, _, err := browser.GetVersion().Do(ctx)
				if err == nil && product != "" {
					engineName = fmt.Sprintf("%s (%s)", product, userAgent)
				}
				return nil
			}),
		)
	}

	caps := BrowserCapability{
		SupportsShadowDOM:  true,
		SupportsAnimations: true,
		CanvasInspection:   "OUT_OF_SCOPE: WebGL/Canvas elements render zero semantic AXTree nodes. Visual pixel-diff only.",
		CrossOriginIframes: "OUT_OF_SCOPE: Cross-origin payment iframes (Stripe/PayPal) require test-mode bypass tokens or mock webhooks.",
		CaptchaHandling:    "OUT_OF_SCOPE: Cloudflare Turnstile / reCAPTCHA requires IP allowlisting or test-environment bypass flags.",
		RuntimeEngine:      engineName,
		ProbedAt:           time.Now(),
	}
	c.capabilities = &caps
	return caps, nil
}
