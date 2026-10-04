package driver

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
)

// ActionType enumerates user interaction primitives.
type ActionType string

const (
	ActionNavigate ActionType = "navigate"
	ActionClick    ActionType = "click"
	ActionTypeKey  ActionType = "type"
	ActionScroll   ActionType = "scroll"
	ActionHover    ActionType = "hover"
	ActionWait     ActionType = "wait"
	ActionAssert   ActionType = "assert"
	ActionUpload   ActionType = "upload"
)

// Rect represents element bounding box coordinates on the viewport.
type Rect struct {
	X      float64 `json:"x"`
	Y      float64 `json:"y"`
	Width  float64 `json:"width"`
	Height float64 `json:"height"`
}

// Element models an interactive element on the page.
type Element struct {
	Tag           string            `json:"tag"`
	ID            string            `json:"id,omitempty"`
	Classes       []string          `json:"classes,omitempty"`
	Role          string            `json:"role,omitempty"`
	Text          string            `json:"text,omitempty"`
	Value         string            `json:"value,omitempty"`
	Placeholder   string            `json:"placeholder,omitempty"`
	XPath         string            `json:"xpath,omitempty"`
	TestID        string            `json:"test_id,omitempty"`
	ContainerID   string            `json:"container_id,omitempty"`
	ContainerRole string            `json:"container_role,omitempty"`
	ActionIntent  string            `json:"action_intent,omitempty"`
	BoundingBox   Rect              `json:"bounding_box"`
	Attributes    map[string]string `json:"attributes,omitempty"`
	Disabled      bool              `json:"disabled"`
}

// AXNode models an Accessibility Tree node.
type AXNode struct {
	ID          string   `json:"id"`
	Role        string   `json:"role"`
	Name        string   `json:"name"`
	Value       string   `json:"value,omitempty"`
	Description string   `json:"description,omitempty"`
	Children    []AXNode `json:"children,omitempty"`
}

// Action encapsulates a user or agent interaction.
type Action struct {
	Type        ActionType `json:"type"`
	TargetXPath string     `json:"target_xpath,omitempty"`
	TargetRole  string     `json:"target_role,omitempty"`
	TargetText  string     `json:"target_text,omitempty"`
	Coordinates *Rect      `json:"coordinates,omitempty"`
	Value       string     `json:"value,omitempty"`
	Description string     `json:"description,omitempty"`
	Timestamp   time.Time  `json:"timestamp"`
}

// NetworkEvent captures an HTTP request/response through the driver.
type NetworkEvent struct {
	URL        string            `json:"url"`
	Method     string            `json:"method"`
	StatusCode int               `json:"status_code"`
	Duration   time.Duration     `json:"duration"`
	Headers    map[string]string `json:"headers,omitempty"`
	PostData   string            `json:"post_data,omitempty"`
	Error      string            `json:"error,omitempty"`
}

// BrowserState captures the holistic observation of the browser at an instant.
type BrowserState struct {
	URL             string                      `json:"url"`
	Title           string                      `json:"title"`
	ViewportWidth   int                         `json:"viewport_width"`
	ViewportHeight  int                         `json:"viewport_height"`
	Elements        []Element                   `json:"elements"`
	AXTree          *AXNode                     `json:"ax_tree,omitempty"`
	ScreenshotB64   string                      `json:"screenshot_b64,omitempty"`
	ConsoleLogs     []string                    `json:"console_logs"`
	NetworkActivity []NetworkEvent              `json:"network_activity"`
	Findings        []UnsupportedSurfaceFinding `json:"findings,omitempty"`
	Timestamp       time.Time                   `json:"timestamp"`
}

// SurfaceType enumerates non-semantic or un-automatable surfaces.
type SurfaceType string

const (
	SurfaceCanvasWebGL       SurfaceType = "CANVAS_WEBGL"
	SurfaceCrossOriginIframe SurfaceType = "CROSS_ORIGIN_IFRAME"
	SurfaceCaptchaTurnstile  SurfaceType = "CAPTCHA_TURNSTILE"
)

