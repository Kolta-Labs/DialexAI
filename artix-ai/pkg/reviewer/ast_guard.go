package reviewer

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"path/filepath"
	"regexp"
	"strings"
)

var defaultProtectedPrefixes = []string{
	".github/",
	".gitlab-ci",
	".circleci/",
	".artix/",
	".kritix/",
	"policy.json",
	"steering.json",
	"AGENTS.md",
	"CLAUDE.md",
	"GEMINI.md",
	"Makefile",
	"package.json",
	"package-lock.json",
	"build.gradle",
	"build.gradle.kts",
	"pom.xml",
	"go.mod",
	"go.sum",
	"Cargo.toml",
	"requirements.txt",
	"Pipfile",
	"pyproject.toml",
}

// CheckProtectedPaths verifies that unified diffs do not touch CI, steering, or governance files.
func CheckProtectedPaths(diff string, customProtected ...string) []string {
	var violations []string
	protected := append(defaultProtectedPrefixes, customProtected...)

	files := extractTouchedFiles(diff)
	for _, f := range files {
		clean := filepath.Clean(f)
		clean = strings.TrimPrefix(clean, "./")
		for _, p := range protected {
			if strings.HasPrefix(clean, p) || clean == p || filepath.Base(clean) == p {
				violations = append(violations, fmt.Sprintf("protected path violation: modification to protected system/governance file %q is strictly blocked", f))
				break
			}
		}
	}
	return violations
}

// CheckTestIntegrity ensures tests are not deleted, assertions gutted, or test skipping introduced.
func CheckTestIntegrity(diff string) []string {
	var violations []string
	lines := strings.Split(diff, "\n")

	var deletedAssertions int
	var addedAssertions int

	for i, line := range lines {
		// Ignore diff header lines
		if strings.HasPrefix(line, "---") || strings.HasPrefix(line, "+++") {
			continue
		}

		// Detect test deletions
		if strings.HasPrefix(line, "-") {
			trimmed := strings.TrimSpace(line[1:])
			if strings.HasPrefix(trimmed, "func Test") {
				violations = append(violations, fmt.Sprintf("test integrity violation: test function deletion detected: %s", trimmed))
			} else if strings.HasPrefix(trimmed, "@Test") || strings.HasPrefix(trimmed, "fun test") {
				violations = append(violations, fmt.Sprintf("test integrity violation: test method deletion detected: %s", trimmed))
			} else if strings.HasPrefix(trimmed, "it(") || strings.HasPrefix(trimmed, "test(") || strings.HasPrefix(trimmed, "describe(") {
				violations = append(violations, fmt.Sprintf("test integrity violation: test block deletion detected: %s", trimmed))
			} else if strings.HasPrefix(trimmed, "def test_") {
				violations = append(violations, fmt.Sprintf("test integrity violation: Python test deletion detected: %s", trimmed))
			}

			// Track removed assertions
			if isAssertionStatement(trimmed) {
				deletedAssertions++
			}
		}

		// Detect test skips & gutted bodies
		if strings.HasPrefix(line, "+") {
			trimmed := strings.TrimSpace(line[1:])
			// Exclude comments
			if strings.HasPrefix(trimmed, "//") || strings.HasPrefix(trimmed, "/*") || strings.HasPrefix(trimmed, "#") {
				continue
			}

			if strings.Contains(trimmed, "t.Skip(") || strings.Contains(trimmed, "t.SkipNow()") {
				violations = append(violations, "test integrity violation: Go test skipping is forbidden (t.Skip)")
			} else if strings.Contains(trimmed, "@Ignore") || strings.Contains(trimmed, "@Disabled") {
				violations = append(violations, "test integrity violation: test disablement is forbidden (@Ignore/@Disabled)")
			} else if strings.Contains(trimmed, ".skip(") || strings.Contains(trimmed, "xit(") || strings.Contains(trimmed, "xtest(") {
				violations = append(violations, "test integrity violation: test skipping is forbidden (.skip/xit/xtest)")
			} else if strings.Contains(trimmed, "@pytest.mark.skip") || strings.Contains(trimmed, "@unittest.skip") {
				violations = append(violations, "test integrity violation: test skipping is forbidden (@skip)")
			}

			// Detect gutted test function bodies e.g. func TestFoo(t *testing.T) {} or func TestFoo(t *testing.T) { return }
			if strings.HasPrefix(trimmed, "func Test") && (strings.HasSuffix(trimmed, "{}") || strings.HasSuffix(trimmed, "{ return }") || strings.HasSuffix(trimmed, "{ return; }")) {
				violations = append(violations, fmt.Sprintf("test integrity violation: gutted empty test body detected: %s", trimmed))
			} else if strings.HasPrefix(trimmed, "func Test") && i+1 < len(lines) {
				nextLine := strings.TrimSpace(lines[i+1])
				if nextLine == "+}" || nextLine == "+  return" || nextLine == "+	return" {
					violations = append(violations, fmt.Sprintf("test integrity violation: gutted test function with early return: %s", trimmed))
				}
			}

			if isAssertionStatement(trimmed) {
				addedAssertions++
			}
		}
	}

	// Flag drastic assertion destruction without replacement
	if deletedAssertions >= 3 && addedAssertions == 0 {
		violations = append(violations, fmt.Sprintf("test integrity violation: gutted assertions detected (%d assertion statements removed without replacement)", deletedAssertions))
	}

	return deduplicateStrings(violations)
}

