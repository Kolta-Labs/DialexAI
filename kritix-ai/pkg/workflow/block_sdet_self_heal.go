package workflow

import (
	"context"
	"errors"
	"fmt"

	"kritix/pkg/driver"
	"kritix/pkg/sdet"
)

// SDETSelfHealBlock repairs broken or mutated test selectors using AXTree role and geometric anchors.
type SDETSelfHealBlock struct{}

func (b *SDETSelfHealBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "sdet.self-heal",
		Name:        "Heal Mutated Selectors",
		Category:    "sdet",
		Description: "Computes robust multi-strategy selector repairs using AXTree roles and visual geometry.",
	}
}

func (b *SDETSelfHealBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	brokenVal, ok := bCtx.Get("broken_selectors")
	var brokenFingerprints []sdet.ElementFingerprint

	if ok && brokenVal != nil {
		if fps, ok := brokenVal.([]sdet.ElementFingerprint); ok {
			brokenFingerprints = fps
		}
	}

	if len(brokenFingerprints) == 0 {
		return &BlockResult{
			BlockID: "sdet.self-heal",
			Status:  StatusFailed,
			Message: "Missing input: 'broken_selectors' required for locator self-healing",
			Error:   errors.New("missing broken selectors"),
		}, errors.New("missing broken selectors")
	}

	var liveElements []driver.Element
	if elVal, ok := bCtx.Get("elements"); ok && elVal != nil {
		if els, ok := elVal.([]driver.Element); ok {
			liveElements = els
		}
	}

	// Strict by default: an exact locator that is gone is a regression, not something to click around.
	// Advisory/permissive must be requested explicitly via the `heal_mode` context key.
	mode := sdet.HealModeStrict
	if v, ok := bCtx.Get("heal_mode"); ok {
		switch m := sdet.HealingMode(fmt.Sprint(v)); m {
		case sdet.HealModeStrict, sdet.HealModeAdvisory, sdet.HealModePermissive:
			mode = m
		default:
			err := fmt.Errorf("unknown heal_mode %q (use strict, advisory or permissive)", v)
			return &BlockResult{BlockID: "sdet.self-heal", Status: StatusFailed, Message: err.Error(), Error: err}, err
		}
	}

	registry := sdet.NewSelfHealingLocatorRegistry()
	healedCount, regressions := 0, 0
	var healedArtifacts, proposed []string

	for _, fp := range brokenFingerprints {
		registry.RegisterFingerprint(fp)
		res := registry.ResolveWithMode(mode, fp.ID, liveElements)
		switch {
		case res == nil:
		case res.Status == sdet.StatusHealed:
			healedCount++
			healedArtifacts = append(healedArtifacts, fmt.Sprintf("%s -> %s (conf: %.2f)", fp.ID, res.HealedSelector, res.ConfidenceScore))
		case res.Status == sdet.StatusRegressionFail:
			regressions++
			if res.ProposedSelectorPatch != "" {
				proposed = append(proposed, fmt.Sprintf("%s (conf: %.2f, risk: %s)\n%s", fp.ID, res.ConfidenceScore, res.RiskLevel, res.ProposedSelectorPatch))
			}
		}
	}

	bCtx.Set("healed_selectors", healedArtifacts)
	bCtx.Set("proposed_selector_patches", proposed)

	if healedCount == 0 {
		msg := "Self-healing failed: 0 selectors could be reliably repaired"
		if regressions > 0 {
			msg = fmt.Sprintf("Regression detected (%s mode): %d locator(s) missing, %d patch(es) proposed for human review; nothing was clicked or healed", mode, regressions, len(proposed))
		}
		err := errors.New(msg)
		return &BlockResult{
			BlockID: "sdet.self-heal",
			Status:  StatusFailed,
			Message: msg,
			Error:   err,
			Data:    map[string]interface{}{"regressions": regressions, "proposed_patches": proposed, "mode": string(mode)},
		}, err
	}

	return &BlockResult{
		BlockID: "sdet.self-heal",
		Status:  StatusPassedWithHealing,
		Message: fmt.Sprintf("Healed %d broken selector(s) (%s mode); human review required before merge", healedCount, mode),
		Data: map[string]interface{}{
			"healed_count": healedCount,
			"repairs":      healedArtifacts,
			"mode":         string(mode),
		},
	}, nil
}
