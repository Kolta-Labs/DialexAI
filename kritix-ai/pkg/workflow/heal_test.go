package workflow

import (
	"context"
	"testing"

	"kritix/pkg/driver"
	"kritix/pkg/sdet"
)

func healVars(extra map[string]interface{}) *Context {
	v := map[string]interface{}{
		"broken_selectors": []sdet.ElementFingerprint{{ID: "save-btn", Role: "button", Text: "Save", Tag: "button",
			BBox: driver.Rect{X: 100, Y: 200, Width: 120, Height: 40}}},
		// "Delete" now sits where "Save" used to be; "Save" is gone.
		"elements": []driver.Element{{ID: "delete-btn", Role: "button", Text: "Delete", Tag: "button",
			BoundingBox: driver.Rect{X: 100, Y: 200, Width: 120, Height: 40}}},
	}
	for k, x := range extra {
		v[k] = x
	}
	return NewContext(v)
}

func TestSelfHealNeverClicksWrongButton(t *testing.T) {
	for _, mode := range []string{"", "advisory"} { // default (strict) and even advisory must refuse a bbox-only match
		extra := map[string]interface{}{}
		if mode != "" {
			extra["heal_mode"] = mode
		}
		res, err := (&SDETSelfHealBlock{}).Execute(context.Background(), healVars(extra))
		if err == nil || res.Status != StatusFailed {
			t.Fatalf("mode %q: Delete in Save's slot must not heal, got %s", mode, res.Status)
		}
	}
}

func TestSelfHealDefaultsToStrictAndRejectsUnknownMode(t *testing.T) {
	// A renamed-but-equivalent button: advisory heals it, strict only proposes a patch.
	renamed := map[string]interface{}{"elements": []driver.Element{{ID: "save-btn-v2", Role: "button", Text: "Save", Tag: "button"}}}
	res, err := (&SDETSelfHealBlock{}).Execute(context.Background(), healVars(renamed))
	if err == nil || res.Status != StatusFailed {
		t.Fatalf("strict default must fail with a proposed patch, got %s", res.Status)
	}
	renamed["heal_mode"] = "advisory"
	res, err = (&SDETSelfHealBlock{}).Execute(context.Background(), healVars(renamed))
	if err != nil || res.Status != StatusPassedWithHealing {
		t.Fatalf("explicit advisory should heal with review flag, got %s / %v", res.Status, err)
	}
	if _, err := (&SDETSelfHealBlock{}).Execute(context.Background(), healVars(map[string]interface{}{"heal_mode": "yolo"})); err == nil {
		t.Fatal("unknown heal_mode must error")
	}
}