func isAssertionStatement(s string) bool {
	lower := strings.ToLower(s)
	return strings.Contains(lower, "assert.") ||
		strings.Contains(lower, "require.") ||
		strings.Contains(lower, "t.fatal") ||
		strings.Contains(lower, "t.error") ||
		strings.Contains(lower, "assertequals") ||
		strings.Contains(lower, "asserttrue") ||
		strings.Contains(lower, "assertfalse") ||
		strings.Contains(lower, "expect(")
}

// CheckSemanticASTTaboos checks diff against taboo patterns using AST parsing for Go and comment-aware filtering.
// If workspaceDir is provided, whole post-patch files on disk are parsed to catch multi-statement and cross-declaration patterns.
func CheckSemanticASTTaboos(diff string, tabooList []string, workspaceDir ...string) []string {
	var violations []string
	if len(tabooList) == 0 || strings.TrimSpace(diff) == "" {
		return violations
	}

	// 1. If workspaceDir is provided, parse whole post-patch Go files
	var wholeFiles []*ast.File
	fset := token.NewFileSet()
	if len(workspaceDir) > 0 && workspaceDir[0] != "" {
		for _, f := range extractTouchedFiles(diff) {
			if strings.HasSuffix(f, ".go") {
				fullPath := filepath.Join(workspaceDir[0], f)
				if node, err := parser.ParseFile(fset, fullPath, nil, parser.AllErrors); err == nil {
					wholeFiles = append(wholeFiles, node)
				}
			}
		}
	}

	// 2. Extract added code lines (excluding comments)
	addedCodeLines := extractAddedCodeLines(diff)
	addedCodeBlob := strings.Join(addedCodeLines, "\n")

	// 3. Perform Go AST analysis on Go code snippets and whole files if present
	astViolations := inspectGoASTForTaboos(addedCodeBlob, tabooList, wholeFiles...)
	violations = append(violations, astViolations...)

	// 4. Multi-language semantic rule matching for Kotlin, Swift, TypeScript, Python
	semanticViolations := inspectMultiLanguageSemantics(addedCodeBlob, tabooList)
	violations = append(violations, semanticViolations...)

	return deduplicateStrings(violations)
}

