package triage

import (
	"strings"
	"testing"
)

func TestLocateFailureFrame(t *testing.T) {
	// JVM stack trace test
	jvmTrace := "java.lang.NullPointerException at com.example.service.CheckoutService.applyDiscount(CheckoutService.kt:142)"
	file, line, found := LocateFailureFrame(jvmTrace)
	if !found || file != "CheckoutService.kt" || line != 142 {
		t.Errorf("failed to parse JVM stack frame: found=%v, file=%s, line=%d", found, file, line)
	}

	// TypeScript / Node stack trace test
	tsTrace := "TypeError: Cannot read properties of undefined at validateCoupon (/app/src/coupon.ts:88:12)"
	tsFile, tsLine, tsFound := LocateFailureFrame(tsTrace)
	if !tsFound || !strings.HasSuffix(tsFile, "coupon.ts") || tsLine != 88 {
		t.Errorf("failed to parse TS stack frame: found=%v, file=%s, line=%d", tsFound, tsFile, tsLine)
	}
}

func TestFormatRCAHint(t *testing.T) {
	info := BlameInfo{
		CommitHash:    "a1b2c3d4",
		AuthorName:    "Alice Engineer",
		AuthorEmail:   "alice@example.com",
		CommitDate:    "2026-09-28",
		CommitMessage: "Refactor coupon validation logic",
		FilePath:      "src/coupon.ts",
		LineNumber:    88,
	}

	hint := FormatRCAHint(info)
	if !strings.Contains(hint, "src/coupon.ts:88") {
		t.Errorf("missing file and line in hint: %s", hint)
	}
	if !strings.Contains(hint, "Alice Engineer") {
		t.Errorf("missing author in hint: %s", hint)
	}
	if !strings.Contains(hint, "Refactor coupon validation logic") {
		t.Errorf("missing commit message in hint: %s", hint)
	}
}
