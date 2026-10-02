// Package service registers/unregisters the engine as a background service that starts on
// login, so a desktop or CLI-only install can rely on `roundtable serve` always being up
// without the user babysitting a terminal window. macOS (launchd) and Linux (systemd --user)
// are supported; Windows Service registration needs the SCM API (not stdlib-reachable) and
// is deliberately not built here yet — Status/Install/Uninstall return a clear
// ErrUnsupportedPlatform there instead of pretending to work.
package service

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

// ErrUnsupportedPlatform is returned by Install/Uninstall/Status on a platform with no
// implementation yet (currently: everything but darwin/linux).
var ErrUnsupportedPlatform = errors.New("service management isn't supported on this platform yet")

const label = "io.appspiriment.dialex.engine"

// Options controls how the installed service invokes `roundtable serve`.
type Options struct {
	// Host defaults to 127.0.0.1:7890 (serve's own default) when empty.
	Host string
	// ConfigDir defaults to the platform-standard directory (serve's own default) when empty.
	ConfigDir string
}

func launchdPlistPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", label+".plist"), nil
}

func systemdUnitPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".config", "systemd", "user", label+".service"), nil
}

// Install writes and enables a login-time service that runs `roundtable serve` using the
// currently running executable (so it keeps working after the binary is upgraded in place,
// so long as service files are regenerated on upgrade too — callers that ship updates should
// re-run Install after replacing the binary).
func Install(opts Options) error {
	exePath, err := os.Executable()
	if err != nil {
		return fmt.Errorf("could not determine this binary's path: %w", err)
	}
	args := []string{"serve"}
	if opts.Host != "" {
		args = append(args, "--host", opts.Host)
	}
	if opts.ConfigDir != "" {
		args = append(args, "--dir", opts.ConfigDir)
	}

	switch runtime.GOOS {
	case "darwin":
		return installLaunchd(exePath, args)
	case "linux":
		return installSystemd(exePath, args)
	default:
		return ErrUnsupportedPlatform
	}
}

// Uninstall stops and removes whatever Install put in place. Safe to call even if nothing
// was ever installed (it just no-ops on a missing file).
func Uninstall() error {
	switch runtime.GOOS {
	case "darwin":
		return uninstallLaunchd()
	case "linux":
		return uninstallSystemd()
	default:
		return ErrUnsupportedPlatform
	}
}

// Status reports whether the service is currently registered (installed), not whether the
// engine is actually healthy right now — callers wanting "is it actually up" should hit
// /health instead, same as everything else in this codebase does.
func Status() (installed bool, err error) {
	switch runtime.GOOS {
	case "darwin":
		path, err := launchdPlistPath()
		if err != nil {
			return false, err
		}
		return fileExists(path), nil
	case "linux":
		path, err := systemdUnitPath()
		if err != nil {
			return false, err
		}
		return fileExists(path), nil
	default:
		return false, ErrUnsupportedPlatform
	}
}

func fileExists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func installLaunchd(exePath string, args []string) error {
	path, err := launchdPlistPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	plist := buildLaunchdPlist(exePath, args)
	if err := os.WriteFile(path, []byte(plist), 0o644); err != nil {
		return err
	}
	// `bootstrap` unloads-then-loads cleanly on a re-install; ignore a "not found" failure
	// from an unload of something that was never loaded.
	_ = exec.Command("launchctl", "unload", path).Run()
	if out, err := exec.Command("launchctl", "load", path).CombinedOutput(); err != nil {
		return fmt.Errorf("launchctl load failed: %w: %s", err, out)
	}
	return nil
}

func uninstallLaunchd() error {
	path, err := launchdPlistPath()
	if err != nil {
		return err
	}
	if !fileExists(path) {
		return nil
	}
	_ = exec.Command("launchctl", "unload", path).Run()
	return os.Remove(path)
}

func buildLaunchdPlist(exePath string, args []string) string {
	argXML := "<string>" + exePath + "</string>\n"
	for _, a := range args {
		argXML += "        <string>" + a + "</string>\n"
	}
	return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>` + label + `</string>
    <key>ProgramArguments</key>
    <array>
        ` + argXML + `    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
</dict>
</plist>
`
}

func installSystemd(exePath string, args []string) error {
	path, err := systemdUnitPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	execStart := exePath
	for _, a := range args {
		execStart += " " + a
	}
	unit := `[Unit]
Description=Roundtable engine

[Service]
ExecStart=` + execStart + `
Restart=on-failure

[Install]
WantedBy=default.target
`
	if err := os.WriteFile(path, []byte(unit), 0o644); err != nil {
		return err
	}
	if out, err := exec.Command("systemctl", "--user", "daemon-reload").CombinedOutput(); err != nil {
		return fmt.Errorf("systemctl --user daemon-reload failed: %w: %s", err, out)
	}
	if out, err := exec.Command("systemctl", "--user", "enable", "--now", label+".service").CombinedOutput(); err != nil {
		return fmt.Errorf("systemctl --user enable --now failed: %w: %s", err, out)
	}
	return nil
}

func uninstallSystemd() error {
	path, err := systemdUnitPath()
	if err != nil {
		return err
	}
	if !fileExists(path) {
		return nil
	}
	_ = exec.Command("systemctl", "--user", "disable", "--now", label+".service").Run()
	return os.Remove(path)
}
