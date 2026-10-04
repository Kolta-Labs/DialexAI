package sdet

import (
	"fmt"
	"math"
)

// ClopperPearsonUpper95 calculates the exact one-sided 95% Clopper-Pearson upper bound
// for a binomial proportion with k successes (or failures) out of n trials.
// For k=0, the formula simplifies to: 1 - (alpha)^(1/n) where alpha = 0.05.
func ClopperPearsonUpper95(k int, n int) (float64, error) {
	if n <= 0 {
		return 1.0, fmt.Errorf("sample size n must be > 0 (got %d)", n)
	}
	if k < 0 || k > n {
		return 1.0, fmt.Errorf("observed events k (%d) must be between 0 and n (%d)", k, n)
	}

	alpha := 0.05 // 95% one-sided confidence

	if k == 0 {
		// Exact formula for 0 observed events
		return 1.0 - math.Pow(alpha, 1.0/float64(n)), nil
	}

	// For k > 0, solve for p where P(X <= k) = alpha using beta distribution quantile / root finding
	// Beta distribution quantile: I_p(k+1, n-k) = 1 - alpha
	// We use binary search / Newton-Raphson to solve for p in [0, 1]
	pLow := float64(k) / float64(n)
	pHigh := 1.0

	for iter := 0; iter < 100; iter++ {
		pMid := (pLow + pHigh) / 2.0
		// Cumulative binomial probability P(X <= k) for parameter pMid
		cdf := binomialCDF(k, n, pMid)
		if cdf > alpha {
			// p is too low
			pLow = pMid
		} else {
			// p is too high
			pHigh = pMid
		}
	}

	return (pLow + pHigh) / 2.0, nil
}

// WilsonScoreUpper95 calculates the one-sided 95% Wilson score upper bound.
// z = 1.6448536269514722 for 95% one-sided confidence (or 1.9599639845400542 for two-sided 95%).
func WilsonScoreUpper95(k int, n int) (float64, error) {
	if n <= 0 {
		return 1.0, fmt.Errorf("sample size n must be > 0 (got %d)", n)
	}
	if k < 0 || k > n {
		return 1.0, fmt.Errorf("observed events k (%d) must be between 0 and n (%d)", k, n)
	}

	z := 1.6448536269514722 // one-sided 95% critical value
	p := float64(k) / float64(n)
	nf := float64(n)

	denominator := 1.0 + (z * z / nf)
	center := p + (z * z / (2.0 * nf))
	spread := z * math.Sqrt((p*(1.0-p)/nf) + (z*z/(4.0*nf*nf)))

	upper := (center + spread) / denominator
	if upper > 1.0 {
		upper = 1.0
	}
	return upper, nil
}

// binomialCDF computes P(X <= k) for X ~ Binomial(n, p).
func binomialCDF(k int, n int, p float64) float64 {
	if k >= n {
		return 1.0
	}
	if k < 0 {
		return 0.0
	}
	sum := 0.0
	for i := 0; i <= k; i++ {
		sum += binomialPMF(i, n, p)
	}
	return sum
}

func binomialPMF(k int, n int, p float64) float64 {
	if p == 0.0 {
		if k == 0 {
			return 1.0
		}
		return 0.0
	}
	if p == 1.0 {
		if k == n {
			return 1.0
		}
		return 0.0
	}
	// Use log-gamma to avoid overflow in factorials
	logComb, _ := logCombinations(n, k)
	logProb := logComb + float64(k)*math.Log(p) + float64(n-k)*math.Log(1.0-p)
	return math.Exp(logProb)
}

func logCombinations(n, k int) (float64, error) {
	if k < 0 || k > n {
		return 0, fmt.Errorf("invalid combinations")
	}
	l1, _ := math.Lgamma(float64(n + 1))
	l2, _ := math.Lgamma(float64(k + 1))
	l3, _ := math.Lgamma(float64(n - k + 1))
	return l1 - l2 - l3, nil
}
