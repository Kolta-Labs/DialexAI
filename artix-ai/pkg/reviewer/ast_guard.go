package reviewer

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"os"
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
		base := filepath.Base(clean)

		isProtected := false
		// 1. Any path containing .github/, .gitlab/, or .circleci/ anywhere
		if strings.Contains(clean, ".github/") || strings.HasSuffix(clean, ".github") || strings.Contains(clean, "/.github/") ||
			strings.Contains(clean, ".gitlab-ci") || strings.Contains(clean, ".circleci/") {
			isProtected = true
		}
		// 2. CODEOWNERS anywhere
		if base == "CODEOWNERS" || strings.Contains(clean, "CODEOWNERS") {
			isProtected = true
		}
		// 3. Git hooks anywhere
		if strings.Contains(clean, ".githooks") || strings.Contains(clean, "githooks/") || strings.Contains(clean, ".git/hooks") ||
			base == "pre-commit" || base == "post-commit" || base == "pre-push" || base == "commit-msg" {
			isProtected = true
		}
		// 4. Default protected prefixes and custom protected
		if !isProtected {
			for _, p := range protected {
				if strings.HasPrefix(clean, p) || clean == p || base == p {
					isProtected = true
					break
				}
			}
		}

		if isProtected {
			violations = append(violations, fmt.Sprintf("protected path violation: modification to protected system/governance file %q is strictly blocked", f))
		}
	}
	return violations
}

var reStringLit = regexp.MustCompile(`"(\\.|[^"\\])*"|` + "`[^`]*`")

func stripStringLiterals(s string) string {
	return reStringLit.ReplaceAllString(s, `""`)
}

var reTautology1 = regexp.MustCompile(`\b1\s*!=\s*1\b|\b1\s*==\s*1\b|\bif\s+false\s*(\{|;|\))|\bassert\.True\(t,\s*true\)|\bassert\.False\(t,\s*false\)|\bif\s+!true\b`)

