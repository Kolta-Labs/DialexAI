package sandbox

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// Isolation names how a command was confined. Reported in ExecResult so the UI and docs never
// claim more than actually happened.
const (
	IsolationOSSandbox    = "os-sandbox"    // macOS sandbox-exec, or Linux bubblewrap
	IsolationProcessGroup = "process-group" // no filesystem/network restriction
)

// Confinement: writes are limited to the workspace, temp dirs and common tool caches; network
// is denied unless ARTIX_SANDBOX_NETWORK=1. Reads are unrestricted. ARTIX_SANDBOX=off disables
// it. If no confinement tool exists the command still runs, flagged IsolationProcessGroup.
//
// ponytail: macOS sandbox-exec is deprecated by Apple but still ships; the Linux bwrap path is
// untested in CI. Not a defence against a determined attacker (reads are open, same user).
// Upgrade path: container/VM per task.
func confine(cwd, cmdStr string) (*exec.Cmd, string) {
	plain := func() (*exec.Cmd, string) { return exec.Command("sh", "-c", cmdStr), IsolationProcessGroup }
	if os.Getenv("ARTIX_SANDBOX") == "off" || os.Getenv("KRITIX_SANDBOX") == "off" {
		return plain()
	}
	net := os.Getenv("ARTIX_SANDBOX_NETWORK") == "1" || os.Getenv("KRITIX_SANDBOX_NETWORK") == "1"
	home, _ := os.UserHomeDir()
	real := func(p string) string {
		if r, err := filepath.EvalSymlinks(p); err == nil {
			return r
		}
		return p
	}
	writable := []string{real(cwd), real(os.TempDir()), "/tmp", "/private/tmp", "/private/var/folders"}
	for _, c := range []string{"Library/Caches", ".cache", "go/pkg", ".gradle", ".m2", ".npm", ".cargo", ".artix", ".kritix"} {
		writable = append(writable, filepath.Join(home, c))
	}

	switch runtime.GOOS {
	case "darwin":
		if _, err := exec.LookPath("sandbox-exec"); err != nil {
			return plain()
		}
		var p strings.Builder
		p.WriteString("(version 1)(allow default)")
		if !net {
			p.WriteString("(deny network*)")
		}
		p.WriteString("(deny file-write*)(allow file-write* (literal \"/dev/null\") (literal \"/dev/tty\") (regex #\"^/dev/fd/\")")
		for _, w := range writable {
			fmt.Fprintf(&p, " (subpath %q)", w)
		}
		p.WriteString(")")
		return exec.Command("sandbox-exec", "-p", p.String(), "sh", "-c", cmdStr), IsolationOSSandbox
	case "linux":
		bwrap, err := exec.LookPath("bwrap")
		if err != nil {
			return plain()
		}
		args := []string{"--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--die-with-parent"}
		for _, w := range writable {
			if _, err := os.Stat(w); err == nil {
				args = append(args, "--bind", w, w)
			}
		}
		if !net {
			args = append(args, "--unshare-net")
		}
		return exec.Command(bwrap, append(args, "sh", "-c", cmdStr)...), IsolationOSSandbox
	}
	return plain()
}
