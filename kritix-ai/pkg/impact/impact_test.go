package impact

import (
	"context"
	"errors"
	"testing"
)

func TestChangedFilesRejectsBadRef(t *testing.T) {
	for _, ref := range []string{"main; rm -rf /", "$(id)", "--stat", "-x", ""} {
		if _, err := ChangedFiles(context.Background(), ".", ref); !errors.Is(err, ErrInvalidBaseRef) {
			t.Errorf("ref %q: want ErrInvalidBaseRef, got %v", ref, err)
		}
	}
}

func TestMatchGlobPath(t *testing.T) {
	cases := []struct {
		path, pat string
		want      bool
	}{
		{"src/checkout/a/b.ts", "src/checkout/**", true},
		{"src/checkout", "src/checkout/**", true},
		{"src/cart/a.ts", "src/checkout/**", false},
		{"a/b/c.go", "**/c.go", true},
		{"a.md", "*.md", true},
		{"d/a.md", "*.md", false},
	}
	for _, c := range cases {
		if got := matchGlobPath(c.path, c.pat); got != c.want {
			t.Errorf("%q vs %q = %v", c.path, c.pat, got)
		}
	}
}
