package benchmark

import (
	"math"
)

// CalculateScore computes the weighted aggregate quality score Q in [0.0, 10.0].
// Weights: Factuality 30%, Blind Spots 25%, Trade-Offs 25%, Actionability 20%.
func CalculateScore(factuality, blindSpots, tradeOffs, actionability float64) float64 {
	score := (0.30 * factuality) + (0.25 * blindSpots) + (0.25 * tradeOffs) + (0.20 * actionability)
	return math.Round(score*100) / 100
}

// ComputeSummary aggregates benchmark runs into scientific metrics and hypothesis tests.
func ComputeSummary(runs []BenchmarkRun) BenchmarkSummary {
	n := len(runs)
	if n == 0 {
		return BenchmarkSummary{}
	}

	councilWins := 0
	soloWins := 0
	ties := 0
	deltaQs := make([]float64, n)

	var sumFactDelta, sumBlindDelta, sumTradeDelta, sumActionDelta float64

	for i, r := range runs {
		delta := r.CouncilTotalScore - r.SoloTotalScore
		deltaQs[i] = delta

		switch r.Winner {
		case "COUNCIL":
			councilWins++
		case "SOLO":
			soloWins++
		default:
			ties++
		}

		// Calculate per-metric deltas
		var cFact, sFact, cBlind, sBlind, cTrade, sTrade, cAction, sAction float64
		for _, ev := range r.Evaluations {
			for _, ms := range ev.CouncilScores {
				switch ms.Dimension {
				case MetricFactuality:
					cFact += ms.Score
				case MetricBlindSpots:
					cBlind += ms.Score
				case MetricTradeOffs:
					cTrade += ms.Score
				case MetricActionability:
					cAction += ms.Score
				}
			}
			for _, ms := range ev.SoloScores {
				switch ms.Dimension {
				case MetricFactuality:
					sFact += ms.Score
				case MetricBlindSpots:
					sBlind += ms.Score
				case MetricTradeOffs:
					sTrade += ms.Score
				case MetricActionability:
					sAction += ms.Score
				}
			}
		}

		evCount := float64(len(r.Evaluations))
		if evCount > 0 {
			sumFactDelta += (cFact - sFact) / evCount
			sumBlindDelta += (cBlind - sBlind) / evCount
			sumTradeDelta += (cTrade - sTrade) / evCount
			sumActionDelta += (cAction - sAction) / evCount
		}
	}

	meanDeltaQ := Mean(deltaQs)
	councilWinRate := float64(councilWins) / float64(n)

	// Perform paired Student's t-test if n >= 2
	var pValue float64 = 1.0
	isStatSignificant := false

	if n >= 2 {
		stdDev := SampleStandardDeviation(deltaQs, meanDeltaQ)
		if stdDev > 0 {
			tStat := meanDeltaQ / (stdDev / math.Sqrt(float64(n)))
			df := float64(n - 1)
			pValue = TwoTailedTTestPValue(tStat, df)
			if pValue < 0.05 && meanDeltaQ > 0 {
				isStatSignificant = true
			}
		}
	} else if meanDeltaQ > 0.5 {
		// Single run with clear win
		pValue = 0.5
	}

	return BenchmarkSummary{
		TotalRuns:          n,
		CouncilWins:        councilWins,
		SoloWins:           soloWins,
		Ties:               ties,
		CouncilWinRate:     math.Round(councilWinRate*1000) / 1000,
		MeanDeltaQ:         math.Round(meanDeltaQ*100) / 100,
		PValue:             math.Round(pValue*10000) / 10000,
		IsStatSignificant:  isStatSignificant,
		AvgFactualityDelta: math.Round((sumFactDelta/float64(n))*100) / 100,
		AvgBlindSpotDelta:  math.Round((sumBlindDelta/float64(n))*100) / 100,
		AvgTradeOffDelta:   math.Round((sumTradeDelta/float64(n))*100) / 100,
		AvgActionDelta:     math.Round((sumActionDelta/float64(n))*100) / 100,
	}
}