// UnsupportedSurfaceFinding explicitly flags surfaces that cannot be driven via DOM actions.
type UnsupportedSurfaceFinding struct {
	Type        SurfaceType `json:"type"`
	Selector    string      `json:"selector"`
	Description string      `json:"description"`
	Reason      string      `json:"reason"`
}

var ErrUnsupportedSurface = errors.New("UNSUPPORTED_SURFACE: target element is a non-semantic surface; blind clicking is prohibited")

// HARReport captures HTTP Archive 1.2 network log data.
type HARReport struct {
	Version string     `json:"version"`
	Creator HARCreator `json:"creator"`
	Entries []HAREntry `json:"entries"`
}

type HARCreator struct {
	Name    string `json:"name"`
	Version string `json:"version"`
}

type HAREntry struct {
	StartedDateTime time.Time   `json:"startedDateTime"`
	Time            int64       `json:"time"` // milliseconds
	Request         HARRequest  `json:"request"`
	Response        HARResponse `json:"response"`
}

type HARRequest struct {
	Method      string            `json:"method"`
	URL         string            `json:"url"`
	HTTPVersion string            `json:"httpVersion"`
	Headers     map[string]string `json:"headers"`
	PostData    string            `json:"postData,omitempty"`
}

type HARResponse struct {
	Status      int               `json:"status"`
	StatusText  string            `json:"statusText"`
	HTTPVersion string            `json:"httpVersion"`
	Headers     map[string]string `json:"headers"`
	BodySize    int64             `json:"bodySize"`
}

// BrowserCapability declares supported capabilities and explicit out-of-scope boundaries.
type BrowserCapability struct {
	SupportsShadowDOM    bool   `json:"supports_shadow_dom"`
	SupportsAnimations   bool   `json:"supports_animations"`
	CanvasInspection     string `json:"canvas_inspection"`
	CrossOriginIframes   string `json:"cross_origin_iframes"`
	CaptchaHandling      string `json:"captcha_handling"`
	RuntimeEngine        string `json:"runtime_engine"`
	ProbedAt             time.Time `json:"probed_at"`
}

// AnimationStabilizationOptions configures waits for CSS and JavaScript animation settlement.
type AnimationStabilizationOptions struct {
	WaitForNetworkIdle bool          `json:"wait_for_network_idle"`
	StabilizationDelay time.Duration `json:"stabilization_delay"`
	MaxWait            time.Duration `json:"max_wait"`
}

// BrowserDriver represents the interface for controlling the browser.
type BrowserDriver interface {
	Start(ctx context.Context) error
	Stop(ctx context.Context) error
	Navigate(ctx context.Context, url string) (*BrowserState, error)
	GetState(ctx context.Context) (*BrowserState, error)
	ExecuteAction(ctx context.Context, action Action) (*BrowserState, error)
	CaptureScreenshot(ctx context.Context) (string, error)
	Stabilize(ctx context.Context, opts AnimationStabilizationOptions) error
}

// FindElementBySemanticMatch searches for matching elements using text, role, or testID.
func (s *BrowserState) FindElementBySemanticMatch(query string) *Element {
	queryLower := strings.ToLower(query)
	for i := range s.Elements {
		e := &s.Elements[i]
		if strings.EqualFold(e.TestID, query) {
			return e
		}
		if strings.EqualFold(e.ID, query) {
			return e
		}
		if strings.Contains(strings.ToLower(e.Text), queryLower) {
			return e
		}
		if strings.EqualFold(e.Role, query) {
			return e
		}
	}
	return nil
}

// ShadowDOMPierceSelector formats a selector to pierce open shadow roots.
func ShadowDOMPierceSelector(rootSelector, innerSelector string) string {
	if rootSelector == "" {
		return innerSelector
	}
	return fmt.Sprintf("%s >> internal:control=enter-shadow >> %s", rootSelector, innerSelector)
}
