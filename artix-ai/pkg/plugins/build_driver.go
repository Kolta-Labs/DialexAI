package plugins

import (
	"context"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"artix/pkg/sandbox"
)

// DiagnosticSeverity classifies error levels in compiler or linter output.
type DiagnosticSeverity string

const (
	SeverityError   DiagnosticSeverity = "error"
	SeverityWarning DiagnosticSeverity = "warning"
	SeverityInfo    DiagnosticSeverity = "info"
)

// BuildDiagnostic represents a structured compiler or linter message.
type BuildDiagnostic struct {
	File     string             `json:"file"`
	Line     int                `json:"line"`
	Column   int                `json:"column"`
	Severity DiagnosticSeverity `json:"severity"`
	Message  string             `json:"message"`
	Rule     string             `json:"rule,omitempty"`
}

// BuildDriver abstracts language- and build-tool-specific compilation, testing, and linting.
type BuildDriver interface {
	Name() string
	Detect(rootDir string) bool
	Compile(ctx context.Context, rootDir string) *sandbox.ExecResult
	Test(ctx context.Context, rootDir string) *sandbox.ExecResult
	Lint(ctx context.Context, rootDir string) *sandbox.ExecResult
	ParseErrorTrace(output string) []BuildDiagnostic
}

// Registry manages available BuildDriver plugins.
type Registry struct {
	mu      sync.RWMutex
	drivers []BuildDriver
}

// NewRegistry creates a registry populated with default built-in drivers.
func NewRegistry() *Registry {
	r := &Registry{
		drivers: make([]BuildDriver, 0),
	}
	r.Register(&GoDriver{})
	r.Register(&GradleDriver{})
	r.Register(&CargoDriver{})
	r.Register(&NpmDriver{})
	return r
}

// Register adds a build driver to the registry.
func (r *Registry) Register(d BuildDriver) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.drivers = append(r.drivers, d)
}

// DetectDriver finds the first registered driver that matches the given repository root.
func (r *Registry) DetectDriver(rootDir string) (BuildDriver, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	for _, d := range r.drivers {
		if d.Detect(rootDir) {
			return d, true
		}
	}
	return nil, false
}

// Go Driver
// GoDriver supports standard Go projects.
type GoDriver struct{}

func (g *GoDriver) Name() string { return "go" }

func (g *GoDriver) Detect(rootDir string) bool {
	_, err := os.Stat(filepath.Join(rootDir, "go.mod"))
	return err == nil
}

func (g *GoDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "go build ./...", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 3 * time.Minute,
	})
}

func (g *GoDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "go test -v ./...", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 5 * time.Minute,
	})
}

func (g *GoDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "go vet ./...", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 2 * time.Minute,
	})
}

var goErrRegex = regexp.MustCompile(`(?m)^([^\s:]+\.go):(\d+):(?:(\d+):)?\s*(.*)$`)

func (g *GoDriver) ParseErrorTrace(output string) []BuildDiagnostic {
	var diags []BuildDiagnostic
	matches := goErrRegex.FindAllStringSubmatch(output, -1)
	for _, m := range matches {
		line, _ := strconv.Atoi(m[2])
		col := 0
		if m[3] != "" {
			col, _ = strconv.Atoi(m[3])
		}
		diags = append(diags, BuildDiagnostic{
			File:     m[1],
			Line:     line,
			Column:   col,
			Severity: SeverityError,
			Message:  strings.TrimSpace(m[4]),
		})
	}
	return diags
}

// Gradle / KMP Driver
// GradleDriver supports Android and Kotlin Multiplatform Gradle projects.
type GradleDriver struct{}

func (gr *GradleDriver) Name() string { return "gradle" }

func (gr *GradleDriver) Detect(rootDir string) bool {
	_, errKts := os.Stat(filepath.Join(rootDir, "build.gradle.kts"))
	_, errGroovy := os.Stat(filepath.Join(rootDir, "build.gradle"))
	return errKts == nil || errGroovy == nil
}

func (gr *GradleDriver) gradlewCmd(rootDir string) string {
	gradlew := "./gradlew"
	if _, err := os.Stat(filepath.Join(rootDir, "gradlew")); err != nil {
		gradlew = "gradle"
	}
	return gradlew
}

func (gr *GradleDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	cmd := gr.gradlewCmd(rootDir) + " assembleDebug --no-daemon"
	return box.Run(ctx, cmd, &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 5 * time.Minute,
	})
}

