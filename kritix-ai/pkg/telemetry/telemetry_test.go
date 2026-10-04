package telemetry

import (
	"strings"
	"testing"
)

func TestExtractTraceFromW3CHeader(t *testing.T) {
	correlator := NewCorrelator("https://jaeger.internal", "https://app.datadoghq.com", "https://sentry.io")

	headers := map[string]string{
		"traceparent":  "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
		"x-request-id": "req-98765",
		"sentry-trace": "4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",
	}

	trace := correlator.ExtractTrace(headers)
	if trace == nil {
		t.Fatalf("expected trace extracted from headers")
	}

	if trace.TraceID != "4bf92f3577b34da6a3ce929d0e0e4736" {
		t.Errorf("expected TraceID 4bf92f3577b34da6a3ce929d0e0e4736, got %s", trace.TraceID)
	}

	if trace.SpanID != "00f067aa0ba902b7" {
		t.Errorf("expected SpanID 00f067aa0ba902b7, got %s", trace.SpanID)
	}

	if trace.RequestID != "req-98765" {
		t.Errorf("expected RequestID req-98765, got %s", trace.RequestID)
	}

	summary := trace.FormatTraceSummary()
	if !strings.Contains(summary, "Distributed Trace ID") {
		t.Errorf("summary missing trace ID title: %s", summary)
	}
	if !strings.Contains(summary, "https://app.datadoghq.com/apm/trace/4bf92f3577b34da6a3ce929d0e0e4736") {
		t.Errorf("summary missing datadog link: %s", summary)
	}
}