func extractTouchedFiles(diff string) []string {
	var files []string
	lines := strings.Split(diff, "\n")
	reDiff := regexp.MustCompile(`^diff --git a/(.*) b/(.*)$`)
	rePlus := regexp.MustCompile(`^\+\+\+ b/(.*)$`)

	for _, line := range lines {
		if m := reDiff.FindStringSubmatch(line); len(m) > 2 {
			files = append(files, m[2])
		} else if m := rePlus.FindStringSubmatch(line); len(m) > 1 {
			if m[1] != "/dev/null" {
				files = append(files, m[1])
			}
		}
	}
	return deduplicateStrings(files)
}

func extractAddedCodeLines(diff string) []string {
	var lines []string
	for _, l := range strings.Split(diff, "\n") {
		if strings.HasPrefix(l, "+") && !strings.HasPrefix(l, "+++") {
			code := strings.TrimSpace(l[1:])
			// Filter out pure comment lines to prevent false-positives
			if strings.HasPrefix(code, "//") || strings.HasPrefix(code, "/*") || strings.HasPrefix(code, "*") || strings.HasPrefix(code, "#") {
				continue
			}
			if code != "" {
				lines = append(lines, code)
			}
		}
	}
	return lines
}

func inspectGoASTForTaboos(code string, taboos []string, wholeFiles ...*ast.File) []string {
	var violations []string
	if code == "" && len(wholeFiles) == 0 {
		return violations
	}

	taboosJoined := strings.ToLower(strings.Join(taboos, " "))
	checkDefaultClient := strings.Contains(taboosJoined, "defaultclient")
	checkDefaultServeMux := strings.Contains(taboosJoined, "defaultservemux")
	checkDefaultTransport := strings.Contains(taboosJoined, "defaulttransport")
	checkInsecureTLS := strings.Contains(taboosJoined, "insecureskipverify") || strings.Contains(taboosJoined, "tls")
	checkShellExec := strings.Contains(taboosJoined, "exec.command") || strings.Contains(taboosJoined, "shell") || strings.Contains(taboosJoined, "raw shell")

	// Multi-strategy parsing: handles imports, package-level declarations, function bodies, and bare statements
	var parsedFiles []*ast.File
	parsedFiles = append(parsedFiles, wholeFiles...)
	fset := token.NewFileSet()

	// Strategy 0: Direct parse if code already contains a package declaration
	if strings.Contains(code, "package ") {
		if node, err := parser.ParseFile(fset, "diff_direct.go", code, parser.AllErrors); err == nil {
			parsedFiles = append(parsedFiles, node)
		}
	}

	// Strategy A: parse as package-level declarations (handles imports, types, consts, funcs)
	if node, err := parser.ParseFile(fset, "diff_pkg.go", fmt.Sprintf("package p\n%s\n", code), parser.AllErrors); err == nil {
		parsedFiles = append(parsedFiles, node)
	}

	// Strategy B: parse wrapped in function body (handles bare statements, expressions, local assignments)
	if node, err := parser.ParseFile(fset, "diff_func.go", fmt.Sprintf("package p\nfunc _() {\n%s\n}\n", code), parser.AllErrors); err == nil {
		parsedFiles = append(parsedFiles, node)
	}

	if len(parsedFiles) == 0 {
		return violations
	}

	for _, node := range parsedFiles {
		ast.Inspect(node, func(n ast.Node) bool {
			// 1. Selector expressions: DefaultClient, DefaultServeMux, DefaultTransport
			if sel, ok := n.(*ast.SelectorExpr); ok {
				name := sel.Sel.Name
				if checkDefaultClient && name == "DefaultClient" {
					violations = append(violations, "AST Taboo Violation: forbidden reference to DefaultClient (net/http.DefaultClient)")
				}
				if checkDefaultServeMux && name == "DefaultServeMux" {
					violations = append(violations, "AST Taboo Violation: forbidden reference to DefaultServeMux (net/http.DefaultServeMux)")
				}
				if checkDefaultTransport && name == "DefaultTransport" {
					violations = append(violations, "AST Taboo Violation: forbidden reference to DefaultTransport (net/http.DefaultTransport)")
				}
			}

			// 2. Insecure TLS: InsecureSkipVerify: true
			if kv, ok := n.(*ast.KeyValueExpr); ok && checkInsecureTLS {
				if k, ok := kv.Key.(*ast.Ident); ok && k.Name == "InsecureSkipVerify" {
					if v, ok := kv.Value.(*ast.Ident); ok && v.Name == "true" {
						violations = append(violations, "AST Taboo Violation: forbidden insecure TLS configuration (InsecureSkipVerify: true)")
					}
				}
			}

			// 3. Command execution with shell: exec.Command("sh", ...) or exec.Command("bash", ...)
			if call, ok := n.(*ast.CallExpr); ok && checkShellExec {
				if sel, ok := call.Fun.(*ast.SelectorExpr); ok && sel.Sel.Name == "Command" {
					if len(call.Args) > 0 {
						if lit, ok := call.Args[0].(*ast.BasicLit); ok {
							val := strings.Trim(lit.Value, `"`)
							if val == "sh" || val == "bash" || val == "zsh" {
								violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden shell interpreter invocation via exec.Command(%q)", val))
							}
						}
					}
				}
			}

			return true
		})
	}

	return deduplicateStrings(violations)
}

