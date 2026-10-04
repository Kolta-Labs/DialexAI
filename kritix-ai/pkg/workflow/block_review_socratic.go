package workflow

import (
	"context"
	"errors"
	"fmt"

	"kritix/pkg/driver"
	"kritix/pkg/model"
	"kritix/pkg/spec"
)

// ReviewSocraticBlock executes multi-model Socratic debate and Gherkin step binding validation.
type ReviewSocraticBlock struct{}

func (b *ReviewSocraticBlock) Descriptor() BlockDescriptor {
	return BlockDescriptor{
		ID:          "review.socratic",
		Name:        "Socratic Cross-Model Review",
		Category:    "review",
		Description: "Convenes Socratic QA council to interrogate acceptance criteria, discover edge cases, and validate Gherkin step bindings.",
	}
}

func (b *ReviewSocraticBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	var story spec.Story

	if val, ok := bCtx.Get("bundle"); ok && val != nil {
		if bundle, ok := val.(*spec.MultiArtifactBundle); ok {
			story = spec.Story{
				ID:          bundle.TicketID,
				Title:       "Verification of " + bundle.TicketID,
				Description: bundle.FeatureDesignDoc,
			}
		}
	} else if val, ok := bCtx.Get("story"); ok && val != nil {
		if s, ok := val.(spec.Story); ok {
			story = s
		}
	}

	if story.Title == "" && len(story.AcceptanceCriteria) == 0 {
		return &BlockResult{
			BlockID: "review.socratic",
			Status:  StatusFailed,
			Message: "Missing input: no 'bundle' or 'story' found in context",
			Error:   errors.New("missing story specification"),
		}, errors.New("missing story specification")
	}

	// 1. INVEST Quality Audit
	invest := spec.EvaluateINVEST(story)
	if invest.Score < 40 {
		return &BlockResult{
			BlockID: "review.socratic",
			Status:  StatusFailed,
			Message: fmt.Sprintf("INVEST testability score (%d/100) failed safety threshold: %v", invest.Score, invest.IdentifiedGaps),
			Error:   fmt.Errorf("story failed testability gate (%d/100)", invest.Score),
		}, fmt.Errorf("story failed testability gate (%d/100)", invest.Score)
	}

	// 2. Interrogate via Socratic Council Client
	router := model.NewRouter(model.DefaultRouterConfig())
	council := spec.NewCouncilClient(router, "http://localhost:8080")
	interrogation, err := council.InterrogateStory(ctx, story)
	if err == nil && interrogation != nil && len(interrogation.RefinedCriteria) > 0 {
		story.AcceptanceCriteria = interrogation.RefinedCriteria
	}

	// 3. Step binding validation against AXTree if available
	var axRoot *driver.AXNode
	var elements []driver.Element
	if axVal, ok := bCtx.Get("ax_tree"); ok && axVal != nil {
		if n, ok := axVal.(*driver.AXNode); ok {
			axRoot = n
		}
	}
	if elVal, ok := bCtx.Get("elements"); ok && elVal != nil {
		if els, ok := elVal.([]driver.Element); ok {
			elements = els
		}
	}

	if axRoot != nil || len(elements) > 0 {
		bindingResults := ValidateGherkinAgainstAXTree(story.AcceptanceCriteria, axRoot, elements)
		for _, br := range bindingResults {
			if br.Status == StatusNeedsClarification {
				bCtx.AddFailure(br.Clarification)
				return &BlockResult{
					BlockID: "review.socratic",
					Status:  StatusFailed,
					Message: fmt.Sprintf("Gherkin validation failed: %s", br.Clarification),
					Error:   errors.New("unbound Gherkin steps in active AXTree"),
				}, errors.New("unbound Gherkin steps in active AXTree")
			}
		}
	}

	gherkin := spec.GenerateGherkin(story)
	bCtx.Set("audited_scenarios", story.AcceptanceCriteria)
	bCtx.Set("gherkin_spec", gherkin)

	return &BlockResult{
		BlockID: "review.socratic",
		Status:  StatusPassed,
		Message: fmt.Sprintf("Socratic audit completed for %d scenario(s) (INVEST: %d/100)",
			len(story.AcceptanceCriteria), invest.Score),
		Data: map[string]interface{}{
			"invest_score":   invest.Score,
			"num_criteria":   len(story.AcceptanceCriteria),
			"gaps_count":     len(invest.IdentifiedGaps),
			"uncovered_gaps": invest.IdentifiedGaps,
		},
	}, nil
}
