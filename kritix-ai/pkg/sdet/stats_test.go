package sdet

import (
	"math"
	"testing"
)

func TestStats_ClopperPearsonUpper95(t *testing.T) {
	tests := []struct {
		k           int
		n           int
		minExpected float64
		maxExpected float64
	}{
		// For k=0, n=300: 1 - 0.05^(1/300) = 1 - 0.990059... ≈ 0.00994 (< 1.0%)
		{0, 300, 0.0095, 0.0100},
		// For k=0, n=35: 1 - 0.05^(1/35) ≈ 0.0819 (8.19%)
		{0, 35, 0.0800, 0.0850},
		// For k=0, n=400: 1 - 0.05^(1/400) ≈ 0.00746 (0.75%)
		{0, 400, 0.0070, 0.0080},
		// For k=1, n=500: bound ≈ 0.0094
		{1, 500, 0.0085, 0.0110},
	}

	for _, tt := range tests {
		bound, err := ClopperPearsonUpper95(tt.k, tt.n)
		if err != nil {
			t.Fatalf("unexpected error for k=%d, n=%d: %v", tt.k, tt.n, err)
		}
		if bound < tt.minExpected || bound > tt.maxExpected {
			t.Errorf("ClopperPearsonUpper95(%d, %d) = %f; expected between %f and %f",
				tt.k, tt.n, bound, tt.minExpected, tt.maxExpected)
		}
	}
}

func TestStats_WilsonScoreUpper95(t *testing.T) {
	tests := []struct {
		k           int
		n           int
		minExpected float64
		maxExpected float64
	}{
		{0, 300, 0.0080, 0.0100},
		{0, 35, 0.0600, 0.0800},
		{0, 400, 0.0060, 0.0080},
	}

	for _, tt := range tests {
		bound, err := WilsonScoreUpper95(tt.k, tt.n)
		if err != nil {
			t.Fatalf("unexpected error for k=%d, n=%d: %v", tt.k, tt.n, err)
		}
		if bound < tt.minExpected || bound > tt.maxExpected {
			t.Errorf("WilsonScoreUpper95(%d, %d) = %f; expected between %f and %f",
				tt.k, tt.n, bound, tt.minExpected, tt.maxExpected)
		}
	}
}

func TestStats_InvalidInputs(t *testing.T) {
	if _, err := ClopperPearsonUpper95(0, 0); err == nil {
		t.Errorf("expected error for n=0")
	}
	if _, err := ClopperPearsonUpper95(5, 4); err == nil {
		t.Errorf("expected error for k > n")
	}
	if _, err := WilsonScoreUpper95(0, -1); err == nil {
		t.Errorf("expected error for n < 0")
	}
}

func TestStats_BinomialPMF(t *testing.T) {
	// n=10, k=5, p=0.5: PMF = 252 / 1024 ≈ 0.24609375
	val := binomialPMF(5, 10, 0.5)
	if math.Abs(val-0.24609375) > 1e-6 {
		t.Errorf("binomialPMF(5, 10, 0.5) = %f, expected 0.24609375", val)
	}
}
