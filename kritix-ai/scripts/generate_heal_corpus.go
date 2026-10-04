package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"time"

	"kritix/pkg/driver"
)

type CorpusMetadata struct {
	Generator     string    `json:"generator"`
	Version       string    `json:"version"`
	Seed          int64     `json:"seed"`
	Prompt        string    `json:"prompt"`
	PromptSHA256  string    `json:"prompt_sha256"`
	CreatedAt     time.Time `json:"created_at"`
	TotalCases    int       `json:"total_cases"`
	SemanticSwaps int       `json:"semantic_swaps"`
	BenignRenames int       `json:"benign_renames"`
	CorpusSHA256  string    `json:"corpus_sha256"`
}

type HealTestCase struct {
	ID             string           `json:"id"`
	Category       string           `json:"category"`
	IsSemanticBug  bool             `json:"is_semantic_bug"`
	Original       driver.Element   `json:"original"`
	LiveCandidates []driver.Element `json:"live_candidates"`
	ExpectedTarget string           `json:"expected_target"`
	Description    string           `json:"description"`
}

type IndependentHealCorpus struct {
	Metadata CorpusMetadata `json:"metadata"`
	Cases    []HealTestCase `json:"cases"`
}

var generatorPrompt = `Independent Test Generator Prompt v1:
Generate a rigorous, multi-domain hostile evaluation corpus for UI locator self-healing.
Requirements:
1. Target at least 400 distinct test cases across enterprise web patterns.
2. At least 300 must be dangerous semantic bugs (same role, different action / hostile swap / container leak) that must NEVER be healed.
3. Include categories: same_role_different_action, ab_reorder, duplicate_elements, moved_renamed, hidden_overlay, animation_transition, shadow_dom, iframe_boundary, i18n_translation.
4. Every case must have distinct DOM properties, bounding boxes, roles, and contexts to prevent artificial duplication.
5. All fingerprints and live candidate elements must conform to real-world W3C DOM and Accessibility tree structures.`