func (gr *GradleDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	cmd := gr.gradlewCmd(rootDir) + " testDebugUnitTest --no-daemon"
	return box.Run(ctx, cmd, &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 6 * time.Minute,
	})
}

func (gr *GradleDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	cmd := gr.gradlewCmd(rootDir) + " ktlintCheck --no-daemon"
	return box.Run(ctx, cmd, &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 3 * time.Minute,
	})
}

var kotlinErrRegex = regexp.MustCompile(`(?m)^([^\s:]+\.(?:kt|java)):(\d+):(\d+):\s*(?:([we]):\s*)?(.*)$`)

func (gr *GradleDriver) ParseErrorTrace(output string) []BuildDiagnostic {
	var diags []BuildDiagnostic
	matches := kotlinErrRegex.FindAllStringSubmatch(output, -1)
	for _, m := range matches {
		line, _ := strconv.Atoi(m[2])
		col, _ := strconv.Atoi(m[3])
		sev := SeverityError
		if m[4] == "w" {
			sev = SeverityWarning
		}
		diags = append(diags, BuildDiagnostic{
			File:     m[1],
			Line:     line,
			Column:   col,
			Severity: sev,
			Message:  strings.TrimSpace(m[5]),
		})
	}
	return diags
}

// Cargo / Rust Driver
// CargoDriver supports Rust Cargo projects.
type CargoDriver struct{}

func (c *CargoDriver) Name() string { return "cargo" }

func (c *CargoDriver) Detect(rootDir string) bool {
	_, err := os.Stat(filepath.Join(rootDir, "Cargo.toml"))
	return err == nil
}

func (c *CargoDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "cargo check", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 3 * time.Minute,
	})
}

func (c *CargoDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "cargo test", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 5 * time.Minute,
	})
}

func (c *CargoDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "cargo clippy", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 3 * time.Minute,
	})
}

var cargoErrRegex = regexp.MustCompile(`(?m)^(error|warning)(?:\[([A-Za-z0-9_]+)\])?:\s+(.*)\n\s+-->\s+([^:]+):(\d+):(\d+)`)

func (c *CargoDriver) ParseErrorTrace(output string) []BuildDiagnostic {
	var diags []BuildDiagnostic
	matches := cargoErrRegex.FindAllStringSubmatch(output, -1)
	for _, m := range matches {
		line, _ := strconv.Atoi(m[5])
		col, _ := strconv.Atoi(m[6])
		sev := SeverityError
		if m[1] == "warning" {
			sev = SeverityWarning
		}
		diags = append(diags, BuildDiagnostic{
			File:     m[4],
			Line:     line,
			Column:   col,
			Severity: sev,
			Message:  strings.TrimSpace(m[3]),
			Rule:     m[2],
		})
	}
	return diags
}

// npm / Node Driver
// NpmDriver supports Node.js / TypeScript projects.
type NpmDriver struct{}

func (n *NpmDriver) Name() string { return "npm" }

func (n *NpmDriver) Detect(rootDir string) bool {
	_, err := os.Stat(filepath.Join(rootDir, "package.json"))
	return err == nil
}

func (n *NpmDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "npm run build --if-present", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 3 * time.Minute,
	})
}

func (n *NpmDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "npm test", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 5 * time.Minute,
	})
}

func (n *NpmDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, "npm run lint --if-present", &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: 2 * time.Minute,
	})
}

var tsErrRegex = regexp.MustCompile(`(?m)^([^\s:(]+)(?:\((\d+),(\d+)\)|:(\d+):(\d+)):\s*(error|warning)\s*(?:TS\d+:)?\s*(.*)$`)

func (n *NpmDriver) ParseErrorTrace(output string) []BuildDiagnostic {
	var diags []BuildDiagnostic
	matches := tsErrRegex.FindAllStringSubmatch(output, -1)
	for _, m := range matches {
		line := 0
		col := 0
		if m[2] != "" {
			line, _ = strconv.Atoi(m[2])
			col, _ = strconv.Atoi(m[3])
		} else if m[4] != "" {
			line, _ = strconv.Atoi(m[4])
			col, _ = strconv.Atoi(m[5])
		}
		sev := SeverityError
		if m[6] == "warning" {
			sev = SeverityWarning
		}
		diags = append(diags, BuildDiagnostic{
			File:     m[1],
			Line:     line,
			Column:   col,
			Severity: sev,
			Message:  strings.TrimSpace(m[7]),
		})
	}
	return diags
}
