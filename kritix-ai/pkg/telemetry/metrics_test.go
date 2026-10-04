package telemetry

import (
	"strings"
	"testing"
)

func TestMetricsExposition(t *testing.T) {
	m := NewMetrics()
	m.Add(`kritix_tests_total{result="pass"}`, 2)
	m.Set(`kritix_flake_ratio`, 0.25)
	var sb strings.Builder
	m.WriteTo(&sb)
	if sb.String() != "kritix_flake_ratio 0.25\nkritix_tests_total{result=\"pass\"} 2\n" {
		t.Fatalf("got %q", sb.String())
	}
}
