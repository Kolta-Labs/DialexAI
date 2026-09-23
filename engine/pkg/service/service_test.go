package service

import (
	"strings"
	"testing"
)

// Install/Uninstall aren't exercised here: they shell out to the real launchctl/systemctl and
// would register an actual login-time service on whatever machine runs `go test` — a real
// system-settings change, not something a test suite should do as a side effect. The pure
// logic (plist/unit generation, path selection) is what's under test; Install/Uninstall are
// thin, reviewable wrappers around it plus the exec.Command calls documented in service.go.

func TestBuildLaunchdPlistIncludesTheBinaryAndEveryArg(t *testing.T) {
	plist := buildLaunchdPlist("/usr/local/bin/roundtable", []string{"serve", "--host", "127.0.0.1:7890"})
	for _, want := range []string{"/usr/local/bin/roundtable", "serve", "--host", "127.0.0.1:7890", label} {
		if !strings.Contains(plist, want) {
			t.Errorf("plist missing %q:\n%s", want, plist)
		}
	}
	if !strings.Contains(plist, "<key>RunAtLoad</key>") {
		t.Error("plist should run at login")
	}
}

func TestLaunchdPlistPathIsUnderLaunchAgents(t *testing.T) {
	path, err := launchdPlistPath()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(path, "Library/LaunchAgents") || !strings.HasSuffix(path, ".plist") {
		t.Errorf("unexpected path: %s", path)
	}
}

func TestSystemdUnitPathIsUnderUserConfig(t *testing.T) {
	path, err := systemdUnitPath()
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(path, ".config/systemd/user") || !strings.HasSuffix(path, ".service") {
		t.Errorf("unexpected path: %s", path)
	}
}

func TestStatusIsFalseWhenNothingIsInstalled(t *testing.T) {
	// A freshly-created $HOME (via t.Setenv) has no LaunchAgents/systemd unit, so this is
	// true regardless of what's actually installed on the machine running the test.
	t.Setenv("HOME", t.TempDir())
	installed, err := Status()
	if err != nil {
		t.Fatal(err)
	}
	if installed {
		t.Error("expected not installed in a fresh HOME")
	}
}
