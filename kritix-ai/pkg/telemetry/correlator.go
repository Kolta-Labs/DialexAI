package telemetry

import (
	"fmt"
	"strings"
)

// ObservabilityTrace represents correlated distributed tracing headers from network activity.
type ObservabilityTrace struct {
	TraceID     string `json:"trace_id"`
	SpanID      string `json:"span_id,omitempty"`
	SentryEvent string `json:"sentry_event_id,omitempty"`
	RequestID   string `json:"request_id,omitempty"`
	Service     string `json:"service,omitempty"`
	TraceURL    string `json:"trace_url,omitempty"`
}

// Correlator parses headers and links frontend failures to backend observability platforms.
type Correlator struct {
	baseJaegerURL  string
	baseDatadogURL string
	baseSentryURL  string
}

// NewCorrelator constructs an observability correlator.
func NewCorrelator(jaegerURL, datadogURL, sentryURL string) *Correlator {
	return &Correlator{
		baseJaegerURL:  strings.TrimSuffix(jaegerURL, "/"),
		baseDatadogURL: strings.TrimSuffix(datadogURL, "/"),
		baseSentryURL:  strings.TrimSuffix(sentryURL, "/"),
	}
}

// ExtractTrace inspects response headers for distributed tracing identifiers.
func (c *Correlator) ExtractTrace(headers map[string]string) *ObservabilityTrace {
	trace := &ObservabilityTrace{}

	for k, v := range headers {
		keyLower := strings.ToLower(k)
		switch keyLower {
		case "traceparent":
			// W3C format: version-trace_id-parent_id-trace_flags
			parts := strings.Split(v, "-")
			if len(parts) >= 3 {
				trace.TraceID = parts[1]
				trace.SpanID = parts[2]
			}
		case "x-b3-traceid", "x-trace-id":
			if trace.TraceID == "" {
				trace.TraceID = v
			}
		case "x-request-id":
			trace.RequestID = v
		case "x-sentry-id", "sentry-trace":
			trace.SentryEvent = v
		case "x-service-name":
			trace.Service = v
		}
	}

	if trace.TraceID == "" && trace.RequestID == "" && trace.SentryEvent == "" {
		return nil
	}

	// Generate trace URL
	if c.baseDatadogURL != "" && trace.TraceID != "" {
		trace.TraceURL = fmt.Sprintf("%s/apm/trace/%s", c.baseDatadogURL, trace.TraceID)
	} else if c.baseJaegerURL != "" && trace.TraceID != "" {
		trace.TraceURL = fmt.Sprintf("%s/trace/%s", c.baseJaegerURL, trace.TraceID)
	}

	return trace
}

// FormatTraceSummary renders markdown for inclusion in defect tickets.
func (t *ObservabilityTrace) FormatTraceSummary() string {
	var sb strings.Builder
	sb.WriteString("### 🔭 Correlated Backend Observability Traces\n")
	if t.TraceID != "" {
		sb.WriteString(fmt.Sprintf("- **Distributed Trace ID**: `%s`\n", t.TraceID))
	}
	if t.SpanID != "" {
		sb.WriteString(fmt.Sprintf("- **Span ID**: `%s`\n", t.SpanID))
	}
	if t.RequestID != "" {
		sb.WriteString(fmt.Sprintf("- **Request ID**: `%s`\n", t.RequestID))
	}
	if t.SentryEvent != "" {
		sb.WriteString(fmt.Sprintf("- **Sentry Issue / Event ID**: `%s`\n", t.SentryEvent))
	}
	if t.TraceURL != "" {
		sb.WriteString(fmt.Sprintf("- **Direct APM Trace Link**: [View Waterfall](%s)\n", t.TraceURL))
	}
	sb.WriteString("\n")
	return sb.String()
}