// Mean computes the arithmetic average of a float64 slice.
func Mean(xs []float64) float64 {
	if len(xs) == 0 {
		return 0
	}
	sum := 0.0
	for _, x := range xs {
		sum += x
	}
	return sum / float64(len(xs))
}

// SampleStandardDeviation computes the unbiased sample standard deviation (N-1).
func SampleStandardDeviation(xs []float64, mean float64) float64 {
	if len(xs) <= 1 {
		return 0
	}
	sumSq := 0.0
	for _, x := range xs {
		diff := x - mean
		sumSq += diff * diff
	}
	return math.Sqrt(sumSq / float64(len(xs)-1))
}

// TwoTailedTTestPValue computes the two-tailed p-value for a given t-statistic and degrees of freedom df.
// Grounded in the Regularized Incomplete Beta function:
// p = I_x(df/2, 1/2) where x = df / (df + t^2).
func TwoTailedTTestPValue(tStat, df float64) float64 {
	if df <= 0 {
		return 1.0
	}
	t2 := tStat * tStat
	x := df / (df + t2)
	a := df / 2.0
	b := 0.5

	pValue := IncompleteBeta(a, b, x)
	if pValue < 0.0 {
		pValue = 0.0
	}
	if pValue > 1.0 {
		pValue = 1.0
	}
	return pValue
}

// IncompleteBeta computes the regularized incomplete beta function I_x(a, b) using continued fractions.
func IncompleteBeta(a, b, x float64) float64 {
	if x <= 0.0 {
		return 0.0
	}
	if x >= 1.0 {
		return 1.0
	}

	// Factors front term: x^a * (1-x)^b / (a * Beta(a, b))
	lnBeta, _ := math.Lgamma(a)
	lnBetaB, _ := math.Lgamma(b)
	lnBetaAB, _ := math.Lgamma(a + b)
	lnFactor := a*math.Log(x) + b*math.Log(1.0-x) - (lnBeta + lnBetaB - lnBetaAB)
	front := math.Exp(lnFactor) / a

	// Continued fraction evaluation via modified Lentz's method
	if x < (a+1.0)/(a+b+2.0) {
		return front * betaContinuedFraction(a, b, x)
	}
	// Symmetry relation: I_x(a, b) = 1 - I_{1-x}(b, a)
	lnFactorSym := b*math.Log(1.0-x) + a*math.Log(x) - (lnBeta + lnBetaB - lnBetaAB)
	frontSym := math.Exp(lnFactorSym) / b
	return 1.0 - frontSym*betaContinuedFraction(b, a, 1.0-x)
}

func betaContinuedFraction(a, b, x float64) float64 {
	const maxIterations = 200
	const tiny = 1e-30
	const epsilon = 1e-12

	c := 1.0
	d := 1.0 - (a+b)*x/(a+1.0)
	if math.Abs(d) < tiny {
		d = tiny
	}
	d = 1.0 / d
	f := d

	for m := 1; m <= maxIterations; m++ {
		mf := float64(m)
		// Even step
		num := mf * (b - mf) * x / ((a + 2.0*mf - 1.0) * (a + 2.0*mf))
		d = 1.0 + num*d
		if math.Abs(d) < tiny {
			d = tiny
		}
		c = 1.0 + num/c
		if math.Abs(c) < tiny {
			c = tiny
		}
		d = 1.0 / d
		f *= c * d

		// Odd step
		num = -(a + mf) * (a + b + mf) * x / ((a + 2.0*mf) * (a + 2.0*mf + 1.0))
		d = 1.0 + num*d
		if math.Abs(d) < tiny {
			d = tiny
		}
		c = 1.0 + num/c
		if math.Abs(c) < tiny {
			c = tiny
		}
		d = 1.0 / d
		delta := c * d
		f *= delta

		if math.Abs(delta-1.0) < epsilon {
			break
		}
	}
	return f
}
