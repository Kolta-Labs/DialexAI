package telemetry

import (
	"fmt"
	"io"
	"sort"
	"sync"
)

// Metrics is a tiny Prometheus text-format registry (gauges/counters keyed by name+labels).
// Serve WriteTo from any /metrics handler; OTel collectors scrape the same format.
type Metrics struct {
	mu   sync.Mutex
	vals map[string]float64
}

func NewMetrics() *Metrics { return &Metrics{vals: map[string]float64{}} }

// Add increments a series. series is the full exposition key, e.g. `kritix_tests_total{tenant="a",result="pass"}`.
func (m *Metrics) Add(series string, delta float64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.vals[series] += delta
}

// Set overwrites a series (use for gauges such as flake ratio or cost per test).
func (m *Metrics) Set(series string, v float64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.vals[series] = v
}

// WriteTo renders the Prometheus text exposition format, sorted for stable output.
func (m *Metrics) WriteTo(w io.Writer) (int64, error) {
	m.mu.Lock()
	keys := make([]string, 0, len(m.vals))
	for k := range m.vals {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var total int64
	for _, k := range keys {
		n, err := fmt.Fprintf(w, "%s %g\n", k, m.vals[k])
		total += int64(n)
		if err != nil {
			m.mu.Unlock()
			return total, err
		}
	}
	m.mu.Unlock()
	return total, nil
}
