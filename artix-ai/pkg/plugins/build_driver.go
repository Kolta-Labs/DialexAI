package plugins

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"

	"artix/pkg/policy"
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
	Warm(ctx context.Context, rootDir string) error
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

func (g *GoDriver) CompileCmd(rootDir string) string {
	return "GOFLAGS=-mod=mod GOPROXY=off go build ./..."
}

func (g *GoDriver) TestCmd(rootDir string) string {
	return "GOFLAGS=-mod=mod GOPROXY=off go test -v ./..."
}

func (g *GoDriver) LintCmd(rootDir string) string {
	return "GOFLAGS=-mod=mod GOPROXY=off go vet ./..."
}

func (g *GoDriver) Warm(ctx context.Context, rootDir string) error {
	cmd := exec.CommandContext(ctx, "go", "mod", "download")
	cmd.Dir = rootDir
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("go mod download failed: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func (g *GoDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, g.CompileCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("go", "compile"),
	})
}

func (g *GoDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, g.TestCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("go", "test"),
	})
}

func (g *GoDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, g.LintCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("go", "lint"),
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

func (gr *GradleDriver) CompileCmd(rootDir string) string {
	return gr.gradlewCmd(rootDir) + " assembleDebug --offline"
}

func (gr *GradleDriver) TestCmd(rootDir string) string {
	return gr.gradlewCmd(rootDir) + " testDebugUnitTest --offline"
}

func (gr *GradleDriver) LintCmd(rootDir string) string {
	return gr.gradlewCmd(rootDir) + " ktlintCheck --offline"
}

func (gr *GradleDriver) Warm(ctx context.Context, rootDir string) error {
	gradlew := gr.gradlewCmd(rootDir)
	cmd := exec.CommandContext(ctx, "sh", "-c", gradlew+" --refresh-dependencies dependencies testClasses")
	cmd.Dir = rootDir
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("gradle pre-warm failed: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func (gr *GradleDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, gr.CompileCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("gradle", "compile"),
	})
}

func (gr *GradleDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, gr.TestCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("gradle", "test"),
	})
}

func (gr *GradleDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, gr.LintCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("gradle", "lint"),
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

func (c *CargoDriver) CompileCmd(rootDir string) string {
	return "cargo check --offline"
}

func (c *CargoDriver) TestCmd(rootDir string) string {
	return "cargo test --offline"
}

func (c *CargoDriver) LintCmd(rootDir string) string {
	return "cargo clippy --offline"
}

func (c *CargoDriver) Warm(ctx context.Context, rootDir string) error {
	cmd := exec.CommandContext(ctx, "cargo", "fetch")
	cmd.Dir = rootDir
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("cargo fetch failed: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func (c *CargoDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, c.CompileCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("cargo", "compile"),
	})
}

func (c *CargoDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, c.TestCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("cargo", "test"),
	})
}

func (c *CargoDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, c.LintCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("cargo", "lint"),
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

func (n *NpmDriver) CompileCmd(rootDir string) string {
	return "npm run build --if-present --offline"
}

func (n *NpmDriver) TestCmd(rootDir string) string {
	return "npm test --offline"
}

func (n *NpmDriver) LintCmd(rootDir string) string {
	return "npm run lint --if-present --offline"
}

func (n *NpmDriver) Warm(ctx context.Context, rootDir string) error {
	cmd := exec.CommandContext(ctx, "npm", "ci")
	cmd.Dir = rootDir
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("npm ci failed: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	return nil
}

func (n *NpmDriver) Compile(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, n.CompileCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("npm", "compile"),
	})
}

func (n *NpmDriver) Test(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, n.TestCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("npm", "test"),
	})
}

func (n *NpmDriver) Lint(ctx context.Context, rootDir string) *sandbox.ExecResult {
	box := sandbox.NewSandbox(rootDir)
	return box.Run(ctx, n.LintCmd(rootDir), &sandbox.ExecOptions{
		Cwd:     rootDir,
		Timeout: policy.GetTimeout("npm", "lint"),
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

// DetectOfflineCacheMiss parses build failure output and identifies any missing offline artifact.
func DetectOfflineCacheMiss(output string) (string, bool) {
	// 1. Gradle patterns
	reGradleResolve := regexp.MustCompile(`(?i)(?:Could not resolve|Could not download)\s+([^\s:]+:[^\s:]+:[^\s:]+)[.\s]`)
	if m := reGradleResolve.FindStringSubmatch(output); len(m) > 1 {
		return strings.TrimRight(m[1], "."), true
	}
	reGradleOffline := regexp.MustCompile(`(?i)No cached version of\s+([^\s:]+:[^\s:]+:[^\s:]+)\s+available for offline mode`)
	if m := reGradleOffline.FindStringSubmatch(output); len(m) > 1 {
		return m[1], true
	}

	// 2. Go patterns
	reGoProxy := regexp.MustCompile(`(?:go:\s+)?([^\s:]+@[^\s:]+|[^\s:]+):\s*(?:reading\s+[^\s:]+:)?\s*GOPROXY=off`)
	if m := reGoProxy.FindStringSubmatch(output); len(m) > 1 {
		return m[1], true
	}
	reGoFind := regexp.MustCompile(`cannot find module providing package\s+([^\s:]+)`)
	if m := reGoFind.FindStringSubmatch(output); len(m) > 1 {
		return m[1], true
	}

	// 3. Cargo patterns
	reCargo := regexp.MustCompile(`(?i)failed to select a version for the requirement\s+['"` + "`" + `]?([a-zA-Z0-9_\-]+)`)
	if m := reCargo.FindStringSubmatch(output); len(m) > 1 {
		return m[1], true
	}

	// 4. Npm patterns
	reNpmCache := regexp.MustCompile(`(?i)package\s+([@a-zA-Z0-9_\-\./]+)\s+not in cache`)
	if m := reNpmCache.FindStringSubmatch(output); len(m) > 1 {
		return m[1], true
	}
	reNpmENOTCACHED := regexp.MustCompile(`(?i)ENOTCACHED`)
	if reNpmENOTCACHED.MatchString(output) {
		reNpmPkg := regexp.MustCompile(`(?:npm ERR!\s+)?(?:request to https?://[^\s]+/([^\s/]+)|package\s+([^\s]+))`)
		if m := reNpmPkg.FindStringSubmatch(output); len(m) > 1 {
			pkg := m[1]
			if pkg == "" && len(m) > 2 {
				pkg = m[2]
			}
			if pkg != "" {
				return pkg, true
			}
		}
		return "unknown-npm-package", true
	}

	return "", false
}