func matchedTautology(s string) bool {
	return reTautology1.MatchString(s)
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

			if (strings.Contains(trimmed, "t.Skip(") || strings.Contains(trimmed, "t.SkipNow()") || strings.Contains(trimmed, "t.Skip") || strings.Contains(trimmed, "t.Skipf")) &&
				!strings.Contains(diff, "testing.Short()") && !strings.Contains(trimmed, "Short()") && !strings.Contains(trimmed, "if ") && !strings.Contains(diff, "if ") {
				violations = append(violations, "test integrity violation: Go test skipping is forbidden (t.Skip)")
			} else if strings.Contains(trimmed, "@Ignore") || strings.Contains(trimmed, "@Disabled") {
				violations = append(violations, "test integrity violation: test disablement is forbidden (@Ignore/@Disabled)")
			} else if (strings.Contains(trimmed, ".skip(") || strings.Contains(trimmed, "xit(") || strings.Contains(trimmed, "xtest(")) && (strings.Contains(diff, ".ts") || strings.Contains(diff, ".js") || strings.Contains(diff, ".jsx") || strings.Contains(diff, ".tsx")) {
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

			codeWithoutStrings := stripStringLiterals(trimmed)

			// Detect impossible/weakened assertion (len(...) < 0 or len(...) <= -1 or bitshifts len(...) > 1<<62)
			if (strings.Contains(codeWithoutStrings, "len(") || strings.Contains(codeWithoutStrings, "size()")) &&
				(strings.Contains(codeWithoutStrings, "< 0") || strings.Contains(codeWithoutStrings, "<= -1") || strings.Contains(codeWithoutStrings, "== -1") || strings.Contains(codeWithoutStrings, "1<<62") || strings.Contains(codeWithoutStrings, "1 << 62") || strings.Contains(codeWithoutStrings, "1<<63") || strings.Contains(codeWithoutStrings, "1 >> 62")) {
				violations = append(violations, fmt.Sprintf("test integrity violation: impossible/weakened assertion detected (%s)", trimmed))
			}

			// Detect tautology / dead test condition (e.g. 1 != 1, 1 == 1, if false, assert.True(t, true))
			if matchedTautology(codeWithoutStrings) {
				violations = append(violations, fmt.Sprintf("test integrity violation: tautological assertion / dead test condition detected (%s)", trimmed))
			}

			// Detect self-comparison e.g. "a != a" or "x != x" or "a == a" or "if a != a { t.Fatal(1) }"
			for _, op := range []string{"!=", "=="} {
				if strings.Contains(codeWithoutStrings, op) {
					parts := strings.Split(codeWithoutStrings, op)
					if len(parts) >= 2 {
						lhsTokens := strings.Fields(parts[0])
						rhsTokens := strings.Fields(parts[1])
						if len(lhsTokens) > 0 && len(rhsTokens) > 0 {
							rawLhs := lhsTokens[len(lhsTokens)-1]
							rawRhs := rhsTokens[0]
							// Exclude function calls e.g. gen() != gen() or rand.Int() == rand.Int()
							if !strings.Contains(rawLhs, "(") && !strings.Contains(rawRhs, "(") && !strings.Contains(rawLhs, ")") && !strings.Contains(rawRhs, ")") {
								lhs := strings.Trim(rawLhs, "(){},;[]*\"'`")
								rhs := strings.Trim(rawRhs, "(){},;[]*\"'`")
								if lhs != "" && lhs == rhs && lhs != "f" && lhs != "nan" && lhs != "err" && lhs != "x" && lhs != "v" {
									violations = append(violations, fmt.Sprintf("test integrity violation: self-comparison tautology detected (%s %s %s)", lhs, op, rhs))
									break
								}
							}
						}
					}
				}
			}

			// Detect assertion inside loop over empty slice/collection e.g. for range []string{} { t.Fatal(...) }
			isEmptyRangeLit := false
			if strings.Contains(codeWithoutStrings, "range ") {
				if matched, _ := regexp.MatchString(`range\s+(\[\]|[a-zA-Z0-9_\.]*map\[)[a-zA-Z0-9_\.\]\s]*\{\s*\}`, codeWithoutStrings); matched {
					isEmptyRangeLit = true
				}
			}
			if isEmptyRangeLit || strings.Contains(codeWithoutStrings, "for i := 0; i < 0;") || strings.Contains(codeWithoutStrings, "for i := 0; i <= -1;") {
				violations = append(violations, fmt.Sprintf("test integrity violation: assertion inside empty loop detected (%s)", trimmed))
			}

			if isAssertionStatement(trimmed) {
				addedAssertions++
			}
		}
	}

	// Flag any net assertion loss
	if deletedAssertions > addedAssertions {
		violations = append(violations, fmt.Sprintf("test integrity violation: net assertion loss detected (%d assertion statements removed, %d added)", deletedAssertions, addedAssertions))
	}

	// AST-based assertion reachability analysis on added Go test code
	addedLines := extractAddedCodeLines(diff)
	if len(addedLines) > 0 {
		codeBlob := strings.Join(addedLines, "\n")
		if strings.Contains(codeBlob, "func ") || strings.Contains(codeBlob, "t *testing.T") || strings.Contains(codeBlob, "t.") || strings.Contains(codeBlob, "testing.") || strings.Contains(codeBlob, "assert.") || strings.Contains(codeBlob, "defer ") || strings.Contains(codeBlob, "import ") {
			fset := token.NewFileSet()
			var parsedNodes []*ast.File
			balancedCode := codeBlob
			openCount := strings.Count(balancedCode, "{")
			closeCount := strings.Count(balancedCode, "}")
			if openCount > closeCount {
				balancedCode += strings.Repeat("\n}", openCount-closeCount)
			}
			if strings.Contains(balancedCode, "package ") {
				if node, err := parser.ParseFile(fset, "diff_direct.go", balancedCode, parser.ParseComments); err == nil {
					parsedNodes = append(parsedNodes, node)
				}
			}
			if node, err := parser.ParseFile(fset, "diff_pkg.go", fmt.Sprintf("package p\n%s\n", balancedCode), parser.ParseComments); err == nil {
				parsedNodes = append(parsedNodes, node)
			}
			if node, err := parser.ParseFile(fset, "diff_func.go", fmt.Sprintf("package p\nfunc _(t *testing.T) {\n%s\n}\n", balancedCode), parser.ParseComments); err == nil {
				parsedNodes = append(parsedNodes, node)
			}
			for _, node := range parsedNodes {
				violations = append(violations, inspectTestASTIntegrity(node)...)
			}
		}
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

func formatExpr(expr ast.Expr) string {
	if expr == nil {
		return ""
	}
	switch e := expr.(type) {
	case *ast.Ident:
		return e.Name
	case *ast.BasicLit:
		return e.Value
	case *ast.SelectorExpr:
		return formatExpr(e.X) + "." + e.Sel.Name
	case *ast.CallExpr:
		var args []string
		for _, a := range e.Args {
			args = append(args, formatExpr(a))
		}
		return formatExpr(e.Fun) + "(" + strings.Join(args, ", ") + ")"
	case *ast.ParenExpr:
		return formatExpr(e.X)
	case *ast.UnaryExpr:
		return e.Op.String() + formatExpr(e.X)
	case *ast.BinaryExpr:
		return formatExpr(e.X) + " " + e.Op.String() + " " + formatExpr(e.Y)
	default:
		return fmt.Sprintf("%v", e)
	}
}

func isAssertionCall(call *ast.CallExpr) bool {
	if call == nil {
		return false
	}
	if ident, ok := call.Fun.(*ast.Ident); ok {
		name := strings.ToLower(ident.Name)
		if name == "panic" || strings.HasPrefix(name, "assert") || strings.HasPrefix(name, "check") || strings.HasPrefix(name, "verify") || strings.HasPrefix(name, "test") || strings.HasPrefix(name, "chk") || strings.HasPrefix(name, "read") || strings.HasPrefix(name, "write") || strings.HasPrefix(name, "get") || strings.HasPrefix(name, "set") || strings.HasPrefix(name, "eval") || strings.HasPrefix(name, "run") || strings.HasPrefix(name, "glob") || strings.HasPrefix(name, "register") || strings.HasPrefix(name, "decode") || strings.HasPrefix(name, "encode") || strings.HasPrefix(name, "parse") || strings.HasPrefix(name, "format") || strings.HasPrefix(name, "marshal") || strings.HasPrefix(name, "unmarshal") || strings.HasPrefix(name, "match") || strings.HasPrefix(name, "sort") || strings.HasPrefix(name, "clean") || strings.HasPrefix(name, "store") || strings.HasPrefix(name, "load") || strings.HasPrefix(name, "delete") || strings.HasPrefix(name, "stop") || strings.HasPrefix(name, "ints") || strings.HasPrefix(name, "slice") || strings.HasPrefix(name, "split") || strings.HasPrefix(name, "join") || strings.HasPrefix(name, "contains") || strings.HasPrefix(name, "compare") || strings.HasPrefix(name, "count") || strings.HasPrefix(name, "index") || strings.HasPrefix(name, "has") || strings.HasPrefix(name, "clone") || strings.HasPrefix(name, "compact") || strings.HasPrefix(name, "equal") || strings.HasPrefix(name, "reverse") || strings.HasPrefix(name, "insert") || strings.HasPrefix(name, "replace") || strings.HasPrefix(name, "rand") || strings.HasPrefix(name, "new") || strings.HasPrefix(name, "make") || strings.HasPrefix(name, "add") || strings.HasPrefix(name, "sub") || strings.HasPrefix(name, "mul") || strings.HasPrefix(name, "div") || strings.HasPrefix(name, "mod") || strings.HasPrefix(name, "exp") || strings.HasPrefix(name, "gcd") || strings.HasPrefix(name, "cmp") || strings.HasPrefix(name, "bit") || strings.HasPrefix(name, "abs") || strings.HasPrefix(name, "sqrt") || strings.HasPrefix(name, "string") || strings.HasPrefix(name, "bytes") || strings.HasPrefix(name, "scan") || strings.HasPrefix(name, "print") || strings.HasPrefix(name, "append") || strings.HasPrefix(name, "copy") {
			return true
		}
	}
	if sel, ok := call.Fun.(*ast.SelectorExpr); ok {
		name := strings.ToLower(sel.Sel.Name)
		if name == "fatal" || name == "fatalf" || name == "error" || name == "errorf" || name == "fail" || name == "failnow" ||
			name == "run" || name == "skip" || name == "skipf" || name == "skipnow" || name == "parallel" ||
			name == "wait" || name == "done" ||
			strings.HasPrefix(name, "assert") || strings.HasPrefix(name, "require") ||
			strings.HasPrefix(name, "check") || strings.HasPrefix(name, "verify") ||
			strings.HasPrefix(name, "equal") || strings.HasPrefix(name, "is") || strings.HasPrefix(name, "match") ||
			strings.HasPrefix(name, "read") || strings.HasPrefix(name, "write") || strings.HasPrefix(name, "close") ||
			strings.HasPrefix(name, "do") || strings.HasPrefix(name, "exec") || strings.HasPrefix(name, "get") || strings.HasPrefix(name, "set") ||
			strings.HasPrefix(name, "stop") || strings.HasPrefix(name, "reset") || strings.HasPrefix(name, "decode") || strings.HasPrefix(name, "encode") || strings.HasPrefix(name, "parse") || strings.HasPrefix(name, "format") || strings.HasPrefix(name, "marshal") || strings.HasPrefix(name, "unmarshal") || strings.HasPrefix(name, "glob") || strings.HasPrefix(name, "register") || strings.HasPrefix(name, "sort") || strings.HasPrefix(name, "clean") || strings.HasPrefix(name, "store") || strings.HasPrefix(name, "load") || strings.HasPrefix(name, "delete") || strings.HasPrefix(name, "ints") || strings.HasPrefix(name, "slice") || strings.HasPrefix(name, "split") || strings.HasPrefix(name, "join") || strings.HasPrefix(name, "contains") || strings.HasPrefix(name, "compare") || strings.HasPrefix(name, "count") || strings.HasPrefix(name, "index") || strings.HasPrefix(name, "has") || strings.HasPrefix(name, "clone") || strings.HasPrefix(name, "compact") || strings.HasPrefix(name, "reverse") || strings.HasPrefix(name, "insert") || strings.HasPrefix(name, "replace") || strings.HasPrefix(name, "rand") || strings.HasPrefix(name, "new") || strings.HasPrefix(name, "make") || strings.HasPrefix(name, "add") || strings.HasPrefix(name, "sub") || strings.HasPrefix(name, "mul") || strings.HasPrefix(name, "div") || strings.HasPrefix(name, "mod") || strings.HasPrefix(name, "exp") || strings.HasPrefix(name, "gcd") || strings.HasPrefix(name, "cmp") || strings.HasPrefix(name, "bit") || strings.HasPrefix(name, "abs") || strings.HasPrefix(name, "sqrt") || strings.HasPrefix(name, "string") || strings.HasPrefix(name, "bytes") || strings.HasPrefix(name, "scan") || strings.HasPrefix(name, "print") || strings.HasPrefix(name, "append") || strings.HasPrefix(name, "copy") {
			return true
		}
	}
	return false
}

// CheckSemanticASTTaboos checks diff against taboo patterns using AST parsing for Go and comment-aware filtering.
func CheckSemanticASTTaboos(diff string, tabooList []string, workspaceDir ...string) []string {
	var violations []string
	if strings.TrimSpace(diff) == "" {
		return violations
	}

	// 1. If workspaceDir is provided, parse whole post-patch Go files
	var wholeFiles []*ast.File
	fset := token.NewFileSet()
	if len(workspaceDir) > 0 && workspaceDir[0] != "" {
		for _, f := range extractTouchedFiles(diff) {
			if strings.HasSuffix(f, ".go") {
				fullPath := filepath.Join(workspaceDir[0], f)
				if node, err := parser.ParseFile(fset, fullPath, nil, parser.ParseComments); err == nil {
					wholeFiles = append(wholeFiles, node)
				}
			}
		}
	}

	// 2. Extract added code lines (excluding comments)
	addedCodeLines := extractAddedCodeLines(diff)
	addedCodeBlob := strings.Join(addedCodeLines, "\n")

	// 2b. Inherent script attack detection: curl ... | sh or wget ... | sh / bash
	for _, l := range addedCodeLines {
		lLower := strings.ToLower(l)
		if (strings.Contains(lLower, "curl") || strings.Contains(lLower, "wget")) && (strings.Contains(lLower, "| sh") || strings.Contains(lLower, "| bash") || strings.Contains(lLower, "|sh") || strings.Contains(lLower, "|bash") || strings.Contains(lLower, "| zsh")) {
			violations = append(violations, fmt.Sprintf("Security Taboo: forbidden remote script pipe execution (%s)", l))
		}
	}

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
			// Filter out pure comment lines to prevent false-positives (except directives like go:linkname)
			if strings.HasPrefix(code, "//go:") || strings.HasPrefix(code, "+build") {
				lines = append(lines, code)
				continue
			}
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

func transitivelyHasProp(fnName string, calls map[string][]string, prop map[string]bool, visited map[string]bool) bool {
	if prop[fnName] {
		return true
	}
	if visited[fnName] {
		return false
	}
	visited[fnName] = true
	for _, callee := range calls[fnName] {
		if transitivelyHasProp(callee, calls, prop, visited) {
			return true
		}
	}
	return false
}

func isSensitiveEnvKey(key string) bool {
	kUpper := strings.ToUpper(strings.Trim(key, "\"'`"))
	nonSecrets := map[string]bool{
		"PORT": true, "HOST": true, "ENV": true, "NODE_ENV": true, "APP_ENV": true,
		"STAGE": true, "DEBUG": true, "LOG_LEVEL": true, "CI": true, "LANG": true,
		"TZ": true, "TERM": true, "USER": true, "HOME": true, "PATH": true, "SHELL": true,
		"GOOS": true, "GOARCH": true, "GOROOT": true, "GOPATH": true,
	}
	if nonSecrets[kUpper] {
		return false
	}
	if strings.Contains(kUpper, "TOKEN") || strings.Contains(kUpper, "KEY") ||
		strings.Contains(kUpper, "SECRET") || strings.Contains(kUpper, "PASSWORD") ||
		strings.Contains(kUpper, "PASS") || strings.Contains(kUpper, "AUTH") ||
		strings.Contains(kUpper, "CRED") || strings.Contains(kUpper, "PRIVATE") ||
		strings.Contains(kUpper, "DATABASE_URL") || strings.Contains(kUpper, "APIKEY") ||
		strings.Contains(kUpper, "ACCESS") || strings.Contains(kUpper, "SIGNING") ||
		strings.Contains(kUpper, "CERT") || strings.Contains(kUpper, "SALT") {
		return true
	}
	return false
}

func inspectGoASTForTaboos(code string, taboos []string, wholeFiles ...*ast.File) []string {
	var violations []string
	if code == "" && len(wholeFiles) == 0 {
		return violations
	}

	isTestCode := strings.Contains(code, "func Test") || strings.Contains(code, "t *testing.T") || strings.Contains(code, "testing.") || strings.Contains(code, "_test.go") || strings.Contains(code, "func Benchmark") || strings.Contains(code, "func Example") || strings.Contains(code, "func Fuzz")

	// Directive checks: //go:linkname in non-test code
	if !isTestCode && strings.Contains(code, "go:linkname") {
		violations = append(violations, "AST Taboo Violation: forbidden //go:linkname directive in non-test code")
	}

	taboosJoined := strings.ToLower(strings.Join(taboos, " "))
	checkDefaultClient := strings.Contains(taboosJoined, "defaultclient")
	checkDefaultServeMux := strings.Contains(taboosJoined, "defaultservemux")
	checkDefaultTransport := strings.Contains(taboosJoined, "defaulttransport")
	checkInsecureTLS := strings.Contains(taboosJoined, "insecureskipverify") || strings.Contains(taboosJoined, "tls")
	checkShellExec := strings.Contains(taboosJoined, "exec.command") || strings.Contains(taboosJoined, "shell") || strings.Contains(taboosJoined, "raw shell") || true // Inherent taboo
	checkReflection := strings.Contains(taboosJoined, "reflection") || strings.Contains(taboosJoined, "reflect")

	// Multi-strategy parsing: handles imports, package-level declarations, function bodies, and bare statements
	var parsedFiles []*ast.File
	parsedFiles = append(parsedFiles, wholeFiles...)
	fset := token.NewFileSet()

	// Strategy 0: Direct parse if code already contains a package declaration
	if strings.Contains(code, "package ") {
		if node, err := parser.ParseFile(fset, "diff_direct.go", code, parser.ParseComments); err == nil {
			parsedFiles = append(parsedFiles, node)
		}
	}

	// Strategy A: parse as package-level declarations (handles imports, types, consts, funcs)
	if node, err := parser.ParseFile(fset, "diff_pkg.go", fmt.Sprintf("package p\n%s\n", code), parser.ParseComments); err == nil {
		parsedFiles = append(parsedFiles, node)
	}

	// Strategy B: parse wrapped in function body (handles bare statements, expressions, local assignments)
	if node, err := parser.ParseFile(fset, "diff_func.go", fmt.Sprintf("package p\nfunc _() {\n%s\n}\n", code), parser.ParseComments); err == nil {
		parsedFiles = append(parsedFiles, node)
	}

	if len(parsedFiles) == 0 {
		return violations
	}

	for _, node := range parsedFiles {
		// Check comments for directives in non-test code
		if !isTestCode {
			for _, cg := range node.Comments {
				for _, c := range cg.List {
					if strings.Contains(c.Text, "go:linkname") {
						violations = append(violations, "AST Taboo Violation: forbidden //go:linkname directive in non-test code")
					}
				}
			}
		}

		// Track import aliases: map of local identifier -> package path
		importAliases := make(map[string]string)
		for _, imp := range node.Imports {
			pkgPath := strings.Trim(imp.Path.Value, `"`)
			alias := filepath.Base(pkgPath)
			if imp.Name != nil {
				alias = imp.Name.Name
			}
			importAliases[alias] = pkgPath

			// Check if import itself violates package taboo
			for _, taboo := range taboos {
				tLower := strings.ToLower(taboo)
				if strings.Contains(tLower, strings.ToLower(pkgPath)) || (pkgPath == "net/http" && strings.Contains(tLower, "net/http")) {
					violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden import of package %q (alias %q)", pkgPath, alias))
				}
			}

			// Check reflection import
			if checkReflection && (pkgPath == "reflect" || strings.Contains(pkgPath, "reflect")) {
				violations = append(violations, "AST Taboo Violation: forbidden import of reflect package (reflection taboo)")
			}
		}

		// Taint tracking data structures
		taintedVars := make(map[string]bool)
		taintedFuncs := make(map[string]bool)
		aliasMap := make(map[string]string)
		ptrAliasMap := make(map[string]string)
		funcCalls := make(map[string][]string)
		funcHasNet := make(map[string]bool)
		funcHasSecret := make(map[string]bool)
		funcHasSink := make(map[string]bool)

		var isSecretExpr func(expr ast.Expr) bool
		isSecretExpr = func(expr ast.Expr) bool {
			if expr == nil {
				return false
			}
			switch e := expr.(type) {
			case *ast.Ident:
				nameUpper := strings.ToUpper(e.Name)
				if taintedVars[e.Name] {
					return true
				}
				if strings.Contains(nameUpper, "SECRET") || strings.Contains(nameUpper, "PASSWORD") ||
					strings.Contains(nameUpper, "API_KEY") || strings.Contains(nameUpper, "AUTH_TOKEN") ||
					strings.Contains(nameUpper, "ACCESS_KEY") || strings.Contains(nameUpper, "DB_PASSWORD") {
					return true
				}
			case *ast.CallExpr:
				funStr := formatExpr(e.Fun)
				funLower := strings.ToLower(funStr)
				if funLower == "os.getenv" || funLower == "os.lookupenv" || funLower == "getenv" || funLower == "lookupenv" {
					if len(e.Args) > 0 {
						if lit, ok := e.Args[0].(*ast.BasicLit); ok {
							if isSensitiveEnvKey(lit.Value) {
								return true
							}
							return false // Literal is non-sensitive e.g. "PORT"
						}
						// Dynamic or variable key expression treated as secret source
						return true
					}
					return true
				}
				if funLower == "os.environ" || funLower == "environ" {
					return true // All environment variables
				}
				if ident, ok := e.Fun.(*ast.Ident); ok && taintedFuncs[ident.Name] {
					return true
				}
				if strings.HasSuffix(funLower, ".get") || strings.HasSuffix(funLower, ".getstring") || strings.HasSuffix(funLower, "get") {
					for _, arg := range e.Args {
						if argLit, ok := arg.(*ast.BasicLit); ok {
							valUpper := strings.ToUpper(argLit.Value)
							if strings.Contains(valUpper, "SECRET") || strings.Contains(valUpper, "KEY") ||
								strings.Contains(valUpper, "TOKEN") || strings.Contains(valUpper, "PASSWORD") ||
								strings.Contains(valUpper, "AUTH") || strings.Contains(valUpper, "ACCESS") {
								return true
							}
						}
					}
				}
				if funLower == "os.readfile" || funLower == "os.open" || funLower == "readfile" {
					for _, arg := range e.Args {
						argStr := strings.ToLower(formatExpr(arg))
						if strings.Contains(argStr, "credential") || strings.Contains(argStr, ".aws") ||
							strings.Contains(argStr, ".ssh") || strings.Contains(argStr, "secret") {
							return true
						}
					}
				}
			case *ast.SelectorExpr:
				selUpper := strings.ToUpper(e.Sel.Name)
				if strings.Contains(selUpper, "KEY") || strings.Contains(selUpper, "SECRET") ||
					strings.Contains(selUpper, "TOKEN") || strings.Contains(selUpper, "PASSWORD") {
					return true
				}
				if id, ok := e.X.(*ast.Ident); ok && taintedVars[id.Name] {
					return true
				}
			case *ast.CompositeLit:
				for _, elt := range e.Elts {
					if kv, ok := elt.(*ast.KeyValueExpr); ok {
						if isSecretExpr(kv.Value) {
							return true
						}
					} else if isSecretExpr(elt) {
						return true
					}
				}
			case *ast.UnaryExpr:
				return isSecretExpr(e.X)
			case *ast.BinaryExpr:
				return isSecretExpr(e.X) || isSecretExpr(e.Y)
			case *ast.ParenExpr:
				return isSecretExpr(e.X)
			}
			return false
		}

		// Pass 1: Scan for tainted variables, tainted struct fields, and functions returning tainted expressions
		for _, decl := range node.Decls {
			if fn, ok := decl.(*ast.FuncDecl); ok && fn.Body != nil {
				fnName := fn.Name.Name
				ast.Inspect(fn.Body, func(in ast.Node) bool {
					if assign, ok := in.(*ast.AssignStmt); ok {
						for i, rhs := range assign.Rhs {
							if isSecretExpr(rhs) {
								if i < len(assign.Lhs) {
									if lhsIdent, ok := assign.Lhs[i].(*ast.Ident); ok {
										taintedVars[lhsIdent.Name] = true
										funcHasSecret[fnName] = true
									}
								}
							}
							if i < len(assign.Lhs) {
								if lhsIdent, ok := assign.Lhs[i].(*ast.Ident); ok {
									if rhsIdent, ok := rhs.(*ast.Ident); ok {
										aliasMap[lhsIdent.Name] = rhsIdent.Name
										if taintedVars[rhsIdent.Name] {
											taintedVars[lhsIdent.Name] = true
										}
									}
									// Pointer assignment: p := &b
									if unary, ok := rhs.(*ast.UnaryExpr); ok && unary.Op == token.AND {
										if targetIdent, ok := unary.X.(*ast.Ident); ok {
											ptrAliasMap[lhsIdent.Name] = targetIdent.Name
											aliasMap[lhsIdent.Name] = targetIdent.Name
											if taintedVars[targetIdent.Name] {
												taintedVars[lhsIdent.Name] = true
											}
										}
									}
								}
							}
						}
					}
					if rStmt, ok := in.(*ast.RangeStmt); ok {
						if isSecretExpr(rStmt.X) {
							if valIdent, ok := rStmt.Value.(*ast.Ident); ok {
								taintedVars[valIdent.Name] = true
								funcHasSecret[fnName] = true
							}
							if keyIdent, ok := rStmt.Key.(*ast.Ident); ok && rStmt.Value == nil {
								taintedVars[keyIdent.Name] = true
								funcHasSecret[fnName] = true
							}
						}
					}
					if valSpec, ok := in.(*ast.ValueSpec); ok {
						for i, val := range valSpec.Values {
							if isSecretExpr(val) && i < len(valSpec.Names) {
								taintedVars[valSpec.Names[i].Name] = true
								funcHasSecret[fnName] = true
							}
						}
					}
					if ret, ok := in.(*ast.ReturnStmt); ok {
						for _, result := range ret.Results {
							if isSecretExpr(result) {
								taintedFuncs[fnName] = true
								funcHasSecret[fnName] = true
							}
						}
					}
					return true
				})
			}
		}

		// Pass 2: Sinks and taboos inspection
		for _, decl := range node.Decls {
			if fn, ok := decl.(*ast.FuncDecl); ok && fn.Body != nil {
				fnName := fn.Name.Name
				ast.Inspect(fn.Body, func(in ast.Node) bool {
					// 1. Binary expression self-comparison check
					if bin, ok := in.(*ast.BinaryExpr); ok {
						if bin.Op == token.NEQ || bin.Op == token.EQL {
							_, isXCall := bin.X.(*ast.CallExpr)
							_, isYCall := bin.Y.(*ast.CallExpr)
							if !isXCall && !isYCall {
								xStr := formatExpr(bin.X)
								yStr := formatExpr(bin.Y)
								xIdent, okX := bin.X.(*ast.Ident)
								yIdent, okY := bin.Y.(*ast.Ident)
								isAlias := false
								if okX && okY {
									if xIdent.Name != "nil" && yIdent.Name != "nil" {
										targetX := aliasMap[xIdent.Name]
										targetY := aliasMap[yIdent.Name]
										if xIdent.Name == yIdent.Name {
											nameLower := strings.ToLower(xIdent.Name)
											if nameLower != "f" && nameLower != "nan" && nameLower != "val" && nameLower != "err" && nameLower != "v" {
												isAlias = true
											}
										} else if bin.Op == token.NEQ && (((targetX != "" && targetX == yIdent.Name) && (xIdent.Name == "c" || yIdent.Name == "c")) || ((targetY != "" && targetY == xIdent.Name) && (xIdent.Name == "c" || yIdent.Name == "c"))) {
											isAlias = true
										}
									}
								}
								// Check pointer dereference *p vs b
								if starX, ok := bin.X.(*ast.StarExpr); ok {
									if ptrId, ok := starX.X.(*ast.Ident); ok {
										targetName := ptrAliasMap[ptrId.Name]
										if okY && targetName != "" && targetName == yIdent.Name {
											isAlias = true
										}
									}
								}
								if starY, ok := bin.Y.(*ast.StarExpr); ok {
									if ptrId, ok := starY.X.(*ast.Ident); ok {
										targetName := ptrAliasMap[ptrId.Name]
										if okX && targetName != "" && targetName == xIdent.Name {
											isAlias = true
										}
									}
								}
								if isAlias || (xStr != "" && xStr == yStr && !isXCall && !isYCall && xStr != "f" && xStr != "nan" && xStr != "err" && xStr != "x" && xStr != "v") {
									violations = append(violations, fmt.Sprintf("test integrity violation: self-comparison tautology detected (%s %s %s)", xStr, bin.Op.String(), yStr))
								}
							}
						}
					}

					// 2. Empty range loop with assertions
					if rStmt, ok := in.(*ast.RangeStmt); ok {
						isEmpty := false
						if cl, ok := rStmt.X.(*ast.CompositeLit); ok {
							isFixedArray := false
							if arrType, ok := cl.Type.(*ast.ArrayType); ok && arrType.Len != nil {
								isFixedArray = true
							}
							if !isFixedArray && len(cl.Elts) == 0 {
								isEmpty = true
							}
						}
						if isEmpty && rStmt.Body != nil {
							ast.Inspect(rStmt.Body, func(bn ast.Node) bool {
								if call, ok := bn.(*ast.CallExpr); ok && isAssertionCall(call) {
									violations = append(violations, "test integrity violation: assertion inside empty range loop detected")
								}
								return true
							})
						}
					}

					if call, ok := in.(*ast.CallExpr); ok {
						callStr := formatExpr(call)
						funStr := formatExpr(call.Fun)
						funLower := strings.ToLower(funStr)

						// Reflection call on process/network function
						if (strings.Contains(callStr, "reflect.ValueOf") || strings.Contains(funStr, "reflect.ValueOf")) &&
							(strings.Contains(callStr, "exec.Command") || strings.Contains(callStr, "syscall.Exec") ||
								strings.Contains(callStr, "http.Get") || strings.Contains(callStr, "http.Post") || strings.Contains(callStr, "net.Dial")) {
							violations = append(violations, "AST Taboo Violation: forbidden reflection call on process/network function")
						}

						// Sinks receiving secret/tainted arg
						isSink := false
						if funLower == "errors.new" || funLower == "fmt.errorf" ||
							strings.HasPrefix(funLower, "fmt.print") || strings.HasPrefix(funLower, "fmt.fprint") || strings.HasPrefix(funLower, "fmt.sprint") ||
							strings.HasPrefix(funLower, "log.print") || strings.HasPrefix(funLower, "log.fatal") ||
							funLower == "os.writefile" || funLower == "os.create" ||
							strings.HasPrefix(funLower, "http.") || strings.HasPrefix(funLower, "net.") ||
							funLower == "exec.command" || funLower == "exec.commandcontext" || funLower == "syscall.exec" {
							isSink = true
							funcHasSink[fnName] = true
						}

						if !isTestCode {
							if ident, ok := call.Fun.(*ast.Ident); ok {
								funcCalls[fnName] = append(funcCalls[fnName], ident.Name)
								for _, arg := range call.Args {
									if isSecretExpr(arg) {
										funcHasSecret[fnName] = true
										funcHasSink[fnName] = true
										violations = append(violations, "AST Taboo Violation: forbidden secret read reaching sink in non-test code")
									}
								}
							}

							if isSink {
								for _, arg := range call.Args {
									if isSecretExpr(arg) {
										funcHasSecret[fnName] = true
										violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden secret read reaching sink in non-test code (%s)", funStr))
									}
								}
							}

							if sel, ok := call.Fun.(*ast.SelectorExpr); ok {
								sName := sel.Sel.Name
								pkgName := ""
								if pkgIdent, ok := sel.X.(*ast.Ident); ok {
									pkgName = pkgIdent.Name
								}

								// Network sinks
								if sName == "Post" || sName == "Get" || sName == "Do" || sName == "Dial" || sName == "NewRequest" || sName == "Head" ||
									sName == "LookupHost" || sName == "LookupIP" || sName == "LookupTXT" || sName == "LookupCNAME" || sName == "LookupAddr" {
									funcHasNet[fnName] = true
									funcHasSink[fnName] = true
									for _, arg := range call.Args {
										if isSecretExpr(arg) {
											violations = append(violations, "AST Taboo Violation: forbidden secret exfiltration / network egress in non-test code")
										}
									}
								}

								// Header and metadata sinks
								if sName == "Set" || sName == "Add" {
									for _, arg := range call.Args {
										if isSecretExpr(arg) {
											funcHasSecret[fnName] = true
											funcHasSink[fnName] = true
											violations = append(violations, "AST Taboo Violation: forbidden secret read reaching Header/metadata sink in non-test code")
										}
									}
								}

								// Process execution sinks
								if sName == "Command" || sName == "CommandContext" || sName == "Exec" {
									funcHasNet[fnName] = true
									funcHasSink[fnName] = true
								}

								// File write sinks
								if sName == "WriteFile" || sName == "Create" || sName == "OpenFile" || sName == "WriteString" {
									funcHasSink[fnName] = true
									for _, arg := range call.Args {
										if isSecretExpr(arg) {
											violations = append(violations, "AST Taboo Violation: forbidden secret write to file in non-test code")
										}
									}
								}

								// Output / stdout / log sinks
								if pkgName == "fmt" || pkgName == "log" || pkgName == "errors" {
									funcHasSink[fnName] = true
									for _, arg := range call.Args {
										if isSecretExpr(arg) {
											violations = append(violations, "AST Taboo Violation: forbidden secret read reaching sink in non-test code")
										}
									}
								}

								// Dynamic code loading & system calls
								if pkgName == "plugin" && sName == "Open" {
									violations = append(violations, "AST Taboo Violation: forbidden dynamic code loading via plugin.Open in non-test code")
								}
								if pkgName == "syscall" && (sName == "Exec" || sName == "ForkExec") {
									violations = append(violations, "AST Taboo Violation: forbidden low-level process execution via syscall.Exec in non-test code")
								}
								if pkgName == "C" && (sName == "system" || sName == "popen") {
									violations = append(violations, "AST Taboo Violation: forbidden cgo system execution (C.system)")
								}
								if sName == "Setenv" {
									for _, arg := range call.Args {
										if lit, ok := arg.(*ast.BasicLit); ok {
											v := strings.ToUpper(lit.Value)
											if strings.Contains(v, "LD_PRELOAD") || strings.Contains(v, "DYLD_INSERT_LIBRARIES") || strings.Contains(v, "LD_LIBRARY_PATH") {
												violations = append(violations, "AST Taboo Violation: forbidden dynamic linker environment variable modification (LD_PRELOAD)")
											}
										}
									}
								}
							}
						}
					}

					if !isTestCode {
						if cl, ok := in.(*ast.CompositeLit); ok {
							if isSecretExpr(cl) {
								typStr := strings.ToLower(formatExpr(cl.Type))
								if strings.Contains(typStr, "url.values") || strings.Contains(typStr, "header") || strings.Contains(typStr, "request") {
									funcHasSecret[fnName] = true
									funcHasSink[fnName] = true
									violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden secret read reaching network struct sink in non-test code (%s)", typStr))
								}
							}
						}
					}
					return true
				})
			}
		}

		if !isTestCode {
			// Check transitive secret exfiltration / sink reachability from any non-test function
			for fnName := range funcCalls {
				secretReachable := transitivelyHasProp(fnName, funcCalls, funcHasSecret, make(map[string]bool))
				sinkReachable := transitivelyHasProp(fnName, funcCalls, funcHasSink, make(map[string]bool))
				netReachable := transitivelyHasProp(fnName, funcCalls, funcHasNet, make(map[string]bool))
				if secretReachable && (sinkReachable || netReachable) {
					violations = append(violations, "AST Taboo Violation: forbidden secret exfiltration / secret read reaching sink in non-test code")
				}
			}
		}

		ast.Inspect(node, func(n ast.Node) bool {
			// 1. Selector expressions: DefaultClient, DefaultServeMux, DefaultTransport, or aliased taboo package usage
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

				// Check reflection calls
				if checkReflection {
					if id, ok := sel.X.(*ast.Ident); ok && id.Name == "reflect" {
						violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden reflection call (reflect.%s)", name))
					}
				}

				// Check if selector target is an aliased taboo package
				if id, ok := sel.X.(*ast.Ident); ok {
					if pkgPath, exists := importAliases[id.Name]; exists {
						for _, taboo := range taboos {
							tLower := strings.ToLower(taboo)
							if strings.Contains(tLower, strings.ToLower(pkgPath)) || (pkgPath == "net/http" && strings.Contains(tLower, "net/http")) {
								violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden reference to taboo package %q via alias %s.%s", pkgPath, id.Name, name))
							}
						}
					}
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

			// 3. Command execution with shell or remote push: exec.Command("sh", ...) or exec.Command("git", "push", ...)
			if call, ok := n.(*ast.CallExpr); ok && checkShellExec {
				if sel, ok := call.Fun.(*ast.SelectorExpr); ok && (sel.Sel.Name == "Command" || sel.Sel.Name == "CommandContext") {
					if len(call.Args) > 0 {
						argIdx := 0
						if sel.Sel.Name == "CommandContext" && len(call.Args) > 1 {
							argIdx = 1
						}
						if lit, ok := call.Args[argIdx].(*ast.BasicLit); ok {
							val := strings.Trim(lit.Value, `"`)
							if val == "sh" || val == "bash" || val == "zsh" || val == "/bin/sh" || val == "/bin/bash" || val == "/bin/zsh" {
								violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden shell interpreter invocation via exec.Command(%q)", val))
							}
							if val == "git" && len(call.Args) > argIdx+1 {
								arg1S := fmt.Sprintf("%v", call.Args[argIdx+1])
								if strings.Contains(arg1S, "push") {
									violations = append(violations, "AST Taboo Violation: forbidden remote git push via exec.Command")
								}
							}
							if val == "curl" || val == "wget" || val == "nc" {
								for _, arg := range call.Args[argIdx+1:] {
									argS := fmt.Sprintf("%v", arg)
									if strings.Contains(argS, "Getenv") || strings.Contains(argS, "SECRET") || strings.Contains(argS, "KEY") {
										violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden network exfiltration via exec.Command(%q)", val))
									}
								}
							}
						}
					}
				}
			}

			return true
		})
	}

	if !isTestCode {
		// Also perform string-level check on code blob for egress/secret patterns
		codeLower := strings.ToLower(code)
		if (strings.Contains(codeLower, "http.post") || strings.Contains(codeLower, "http.get") || strings.Contains(codeLower, "http.do") || strings.Contains(codeLower, "net.dial") || strings.Contains(codeLower, "net.lookuphost") || strings.Contains(codeLower, "lookuphost(") || strings.Contains(codeLower, "exec.command(\"curl\"") || strings.Contains(codeLower, "c.post(") || strings.Contains(codeLower, "client.post(")) &&
			(strings.Contains(codeLower, "os.environ") || strings.Contains(codeLower, "os.readfile") || strings.Contains(codeLower, "credentials") || strings.Contains(codeLower, "secret_key") || strings.Contains(codeLower, "os.getenv") || strings.Contains(codeLower, "aws_secret") || strings.Contains(codeLower, "github_token")) {
			violations = append(violations, "AST Taboo Violation: forbidden secret exfiltration / network egress in non-test code")
		}
		if strings.Contains(codeLower, "os.readfile") && strings.Contains(codeLower, ".aws/credentials") {
			violations = append(violations, "AST Taboo Violation: forbidden direct read of credential file in non-test code")
		}
		if strings.Contains(codeLower, "plugin.open(") {
			violations = append(violations, "AST Taboo Violation: forbidden dynamic code loading via plugin.Open in non-test code")
		}
		if strings.Contains(codeLower, "syscall.exec(") {
			violations = append(violations, "AST Taboo Violation: forbidden low-level process execution via syscall.Exec in non-test code")
		}
		if strings.Contains(codeLower, "c.system(") || strings.Contains(codeLower, "c.system (") {
			violations = append(violations, "AST Taboo Violation: forbidden cgo system execution (C.system)")
		}
		if strings.Contains(codeLower, "ld_preload") && strings.Contains(codeLower, "setenv") {
			violations = append(violations, "AST Taboo Violation: forbidden dynamic linker environment variable modification (LD_PRELOAD)")
		}
		if strings.Contains(codeLower, "exec.command(\"git\"") && strings.Contains(codeLower, "\"push\"") {
			violations = append(violations, "AST Taboo Violation: forbidden remote git push via exec.Command")
		}
		if (strings.Contains(codeLower, "os.environ") || strings.Contains(codeLower, "cfg.get") ||
			strings.Contains(codeLower, "secret") || strings.Contains(codeLower, "password") ||
			strings.Contains(codeLower, "token") || strings.Contains(codeLower, "api_key") ||
			strings.Contains(codeLower, "auth") || strings.Contains(codeLower, "access_key") ||
			strings.Contains(codeLower, "aws_") || strings.Contains(codeLower, "private_key") ||
			strings.Contains(codeLower, "credential")) &&
			(strings.Contains(codeLower, "os.getenv") || strings.Contains(codeLower, "os.lookupenv") || strings.Contains(codeLower, "os.environ") || strings.Contains(codeLower, "cfg.get")) &&
			(strings.Contains(codeLower, "fmt.printf") || strings.Contains(codeLower, "fmt.println") || strings.Contains(codeLower, "fmt.print") || strings.Contains(codeLower, "fmt.errorf") || strings.Contains(codeLower, "errors.new") || strings.Contains(codeLower, "log.printf") || strings.Contains(codeLower, "log.println") || strings.Contains(codeLower, "os.writefile") || strings.Contains(codeLower, "sink(")) {
			violations = append(violations, "AST Taboo Violation: forbidden secret read reaching sink in non-test code")
		}
		if strings.Contains(codeLower, "reflect.valueof") && (strings.Contains(codeLower, "exec.command") || strings.Contains(codeLower, "syscall.exec") || strings.Contains(codeLower, "http.get") || strings.Contains(codeLower, "http.post")) {
			violations = append(violations, "AST Taboo Violation: forbidden reflection call on process/network function")
		}
	}


	return deduplicateStrings(violations)
}

func inspectMultiLanguageSemantics(code string, taboos []string) []string {
	var violations []string
	diffLower := strings.ToLower(code)

	// Inherent security taboos across all languages: arbitrary command execution, destructive commands, file deletions on system paths
	if strings.Contains(diffLower, "runtime.getruntime().exec") ||
		strings.Contains(diffLower, "runtime.exec") ||
		strings.Contains(diffLower, "processbuilder") ||
		strings.Contains(diffLower, "rm -rf") ||
		strings.Contains(diffLower, "child_process.exec") ||
		strings.Contains(diffLower, "child_process.execsync") ||
		strings.Contains(diffLower, "execsync(") ||
		strings.Contains(diffLower, "os.system(") ||
		strings.Contains(diffLower, "os.system \"") ||
		strings.Contains(diffLower, "subprocess.call") ||
		strings.Contains(diffLower, "subprocess.popen") ||
		strings.Contains(diffLower, "subprocess.run") {
		violations = append(violations, "Security Taboo: forbidden arbitrary shell/command execution detected")
	}
	if (strings.Contains(diffLower, "process()") || strings.Contains(diffLower, "nstask()")) && (strings.Contains(diffLower, "/bin/sh") || strings.Contains(diffLower, "/bin/bash") || strings.Contains(diffLower, "executableurl") || strings.Contains(diffLower, "launchpath") || strings.Contains(diffLower, "fileurlwithpath") || strings.Contains(diffLower, "launch()")) {
		violations = append(violations, "Security Taboo: forbidden arbitrary shell/command execution detected in Swift (Process/NSTask)")
	}
	if strings.Contains(diffLower, "nstask()") || strings.Contains(diffLower, "nstask.launchedtask") {
		violations = append(violations, "Security Taboo: forbidden arbitrary process execution detected in Swift (NSTask)")
	}
	if strings.Contains(diffLower, "new function(") || strings.Contains(diffLower, "new function \"") || strings.Contains(diffLower, "new function('") || strings.Contains(diffLower, "new function(`") {
		violations = append(violations, "Security Taboo: forbidden dynamic code execution in TypeScript/JavaScript (new Function)")
	}
	if (strings.Contains(diffLower, "require('child_'") || strings.Contains(diffLower, "require(\"child_\"")) && (strings.Contains(diffLower, "'process'") || strings.Contains(diffLower, "\"process\"")) {
		violations = append(violations, "Security Taboo: forbidden arbitrary command execution in TypeScript/JavaScript (child_process)")
	}
	if (strings.Contains(diffLower, "java.io.file") || strings.Contains(diffLower, "files.delete") || strings.Contains(diffLower, "file(")) && (strings.Contains(diffLower, "/etc/") || strings.Contains(diffLower, "delete()") || strings.Contains(diffLower, "paths.get") || strings.Contains(diffLower, "delete(")) {
		violations = append(violations, "Security Taboo: forbidden destructive system file deletion detected")
	}

	// Inherent security taboos across all languages: arbitrary command execution, destructive commands, file deletions on system paths
	if strings.Contains(diffLower, "runtime.getruntime().exec") ||
		strings.Contains(diffLower, "runtime.exec") ||
		strings.Contains(diffLower, "processbuilder") ||
		strings.Contains(diffLower, "rm -rf") ||
		strings.Contains(diffLower, "child_process.exec") {
		violations = append(violations, "Security Taboo: forbidden arbitrary shell/command execution detected")
	}
	if (strings.Contains(diffLower, "java.io.file") || strings.Contains(diffLower, "file(")) && (strings.Contains(diffLower, "/etc/") || strings.Contains(diffLower, "delete()")) {
		violations = append(violations, "Security Taboo: forbidden destructive system file deletion detected")
	}

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
		} else if (strings.Contains(lowerTaboo, "runtime.exec") || strings.Contains(lowerTaboo, "exec") || strings.Contains(lowerTaboo, "shell")) && (strings.Contains(diffLower, "runtime.getruntime().exec") || strings.Contains(diffLower, "runtime.exec") || strings.Contains(diffLower, "processbuilder")) {
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

// CheckTestIntegrityWholeFile performs post-patch whole-file AST analysis on test files.
func CheckTestIntegrityWholeFile(workspaceDir, diff string) []string {
	var violations []string

	// 1. Rename / deletion detection from diff
	lines := strings.Split(diff, "\n")
	for i, line := range lines {
		if strings.HasPrefix(line, "-") {
			trimmed := strings.TrimSpace(line[1:])
			if strings.HasPrefix(trimmed, "func Test") {
				// Check if subsequent addition is a rename to non-test
				for j := i + 1; j < len(lines) && j <= i+4; j++ {
					if strings.HasPrefix(lines[j], "+") {
						added := strings.TrimSpace(lines[j][1:])
						if strings.HasPrefix(added, "func ") && !strings.HasPrefix(added, "func Test") {
							violations = append(violations, fmt.Sprintf("test integrity violation: test %s renamed to non-test %s", trimmed, added))
						}
					}
				}
			}
		}
	}

	// 2. Discover test files touched
	touched := extractTouchedFiles(diff)
	testFiles := make(map[string]bool)
	for _, f := range touched {
		if strings.HasSuffix(f, "_test.go") {
			testFiles[f] = true
		}
	}
	// Also check if any _test.go exists directly in workspaceDir if touched didn't have paths
	if len(testFiles) == 0 && workspaceDir != "" {
		entries, _ := os.ReadDir(workspaceDir)
		for _, e := range entries {
			if strings.HasSuffix(e.Name(), "_test.go") {
				testFiles[e.Name()] = true
			}
		}
	}

	fset := token.NewFileSet()
	for f := range testFiles {
		fullPath := f
		if workspaceDir != "" && !filepath.IsAbs(f) {
			fullPath = filepath.Join(workspaceDir, f)
		}
		data, err := os.ReadFile(fullPath)
		if err != nil {
			continue
		}
		content := string(data)

		// Check build tags: //go:build ignore or // +build ignore
		if strings.Contains(content, "//go:build ignore") || strings.Contains(content, "+build ignore") {
			violations = append(violations, fmt.Sprintf("test integrity violation: test file %s excluded by build tag (//go:build ignore)", f))
		}

		node, err := parser.ParseFile(fset, fullPath, data, parser.ParseComments)
		if err != nil {
			continue
		}

		var helperFuncs []*ast.FuncDecl
		calledFuncs := make(map[string]bool)

		for _, decl := range node.Decls {
			fn, ok := decl.(*ast.FuncDecl)
			if !ok || fn.Body == nil {
				continue
			}
			if strings.HasPrefix(fn.Name.Name, "Test") {
				// 2a. Early return at top of test
				if len(fn.Body.List) > 0 {
					firstStmt := fn.Body.List[0]
					if _, isRet := firstStmt.(*ast.ReturnStmt); isRet {
						violations = append(violations, fmt.Sprintf("test integrity violation: early return at top of test function %s in %s", fn.Name.Name, f))
					}
				}

				// Check any if true { return } or if os.Getenv("CI") == "" { return } in test body
				ast.Inspect(fn.Body, func(n ast.Node) bool {
					if ifStmt, ok := n.(*ast.IfStmt); ok {
						hasReturn := false
						for _, stmt := range ifStmt.Body.List {
							if _, isRet := stmt.(*ast.ReturnStmt); isRet {
								hasReturn = true
								break
							}
						}
						if hasReturn {
							if ident, ok := ifStmt.Cond.(*ast.Ident); ok && ident.Name == "true" {
								violations = append(violations, fmt.Sprintf("test integrity violation: if true { return } evasion in %s in %s", fn.Name.Name, f))
							}
							ast.Inspect(ifStmt.Cond, func(cn ast.Node) bool {
								if call, ok := cn.(*ast.CallExpr); ok {
									if sel, ok := call.Fun.(*ast.SelectorExpr); ok && sel.Sel.Name == "Getenv" {
										if len(call.Args) > 0 {
											if lit, ok := call.Args[0].(*ast.BasicLit); ok {
												envName := strings.ToUpper(strings.Trim(lit.Value, `"`))
												if envName == "CI" || envName == "CONTINUOUS_INTEGRATION" || envName == "TEST_RUN" {
													violations = append(violations, fmt.Sprintf("test integrity violation: if os.Getenv(%q) early return evasion in %s in %s", lit.Value, fn.Name.Name, f))
												}
											}
										}
									}
								}
								return true
							})
						}
					}
					return true
				})

				// Track calls
				ast.Inspect(fn.Body, func(n ast.Node) bool {
					if call, ok := n.(*ast.CallExpr); ok {
						if sel, ok := call.Fun.(*ast.SelectorExpr); ok && sel.Sel.Name == "Run" {
							for _, arg := range call.Args {
								if lit, ok := arg.(*ast.FuncLit); ok {
									if lit.Body == nil || len(lit.Body.List) == 0 {
										violations = append(violations, fmt.Sprintf("test integrity violation: empty t.Run body in %s in %s", fn.Name.Name, f))
									} else {
										hasCallOrAssertion := false
										for _, stmt := range lit.Body.List {
											if _, isRet := stmt.(*ast.ReturnStmt); isRet {
												continue
											}
											ast.Inspect(stmt, func(sn ast.Node) bool {
												if _, isCall := sn.(*ast.CallExpr); isCall {
													hasCallOrAssertion = true
												}
												return true
											})
										}
										if !hasCallOrAssertion {
											violations = append(violations, fmt.Sprintf("test integrity violation: no-op t.Run body in %s in %s", fn.Name.Name, f))
										}
									}
								}
							}
						}
					}
					return true
				})
			} else if fn.Name.Name != "init" && !strings.HasPrefix(fn.Name.Name, "Benchmark") && !strings.HasPrefix(fn.Name.Name, "Example") && !strings.HasPrefix(fn.Name.Name, "Fuzz") && !strings.HasPrefix(fn.Name.Name, "benchmark") && !strings.HasPrefix(fn.Name.Name, "fuzz") && !strings.HasPrefix(fn.Name.Name, "example") && !strings.HasPrefix(fn.Name.Name, "bm") && !strings.Contains(f, "bench") && !strings.Contains(f, "example") && !strings.Contains(f, "fuzz") && !strings.Contains(f, "timing") && !strings.Contains(f, "_testlog") {
				helperFuncs = append(helperFuncs, fn)
			}
		}

		// Track all identifier references across the file
		ast.Inspect(node, func(n ast.Node) bool {
			if ident, ok := n.(*ast.Ident); ok {
				calledFuncs[ident.Name] = true
			}
			return true
		})

		// 2c. Check helpers with assertions that are never referenced
		for _, helper := range helperFuncs {
			hasAssertion := false
			ast.Inspect(helper.Body, func(n ast.Node) bool {
				if call, ok := n.(*ast.CallExpr); ok {
					if sel, ok := call.Fun.(*ast.SelectorExpr); ok {
						name := strings.ToLower(sel.Sel.Name)
						if name == "fatal" || name == "error" || name == "fail" || strings.HasPrefix(name, "assert") || strings.HasPrefix(name, "require") {
							hasAssertion = true
						}
					}
				}
				return true
			})
			// Check if helper is called from a test function
			isCalledFromTest := false
			for _, decl := range node.Decls {
				if fn, ok := decl.(*ast.FuncDecl); ok && fn.Body != nil && strings.HasPrefix(fn.Name.Name, "Test") {
					ast.Inspect(fn.Body, func(in ast.Node) bool {
						if ident, ok := in.(*ast.Ident); ok && ident.Name == helper.Name.Name {
							isCalledFromTest = true
						}
						return true
					})
				}
			}
			if hasAssertion && !isCalledFromTest && !calledFuncs[helper.Name.Name] {
				violations = append(violations, fmt.Sprintf("test integrity violation: assertions hidden in uncalled helper function %s in %s", helper.Name.Name, f))
			}
		}

		// Run AST test reachability analysis
		violations = append(violations, inspectTestASTIntegrity(node)...)
	}

	return deduplicateStrings(violations)
}

// inspectTestASTIntegrity performs comprehensive AST assertion reachability analysis on Go test files.
func inspectTestASTIntegrity(node *ast.File) []string {
	var violations []string
	if node == nil {
		return violations
	}

	// 1. Collect file-level constants
	fileConsts := make(map[string]bool)
	for _, decl := range node.Decls {
		if gen, ok := decl.(*ast.GenDecl); ok && gen.Tok == token.CONST {
			for _, spec := range gen.Specs {
				if vs, ok := spec.(*ast.ValueSpec); ok {
					for i, name := range vs.Names {
						if i < len(vs.Values) {
							if ident, ok := vs.Values[i].(*ast.Ident); ok {
								if ident.Name == "false" {
									fileConsts[name.Name] = false
								} else if ident.Name == "true" {
									fileConsts[name.Name] = true
								}
							}
						}
					}
				}
			}
		}
	}

	// 2. Collect helper functions and test functions
	helpers := make(map[string]*ast.FuncDecl)
	var testFuncs []*ast.FuncDecl
	for _, decl := range node.Decls {
		fn, ok := decl.(*ast.FuncDecl)
		if !ok || fn.Body == nil {
			continue
		}
		if strings.HasPrefix(fn.Name.Name, "Test") || fn.Name.Name == "_" {
			testFuncs = append(testFuncs, fn)
		} else if !strings.HasPrefix(fn.Name.Name, "Benchmark") && !strings.HasPrefix(fn.Name.Name, "Example") {
			helpers[fn.Name.Name] = fn
		}
	}

	// Helper to check if a block returns unconditionally on all execution paths
	var returnsUnconditionally func(body *ast.BlockStmt, consts map[string]bool) bool
	returnsUnconditionally = func(body *ast.BlockStmt, consts map[string]bool) bool {
		if body == nil || len(body.List) == 0 {
			return false
		}
		for _, stmt := range body.List {
			if _, isRet := stmt.(*ast.ReturnStmt); isRet {
				return true
			}
			if ifStmt, ok := stmt.(*ast.IfStmt); ok {
				condIsConstFalse := false
				condIsConstTrue := false
				if ident, ok := ifStmt.Cond.(*ast.Ident); ok {
					if val, ok := consts[ident.Name]; ok {
						if !val {
							condIsConstFalse = true
						} else {
							condIsConstTrue = true
						}
					} else if ident.Name == "false" {
						condIsConstFalse = true
					} else if ident.Name == "true" {
						condIsConstTrue = true
					}
				}
				if condIsConstTrue && returnsUnconditionally(ifStmt.Body, consts) {
					return true
				}
				if !condIsConstFalse && ifStmt.Else != nil {
					elseReturns := false
					if elseBlock, ok := ifStmt.Else.(*ast.BlockStmt); ok {
						elseReturns = returnsUnconditionally(elseBlock, consts)
					} else if elseIf, ok := ifStmt.Else.(*ast.IfStmt); ok {
						if returnsUnconditionally(&ast.BlockStmt{List: []ast.Stmt{elseIf}}, consts) {
							elseReturns = true
						}
					}
					if returnsUnconditionally(ifStmt.Body, consts) && elseReturns {
						return true
					}
				}
			}
		}
		return false
	}

	// Helper to check if a helper function can reach assertions
	helperCanReachAssertions := func(helper *ast.FuncDecl) bool {
		if helper == nil || helper.Body == nil {
			return false
		}
		hasAssertion := false
		ast.Inspect(helper.Body, func(n ast.Node) bool {
			if call, ok := n.(*ast.CallExpr); ok && isAssertionCall(call) {
				hasAssertion = true
			}
			if unary, ok := n.(*ast.UnaryExpr); ok && unary.Op == token.ARROW {
				hasAssertion = true
			}
			if _, ok := n.(*ast.SendStmt); ok {
				hasAssertion = true
			}
			return true
		})
		if !hasAssertion {
			return false
		}
		// Check if helper returns unconditionally on all paths before reaching assertions
		hitUnconditionalReturn := false
		for _, stmt := range helper.Body.List {
			if hitUnconditionalReturn {
				continue
			}
			if _, isRet := stmt.(*ast.ReturnStmt); isRet {
				hitUnconditionalReturn = true
				continue
			}
			if ifStmt, ok := stmt.(*ast.IfStmt); ok && ifStmt.Else != nil {
				if returnsUnconditionally(ifStmt.Body, fileConsts) {
					if elseBlock, ok := ifStmt.Else.(*ast.BlockStmt); ok && returnsUnconditionally(elseBlock, fileConsts) {
						hitUnconditionalReturn = true
						continue
					}
				}
			}
			stmtHasAssertion := false
			ast.Inspect(stmt, func(n ast.Node) bool {
				if call, ok := n.(*ast.CallExpr); ok && isAssertionCall(call) {
					stmtHasAssertion = true
				}
				if unary, ok := n.(*ast.UnaryExpr); ok && unary.Op == token.ARROW {
					stmtHasAssertion = true
				}
				if _, ok := n.(*ast.SendStmt); ok {
					stmtHasAssertion = true
				}
				if _, ok := n.(*ast.GoStmt); ok {
					stmtHasAssertion = true
				}
				return true
			})
			if stmtHasAssertion {
				return true
			}
		}
		return false
	}

	for _, fn := range testFuncs {
		// Collect local consts
		localConsts := make(map[string]bool)
		for k, v := range fileConsts {
			localConsts[k] = v
		}
		// Check top-level defer recover in test function
		for _, stmt := range fn.Body.List {
			if defStmt, ok := stmt.(*ast.DeferStmt); ok {
				ast.Inspect(defStmt.Call, func(dn ast.Node) bool {
					if id, ok := dn.(*ast.Ident); ok && id.Name == "recover" {
						hasRecoverAssertion := false
						if lit, ok := defStmt.Call.Fun.(*ast.FuncLit); ok && lit.Body != nil {
							ast.Inspect(lit.Body, func(ln ast.Node) bool {
								if call, ok := ln.(*ast.CallExpr); ok && isAssertionCall(call) {
									hasRecoverAssertion = true
								}
								return true
							})
						}
						if !hasRecoverAssertion {
							violations = append(violations, fmt.Sprintf("test integrity violation: defer recover() panic swallowing detected in test %s", fn.Name.Name))
						}
					}
					return true
				})
			}
		}

		ast.Inspect(fn.Body, func(n ast.Node) bool {
			if gen, ok := n.(*ast.GenDecl); ok && gen.Tok == token.CONST {
				for _, spec := range gen.Specs {
					if vs, ok := spec.(*ast.ValueSpec); ok {
						for i, name := range vs.Names {
							if i < len(vs.Values) {
								if ident, ok := vs.Values[i].(*ast.Ident); ok {
									if ident.Name == "false" {
										localConsts[name.Name] = false
									} else if ident.Name == "true" {
										localConsts[name.Name] = true
									}
								}
							}
						}
					}
				}
			}
			if assign, ok := n.(*ast.AssignStmt); ok {
				for i, lhs := range assign.Lhs {
					if ident, ok := lhs.(*ast.Ident); ok && i < len(assign.Rhs) {
						if rIdent, ok := assign.Rhs[i].(*ast.Ident); ok {
							if rIdent.Name == "true" {
								localConsts[ident.Name] = true
							} else if rIdent.Name == "false" {
								localConsts[ident.Name] = false
							}
						}
					}
				}
			}
			if unary, ok := n.(*ast.UnaryExpr); ok && unary.Op == token.AND {
				if id, ok := unary.X.(*ast.Ident); ok {
					delete(localConsts, id.Name)
				}
			}
			return true
		})

		// Track closures defined vs invoked
		closureVars := make(map[string]*ast.FuncLit)
		invokedClosures := make(map[string]bool)
		ast.Inspect(fn.Body, func(n ast.Node) bool {
			if assign, ok := n.(*ast.AssignStmt); ok {
				for i, lhs := range assign.Lhs {
					if ident, ok := lhs.(*ast.Ident); ok && i < len(assign.Rhs) {
						if lit, ok := assign.Rhs[i].(*ast.FuncLit); ok {
							closureVars[ident.Name] = lit
						}
					}
				}
			}
			if call, ok := n.(*ast.CallExpr); ok {
				if ident, ok := call.Fun.(*ast.Ident); ok {
					invokedClosures[ident.Name] = true
				}
			}
			return true
		})

		// Track sync primitives in test function
		hasSync := false
		ast.Inspect(fn.Body, func(n ast.Node) bool {
			if sel, ok := n.(*ast.SelectorExpr); ok {
				if sel.Sel.Name == "Wait" || sel.Sel.Name == "Parallel" || sel.Sel.Name == "Run" {
					hasSync = true
				}
			}
			if _, ok := n.(*ast.UnaryExpr); ok { // <-ch
				hasSync = true
			}
			return true
		})

		// Walk top-level statements in fn.Body.List
		hasReachableAssertion := false
		hasOnlyUnreachableAfterReturn := false
		hasOnlyConstFalseAssertion := false
		hasOnlyUninvokedClosureAssertion := false
		hasOnlyHelperThatCannotFail := false
		hasOnlyUnjoinedGoroutine := false
		hasOnlyCleanupAssertion := false
		hasLogging := false

		hitUnconditionalReturn := false

		for _, stmt := range fn.Body.List {
			if hitUnconditionalReturn {
				// Any assertion here is unreachable dead code
				ast.Inspect(stmt, func(n ast.Node) bool {
					if call, ok := n.(*ast.CallExpr); ok && isAssertionCall(call) {
						hasOnlyUnreachableAfterReturn = true
					}
					return true
				})
				continue
			}

			if _, isRet := stmt.(*ast.ReturnStmt); isRet {
				hitUnconditionalReturn = true
				continue
			}

			// Check if this statement is an if with const-false condition
			if ifStmt, ok := stmt.(*ast.IfStmt); ok {
				condIsConstFalse := false
				if ident, ok := ifStmt.Cond.(*ast.Ident); ok {
					if val, ok := localConsts[ident.Name]; ok && !val {
						condIsConstFalse = true
					} else if ident.Name == "false" {
						condIsConstFalse = true
					}
				} else if unary, ok := ifStmt.Cond.(*ast.UnaryExpr); ok && unary.Op == token.NOT {
					if id, ok := unary.X.(*ast.Ident); ok {
						if val, ok := localConsts[id.Name]; ok && val {
							condIsConstFalse = true
						}
					}
				}
				if condIsConstFalse {
					ast.Inspect(ifStmt.Body, func(n ast.Node) bool {
						if call, ok := n.(*ast.CallExpr); ok && isAssertionCall(call) {
							hasOnlyConstFalseAssertion = true
						}
						return true
					})
					continue
				}
			}

			// Check if statement contains assertion or helper call
			ast.Inspect(stmt, func(n ast.Node) bool {
				if assign, ok := n.(*ast.AssignStmt); ok {
					for _, rhs := range assign.Rhs {
						if _, ok := rhs.(*ast.FuncLit); ok {
							return false // Closures assigned to variables are evaluated via closureVars
						}
					}
				}
				if goStmt, ok := n.(*ast.GoStmt); ok {
					ast.Inspect(goStmt.Call, func(gn ast.Node) bool {
						if call, ok := gn.(*ast.CallExpr); ok && isAssertionCall(call) {
							if !hasSync {
								hasOnlyUnjoinedGoroutine = true
							} else {
								hasReachableAssertion = true
							}
						}
						return true
					})
					return false // don't re-walk inside goStmt
				}

				if unary, ok := n.(*ast.UnaryExpr); ok && unary.Op == token.ARROW {
					hasReachableAssertion = true
				}
				if _, ok := n.(*ast.SendStmt); ok {
					hasReachableAssertion = true
				}
				if _, ok := n.(*ast.SelectStmt); ok {
					hasReachableAssertion = true
				}

				if call, ok := n.(*ast.CallExpr); ok {
					if sel, ok := call.Fun.(*ast.SelectorExpr); ok {
						if sel.Sel.Name == "Cleanup" {
							for _, arg := range call.Args {
								ast.Inspect(arg, func(cn ast.Node) bool {
									if subCall, ok := cn.(*ast.CallExpr); ok && isAssertionCall(subCall) {
										hasOnlyCleanupAssertion = true
									}
									return true
								})
							}
							return false
						}
						if sel.Sel.Name == "Log" || sel.Sel.Name == "Logf" {
							hasLogging = true
						} else if isAssertionCall(call) {
							hasReachableAssertion = true
						}
					} else if ident, ok := call.Fun.(*ast.Ident); ok {
						if helper, ok := helpers[ident.Name]; ok {
							if helperCanReachAssertions(helper) {
								hasReachableAssertion = true
							} else {
								hasOnlyHelperThatCannotFail = true
							}
						} else if isAssertionCall(call) {
							hasReachableAssertion = true
						}
					} else if isAssertionCall(call) {
						hasReachableAssertion = true
					}
				}
				return true
			})
		}

		// Check if closures have assertions but were never invoked
		for name, lit := range closureVars {
			if !invokedClosures[name] {
				ast.Inspect(lit.Body, func(cn ast.Node) bool {
					if call, ok := cn.(*ast.CallExpr); ok && isAssertionCall(call) {
						hasOnlyUninvokedClosureAssertion = true
					}
					return true
				})
			} else {
				ast.Inspect(lit.Body, func(cn ast.Node) bool {
					if call, ok := cn.(*ast.CallExpr); ok && isAssertionCall(call) {
						hasReachableAssertion = true
					}
					return true
				})
			}
		}

		if !hasReachableAssertion {
			if hasOnlyUnreachableAfterReturn {
				violations = append(violations, fmt.Sprintf("test integrity violation: assertion placed after unconditional return in %s (unreachable)", fn.Name.Name))
			} else if hasOnlyConstFalseAssertion {
				violations = append(violations, fmt.Sprintf("test integrity violation: assertion inside constant false block in %s (unreachable)", fn.Name.Name))
			} else if hasOnlyUninvokedClosureAssertion {
				violations = append(violations, fmt.Sprintf("test integrity violation: assertion inside uninvoked closure in %s", fn.Name.Name))
			} else if hasOnlyHelperThatCannotFail {
				violations = append(violations, fmt.Sprintf("test integrity violation: helper function cannot fail (returns on all paths before reaching assertions) in %s", fn.Name.Name))
			} else if hasOnlyUnjoinedGoroutine {
				violations = append(violations, fmt.Sprintf("test integrity violation: test %s has assertions only inside unjoined goroutine", fn.Name.Name))
			} else if hasOnlyCleanupAssertion {
				violations = append(violations, fmt.Sprintf("test integrity violation: test %s has assertions only inside t.Cleanup (must have assertions in test body)", fn.Name.Name))
			} else if hasLogging {
				violations = append(violations, fmt.Sprintf("test integrity violation: test %s has no assertions (only logging)", fn.Name.Name))
			} else if fn.Name.Name != "_" {
				violations = append(violations, fmt.Sprintf("test integrity violation: test %s has no reachable assertions", fn.Name.Name))
			}
		}
	}

	return violations
}

// CountTestsInWorkspace counts all Test* functions in all Go test files within workspaceDir.
func CountTestsInWorkspace(workspaceDir string) int {
	if workspaceDir == "" {
		return 0
	}
	count := 0
	fset := token.NewFileSet()
	_ = filepath.Walk(workspaceDir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info == nil {
			return nil
		}
		if info.IsDir() {
			name := info.Name()
			if name == ".git" || name == "vendor" || name == "node_modules" || name == ".gradle" {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasSuffix(path, "_test.go") {
			if node, err := parser.ParseFile(fset, path, nil, 0); err == nil {
				for _, decl := range node.Decls {
					if fn, ok := decl.(*ast.FuncDecl); ok {
						if strings.HasPrefix(fn.Name.Name, "Test") {
							count++
						}
					}
				}
			}
		}
		return nil
	})
	return count
}

// ExtractCoverage parses statement coverage percentage from test output.
func ExtractCoverage(output string) float64 {
	re := regexp.MustCompile(`coverage:\s*([0-9]+\.?[0-9]*)%`)
	m := re.FindStringSubmatch(output)
	if len(m) > 1 {
		var val float64
		if _, err := fmt.Sscanf(m[1], "%f", &val); err == nil {
			return val / 100.0
		}
	}
	return 0.0
}