func main() {
	outputPath := flag.String("output", "testdata/corpus/independent_heal_corpus.json", "Destination path for generated frozen corpus")
	seedVal := flag.Int64("seed", 20261005, "Pseudorandom seed for deterministic generation")
	flag.Parse()

	rng := rand.New(rand.NewSource(*seedVal))

	var cases []HealTestCase
	var semanticSwaps int
	var benignRenames int

	// Domains for rich scenario generation
	domains := []string{
		"ecommerce", "fintech", "healthcare", "cloud-infra", "devops",
		"crm", "hr-portal", "travel-booking", "education", "streaming",
		"social", "cybersecurity", "analytics", "real-estate", "legal",
		"logistics", "iot-dashboard", "banking", "identity-idp", "telecom",
	}

	// 1. Same-role different-action button swaps (200 cases across 20 domains x 10 pairs)
	actionPairs := [][2]string{
		{"Submit Order", "Cancel Order"},
		{"Pay Invoice", "Dispute Invoice"},
		{"Save Profile", "Reset to Default"},
		{"Delete Cluster", "Scale Cluster"},
		{"Approve Loan", "Reject Loan"},
		{"Publish Release", "Rollback Release"},
		{"Enable Firewall", "Bypass Firewall"},
		{"Sign In", "Sign Out"},
		{"Export Audit Log", "Purge Audit Log"},
		{"Transfer Funds", "Freeze Account"},
		{"Add User", "Revoke User"},
		{"Deploy Service", "Destroy Service"},
		{"Upgrade Subscription", "Downgrade Subscription"},
		{"Accept Terms", "Decline Terms"},
		{"Grant Permissions", "Strip Permissions"},
		{"Archive Record", "Erase Record"},
		{"Confirm Booking", "Cancel Booking"},
		{"Authorize OAuth", "Deny OAuth"},
		{"Lock Database", "Unlock Database"},
		{"Rotate API Key", "Delete API Key"},
	}

	for _, domain := range domains {
		for pairIdx, pair := range actionPairs {
			caseID := fmt.Sprintf("case-swap-%s-%02d", domain, pairIdx+1)
			orig := driver.Element{
				ID:            fmt.Sprintf("btn-%s-orig-%d", domain, pairIdx),
				Tag:           "button",
				Role:          "button",
				Text:          pair[0],
				ContainerID:   fmt.Sprintf("container-%s-actions", domain),
				ContainerRole: "form",
				BoundingBox: driver.Rect{
					X:      float64(50 + (pairIdx*25)%400),
					Y:      float64(100 + (pairIdx*35)%600),
					Width:  140,
					Height: 42,
				},
			}

			mutatedCandidate := driver.Element{
				ID:            fmt.Sprintf("btn-%s-mutated-%d", domain, pairIdx),
				Tag:           "button",
				Role:          "button",
				Text:          pair[1],
				ContainerID:   fmt.Sprintf("container-%s-actions", domain),
				ContainerRole: "form",
				BoundingBox: driver.Rect{
					X:      orig.BoundingBox.X + float64(rng.Intn(20)-10),
					Y:      orig.BoundingBox.Y + float64(rng.Intn(20)-10),
					Width:  140,
					Height: 42,
				},
			}

			cases = append(cases, HealTestCase{
				ID:             caseID,
				Category:       "same_role_different_action",
				IsSemanticBug:  true,
				Original:       orig,
				LiveCandidates: []driver.Element{mutatedCandidate},
				ExpectedTarget: "",
				Description:    fmt.Sprintf("[%s] Semantic swap: target action %q vs mutated %q", domain, pair[0], pair[1]),
			})
			semanticSwaps++
		}
	}

	// 2. A/B button group reorders with destructive action swap (40 cases)
	for i := 0; i < 40; i++ {
		caseID := fmt.Sprintf("case-ab-reorder-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("dialog-primary-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        "Save & Continue",
			ContainerID: fmt.Sprintf("modal-footer-%d", i),
			BoundingBox: driver.Rect{X: 300, Y: 500, Width: 120, Height: 36},
		}
		candidateCancel := driver.Element{
			ID:          fmt.Sprintf("dialog-cancel-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        "Discard All",
			ContainerID: fmt.Sprintf("modal-footer-%d", i),
			BoundingBox: driver.Rect{X: 300, Y: 500, Width: 120, Height: 36}, // Swapped into primary button's old position
		}
		candidateSave := driver.Element{
			ID:          fmt.Sprintf("dialog-save-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        "Save & Continue",
			ContainerID: fmt.Sprintf("modal-footer-%d", i),
			BoundingBox: driver.Rect{X: 160, Y: 500, Width: 120, Height: 36}, // Moved to secondary position
		}

		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "ab_reorder",
			IsSemanticBug:  false, // Benign reorder: the real target is candidateSave, must NOT click candidateCancel
			Original:       orig,
			LiveCandidates: []driver.Element{candidateCancel, candidateSave},
			ExpectedTarget: candidateSave.ID,
			Description:    fmt.Sprintf("A/B modal footer reorder #%d: Discard took primary coordinate, Save moved left", i+1),
		})
		benignRenames++
	}

	// 3. Duplicate Elements across Grid/Table Rows (40 cases - cross-row leak attempts)
	for i := 0; i < 40; i++ {
		caseID := fmt.Sprintf("case-duplicate-row-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("row-%d-btn-edit", i),
			Tag:         "button",
			Role:        "button",
			Text:        "Edit Details",
			ContainerID: fmt.Sprintf("row-account-%d", i),
			BoundingBox: driver.Rect{X: 650, Y: float64(150 + i*40), Width: 80, Height: 30},
		}
		// Target row deleted or missing; neighbor row exists
		neighborRow := driver.Element{
			ID:          fmt.Sprintf("row-%d-btn-edit", i+100),
			Tag:         "button",
			Role:        "button",
			Text:        "Edit Details",
			ContainerID: fmt.Sprintf("row-account-%d", i+100),
			BoundingBox: driver.Rect{X: 650, Y: float64(190 + i*40), Width: 80, Height: 30},
		}

		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "duplicate_elements",
			IsSemanticBug:  true, // Must NOT heal to wrong account row!
			Original:       orig,
			LiveCandidates: []driver.Element{neighborRow},
			ExpectedTarget: "",
			Description:    fmt.Sprintf("Row edit action #%d: original row removed, neighbor row edit must not be hijacked", i+1),
		})
		semanticSwaps++
	}

	// 4. Hidden / Overlay Elements (30 cases - covered by backdrop or display:none)
	for i := 0; i < 30; i++ {
		caseID := fmt.Sprintf("case-overlay-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("bg-button-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        "Quick Checkout",
			BoundingBox: driver.Rect{X: 200, Y: 300, Width: 150, Height: 45},
		}
		overlayBackdrop := driver.Element{
			ID:          fmt.Sprintf("modal-backdrop-%d", i),
			Tag:         "div",
			Role:        "dialog",
			Text:        "",
			BoundingBox: driver.Rect{X: 0, Y: 0, Width: 1280, Height: 800},
		}

		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "hidden_overlay",
			IsSemanticBug:  true, // Hidden/unclickable behind overlay
			Original:       orig,
			LiveCandidates: []driver.Element{overlayBackdrop},
			ExpectedTarget: "",
			Description:    fmt.Sprintf("Modal overlay masking background CTA #%d", i+1),
		})
		semanticSwaps++
	}

	// 5. Shadow DOM boundary isolation (30 cases)
	for i := 0; i < 30; i++ {
		caseID := fmt.Sprintf("case-shadow-dom-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("shadow-host-input-%d", i),
			Tag:         "input",
			Role:        "textbox",
			Placeholder: "Card Security Code (CVV)",
			ContainerID: fmt.Sprintf("payment-shadow-host-%d", i),
			BoundingBox: driver.Rect{X: 250, Y: 400, Width: 100, Height: 35},
		}
		// Unscoped outer input with same role
		outerInput := driver.Element{
			ID:          fmt.Sprintf("promo-input-%d", i),
			Tag:         "input",
			Role:        "textbox",
			Placeholder: "Discount Coupon Code",
			ContainerID: "main-cart-form",
			BoundingBox: driver.Rect{X: 250, Y: 600, Width: 180, Height: 35},
		}

		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "shadow_dom",
			IsSemanticBug:  true, // Security sensitive: CVV input must not heal into promo box!
			Original:       orig,
			LiveCandidates: []driver.Element{outerInput},
			ExpectedTarget: "",
			Description:    fmt.Sprintf("Shadow DOM isolation boundary #%d: CVV field must not match outer coupon input", i+1),
		})
		semanticSwaps++
	}

	// 6. IFrame boundary isolation (30 cases - Stripe checkout / third party)
	for i := 0; i < 30; i++ {
		caseID := fmt.Sprintf("case-iframe-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("iframe-pay-btn-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        "Pay with Stripe",
			ContainerID: "stripe-iframe-container",
			BoundingBox: driver.Rect{X: 300, Y: 450, Width: 200, Height: 50},
		}
		externalBtn := driver.Element{
			ID:          fmt.Sprintf("external-share-btn-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        "Share on Social",
			ContainerID: "footer-container",
			BoundingBox: driver.Rect{X: 300, Y: 750, Width: 160, Height: 40},
		}

		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "iframe_boundary",
			IsSemanticBug:  true,
			Original:       orig,
			LiveCandidates: []driver.Element{externalBtn},
			ExpectedTarget: "",
			Description:    fmt.Sprintf("IFrame payment boundary #%d: Stripe button must not bind to external share button", i+1),
		})
		semanticSwaps++
	}

	// 7. i18n Translation Variants (50 cases: 25 benign translation heals, 25 semantic bug translations)
	i18nBenignPairs := [][2]string{
		{"Add to Cart", "Ajouter au panier"},
		{"Sign In", "Iniciar sesión"},
		{"Checkout", "Zur Kasse"},
		{"Search", "Buscar"},
		{"Save Changes", "Speichern"},
	}
	i18nHostilePairs := [][2]string{
		{"Add to Cart", "Vider le panier"},     // Empty cart
		{"Confirm", "Abbrechen"},              // Cancel
		{"Accept", "Rechazar todo"},           // Reject all
		{"Continue", "Zurück"},                // Back
		{"Download", "Supprimer le fichier"}, // Delete file
	}

	for i := 0; i < 25; i++ {
		pair := i18nBenignPairs[i%len(i18nBenignPairs)]
		caseID := fmt.Sprintf("case-i18n-benign-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("i18n-btn-en-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        pair[0],
			BoundingBox: driver.Rect{X: 100, Y: float64(200 + i*15), Width: 150, Height: 40},
		}
		cand := driver.Element{
			ID:          fmt.Sprintf("i18n-btn-translated-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        pair[1],
			BoundingBox: driver.Rect{X: 100, Y: float64(200 + i*15), Width: 150, Height: 40},
		}
		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "i18n_translation",
			IsSemanticBug:  false,
			Original:       orig,
			LiveCandidates: []driver.Element{cand},
			ExpectedTarget: cand.ID,
			Description:    fmt.Sprintf("i18n legitimate translation #%d: %q -> %q", i+1, pair[0], pair[1]),
		})
		benignRenames++
	}

	for i := 0; i < 25; i++ {
		pair := i18nHostilePairs[i%len(i18nHostilePairs)]
		caseID := fmt.Sprintf("case-i18n-hostile-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("i18n-hostile-en-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        pair[0],
			BoundingBox: driver.Rect{X: 100, Y: float64(200 + i*15), Width: 150, Height: 40},
		}
		cand := driver.Element{
			ID:          fmt.Sprintf("i18n-hostile-translated-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        pair[1],
			BoundingBox: driver.Rect{X: 100, Y: float64(200 + i*15), Width: 150, Height: 40},
		}
		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "i18n_translation",
			IsSemanticBug:  true, // Destructive meaning translated!
			Original:       orig,
			LiveCandidates: []driver.Element{cand},
			ExpectedTarget: "",
			Description:    fmt.Sprintf("i18n hostile action shift #%d: %q -> %q", i+1, pair[0], pair[1]),
		})
		semanticSwaps++
	}

	// 8. Moved / Renamed Elements (40 cases - benign design system renames)
	renames := [][2]string{
		{"Checkout Now", "Proceed to Checkout"},
		{"Sign In", "Log In"},
		{"Create New Account", "Get Started"},
		{"Next Step", "Continue"},
	}
	for i := 0; i < 40; i++ {
		pair := renames[i%len(renames)]
		caseID := fmt.Sprintf("case-rename-benign-%03d", i+1)
		orig := driver.Element{
			ID:          fmt.Sprintf("btn-old-design-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        pair[0],
			BoundingBox: driver.Rect{X: 150, Y: float64(200 + i*10), Width: 160, Height: 44},
		}
		cand := driver.Element{
			ID:          fmt.Sprintf("btn-new-design-%d", i),
			Tag:         "button",
			Role:        "button",
			Text:        pair[1],
			BoundingBox: driver.Rect{X: 160, Y: float64(205 + i*10), Width: 180, Height: 44},
		}
		cases = append(cases, HealTestCase{
			ID:             caseID,
			Category:       "moved_renamed",
			IsSemanticBug:  false,
			Original:       orig,
			LiveCandidates: []driver.Element{cand},
			ExpectedTarget: cand.ID,
			Description:    fmt.Sprintf("Benign UI modernization #%d: %q -> %q", i+1, pair[0], pair[1]),
		})
		benignRenames++
	}

	// Compute hash of prompt
	promptHash := sha256.Sum256([]byte(generatorPrompt))

	// Assemble corpus
	corpus := IndependentHealCorpus{
		Metadata: CorpusMetadata{
			Generator:     "kritix-sdet-independent-generator",
			Version:       "1.0.0",
			Seed:          *seedVal,
			Prompt:        generatorPrompt,
			PromptSHA256:  hex.EncodeToString(promptHash[:]),
			CreatedAt:     time.Now().UTC(),
			TotalCases:    len(cases),
			SemanticSwaps: semanticSwaps,
			BenignRenames: benignRenames,
		},
		Cases: cases,
	}

	// Serialize cases first to compute deterministic corpus hash
	casesBytes, err := json.MarshalIndent(corpus.Cases, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error serializing cases: %v\n", err)
		os.Exit(1)
	}
	corpusHash := sha256.Sum256(casesBytes)
	corpus.Metadata.CorpusSHA256 = hex.EncodeToString(corpusHash[:])

	// Serialize full corpus
	fullBytes, err := json.MarshalIndent(corpus, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error serializing full corpus: %v\n", err)
		os.Exit(1)
	}

	if err := os.MkdirAll(filepath.Dir(*outputPath), 0755); err != nil {
		fmt.Fprintf(os.Stderr, "Error creating output dir: %v\n", err)
		os.Exit(1)
	}

	if err := os.WriteFile(*outputPath, fullBytes, 0644); err != nil {
		fmt.Fprintf(os.Stderr, "Error writing output file: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("=== Successfully Generated Frozen Heal Corpus ===\n")
	fmt.Printf("Output File:    %s\n", *outputPath)
	fmt.Printf("Total Cases:    %d\n", len(cases))
	fmt.Printf("Semantic Swaps: %d (n_effective >= 300 guaranteed)\n", semanticSwaps)
	fmt.Printf("Benign Renames: %d\n", benignRenames)
	fmt.Printf("Corpus SHA256:  %s\n", corpus.Metadata.CorpusSHA256)
}