func inspectMultiLanguageSemantics(code string, taboos []string) []string {
	var violations []string
	diffLower := strings.ToLower(code)

	for _, taboo := range tabooListCanonical(taboos) {
		lowerTaboo := strings.ToLower(strings.TrimSpace(taboo))
		if lowerTaboo == "" {
			continue
		}

		matched := false

		// Kotlin / Android
		if strings.Contains(lowerTaboo, "raw sqlite") && (strings.Contains(diffLower, "android.database.sqlite") || strings.Contains(diffLower, "sqlitedatabase") || strings.Contains(diffLower, "rawquery")) {
			matched = true
		} else if strings.Contains(lowerTaboo, "blocking main thread") && (strings.Contains(diffLower, "thread.sleep") || strings.Contains(diffLower, "runblocking") || strings.Contains(diffLower, "dispatchers.main")) {
			matched = true
		} else if strings.Contains(lowerTaboo, "runtime.exec") && (strings.Contains(diffLower, "runtime.getruntime().exec") || strings.Contains(diffLower, "processbuilder")) {
			matched = true
		}

		// Swift / macOS / iOS
		if strings.Contains(lowerTaboo, "process()") && (strings.Contains(diffLower, "process()") || strings.Contains(diffLower, "nstask") || strings.Contains(diffLower, "system(")) {
			matched = true
		}

		// TypeScript / JavaScript
		if strings.Contains(lowerTaboo, "eval") && (strings.Contains(diffLower, "eval(") || strings.Contains(diffLower, "new function(") || strings.Contains(diffLower, "dangerouslysetinnerhtml")) {
			matched = true
		} else if strings.Contains(lowerTaboo, "child_process") && (strings.Contains(diffLower, "child_process.exec") || strings.Contains(diffLower, "child_process.spawn")) {
			matched = true
		}

		// Generic core token match
		if !matched {
			corePattern := extractCoreToken(lowerTaboo)
			if corePattern != "" && strings.Contains(diffLower, corePattern) {
				matched = true
			}
		}

		if matched {
			violations = append(violations, fmt.Sprintf("Violates Taboo: %s", taboo))
		}
	}

	return violations
}

func tabooListCanonical(in []string) []string {
	return in
}

func extractCoreToken(taboo string) string {
	cleaned := taboo
	prefixes := []string{"no ", "never ", "avoid ", "do not use ", "don't use ", "prohibit "}
	for _, p := range prefixes {
		if strings.HasPrefix(cleaned, p) {
			cleaned = strings.TrimPrefix(cleaned, p)
			break
		}
	}
	return strings.TrimSpace(cleaned)
}

func deduplicateStrings(in []string) []string {
	seen := make(map[string]bool)
	var out []string
	for _, s := range in {
		if !seen[s] && s != "" {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}
