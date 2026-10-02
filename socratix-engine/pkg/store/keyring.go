package store

import (
	"bytes"
	"encoding/hex"
	"errors"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

const keyringService = "dialex"

// OS keychain access through the platform CLI (macOS `security`, Linux libsecret
// `secret-tool`), so there is no cgo or new dependency. Anything else, or DIALEX_KEY_STORAGE=file,
// returns an error and the caller falls back to the key file.
//
// ponytail: on macOS the key is passed in argv to `security` for a few milliseconds (visible to
// same-user `ps`); the old key file was readable by that user all the time. Windows has no
// CLI that can read secrets back, so it stays on the file; use go-keyring/DPAPI if that matters.
var (
	keyringSet = osKeyringSet
	keyringGet = osKeyringGet
)

func keyringEnabled() bool {
	return os.Getenv("DIALEX_KEY_STORAGE") != "file"
}

func osKeyringSet(account string, key []byte) error {
	if !keyringEnabled() {
		return errors.New("keyring disabled")
	}
	val := hex.EncodeToString(key)
	switch runtime.GOOS {
	case "darwin":
		return run(nil, "security", "add-generic-password", "-U", "-s", keyringService, "-a", account, "-w", val)
	case "linux":
		return run(strings.NewReader(val), "secret-tool", "store", "--label=Dialex data key", "service", keyringService, "account", account)
	}
	return errors.New("no keyring on " + runtime.GOOS)
}

func osKeyringGet(account string) ([]byte, error) {
	if !keyringEnabled() {
		return nil, errors.New("keyring disabled")
	}
	var out bytes.Buffer
	var err error
	switch runtime.GOOS {
	case "darwin":
		err = runOut(&out, nil, "security", "find-generic-password", "-s", keyringService, "-a", account, "-w")
	case "linux":
		err = runOut(&out, nil, "secret-tool", "lookup", "service", keyringService, "account", account)
	default:
		err = errors.New("no keyring on " + runtime.GOOS)
	}
	if err != nil {
		return nil, err
	}
	return hex.DecodeString(strings.TrimSpace(out.String()))
}

func run(stdin *strings.Reader, name string, args ...string) error {
	return runOut(nil, stdin, name, args...)
}

func runOut(out *bytes.Buffer, stdin *strings.Reader, name string, args ...string) error {
	cmd := exec.Command(name, args...)
	if stdin != nil {
		cmd.Stdin = stdin
	}
	if out != nil {
		cmd.Stdout = out
	}
	done := make(chan error, 1)
	if err := cmd.Start(); err != nil {
		return err
	}
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		return err
	case <-time.After(5 * time.Second): // a locked keychain can block on a prompt; don't hang startup
		_ = cmd.Process.Kill()
		return errors.New("keyring timeout")
	}
}
