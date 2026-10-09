package coder

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"artix/pkg/git"
	"artix/pkg/persona"
	"artix/pkg/repo"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
	"artix/pkg/spec"
)

func TestCoderLoop_OfflineCacheMiss_FailsWithDistinctError(t *testing.T) {
	tempDir := t.TempDir()

	// Initialize git repo
	d := git.NewDriver(tempDir)
	if err := d.Init(); err != nil {
		t.Fatalf("git init failed: %v", err)
	}
	_ = os.WriteFile(filepath.Join(tempDir, "build.gradle.kts"), []byte("plugins { java }\n"), 0644)
	if err := d.Add("."); err != nil {
		t.Fatalf("git add failed: %v", err)
	}
	if err := d.Commit("initial commit"); err != nil {
		t.Fatalf("git commit failed: %v", err)
	}

	reg := persona.NewRegistry(tempDir)
	dc, err := NewDomainCoder("backend_engineer", reg)
	if err != nil {
		t.Fatalf("NewDomainCoder failed: %v", err)
	}

	rev := reviewer.NewAdversarialReviewer(reg)
	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(dc, rev, d, box)

	story := &spec.StorySpec{
		ID:           "OFFLINE-1",
		Title:        "Cold cache gradle test",
		TestCommands: []string{"./gradlew test --offline"},
	}

	// Create a fake gradlew that simulates cold cache miss
	gradlewScript := "#!/bin/sh\n" +
		"echo \"> Could not resolve com.example:missing-lib:1.2.3.\" >&2\n" +
		"echo \"  Required by: project :app\" >&2\n" +
		"exit 1\n"
	_ = os.WriteFile(filepath.Join(tempDir, "gradlew"), []byte(gradlewScript), 0755)

	rc, _ := repo.DetectContext(tempDir)

	opts := &LoopOptions{
		MaxRounds: 1,
		Autonomy:  AutonomySupervised,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/build.gradle.kts b/build.gradle.kts\n--- a/build.gradle.kts\n+++ b/build.gradle.kts\n@@ -1,1 +1,2 @@\n plugins { java }\n+// comment\n"
		},
	}

	res := coord.Run(context.Background(), story, rc, nil, nil, opts)

	if res.Success {
		t.Fatalf("expected loop to fail due to cold cache miss, but succeeded")
	}

	expectedPrefix := "OFFLINE_CACHE_MISS: com.example:missing-lib:1.2.3"
	if !strings.Contains(res.Error, expectedPrefix) {
		t.Errorf("expected res.Error to contain %q, got: %q", expectedPrefix, res.Error)
	}
}

func TestCoderLoop_WarmThenSandboxedExecution_UnshareNetVerified(t *testing.T) {
	tempDir := t.TempDir()

	d := git.NewDriver(tempDir)
	if err := d.Init(); err != nil {
		t.Fatalf("git init failed: %v", err)
	}
	_ = os.WriteFile(filepath.Join(tempDir, "build.gradle.kts"), []byte("plugins { java }\n"), 0644)
	if err := d.Add("."); err != nil {
		t.Fatalf("git add failed: %v", err)
	}
	if err := d.Commit("initial commit"); err != nil {
		t.Fatalf("git commit failed: %v", err)
	}

	// Fake gradlew that checks outbound network in test phase
	// In sandboxed mode without network (--unshare-net / deny network*), connecting outbound fails.
	gradlewScript := "#!/bin/sh\n" +
		"if [ \"$1\" = \"--refresh-dependencies\" ] || [ \"$1\" = \"dependencies\" ]; then\n" +
		"  echo \"Pre-warmed dependencies successfully outside sandbox\"\n" +
		"  exit 0\n" +
		"fi\n" +
		"# In test phase, try outbound network. If network is blocked (offline sandbox), this test verifies unshare-net.\n" +
		"if nc -z -w 1 8.8.8.8 53 2>/dev/null || curl -s --max-time 1 https://example.com 2>/dev/null; then\n" +
		"  echo \"ERROR: outbound network succeeded inside sandbox!\" >&2\n" +
		"  exit 2\n" +
		"fi\n" +
		"echo \"Offline sandbox verified: outbound network is blocked as expected.\"\n" +
		"exit 0\n"
	_ = os.WriteFile(filepath.Join(tempDir, "gradlew"), []byte(gradlewScript), 0755)

	reg := persona.NewRegistry(tempDir)
	dc, err := NewDomainCoder("backend_engineer", reg)
	if err != nil {
		t.Fatalf("NewDomainCoder failed: %v", err)
	}

	rev := reviewer.NewAdversarialReviewer(reg)
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})

	box := sandbox.NewSandbox(tempDir)
	coord := NewCoordinator(dc, rev, d, box)

	story := &spec.StorySpec{
		ID:           "OFFLINE-WARM-1",
		Title:        "Warm then offline test",
		TestCommands: []string{"./gradlew test --offline"},
	}

	rc, _ := repo.DetectContext(tempDir)

	opts := &LoopOptions{
		MaxRounds: 1,
		Autonomy:  AutonomySupervised,
		MockPatchGen: func(round int, feedback string) string {
			return "diff --git a/build.gradle.kts b/build.gradle.kts\n--- a/build.gradle.kts\n+++ b/build.gradle.kts\n@@ -1,1 +1,2 @@\n plugins { java }\n+// comment\n"
		},
	}

	res := coord.Run(context.Background(), story, rc, nil, nil, opts)
	if !res.Success {
		t.Fatalf("expected loop to succeed after warm verification, got error: %s", res.Error)
	}
}
