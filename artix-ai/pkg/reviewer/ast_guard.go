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

			if strings.Contains(trimmed, "t.Skip(") || strings.Contains(trimmed, "t.SkipNow()") || strings.Contains(trimmed, "t.Skip") || strings.Contains(trimmed, "t.Skipf") {
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

			// Detect impossible/weakened assertion (len(...) < 0 or len(...) <= -1 or bitshifts len(...) > 1<<62)
			if (strings.Contains(trimmed, "len(") || strings.Contains(trimmed, "size()")) && (strings.Contains(trimmed, "< 0") || strings.Contains(trimmed, "<= -1") || strings.Contains(trimmed, "== -1") || strings.Contains(trimmed, "1<<") || strings.Contains(trimmed, "1 <<") || strings.Contains(trimmed, "1>>") || strings.Contains(trimmed, "1 >>")) {
				violations = append(violations, fmt.Sprintf("test integrity violation: impossible/weakened assertion detected (%s)", trimmed))
			}

			// Detect tautology / dead test condition (e.g. 1 != 1, 1 == 1, if false, if !ok, a != a)
			if strings.Contains(trimmed, "1 != 1") || strings.Contains(trimmed, "1 == 1") || strings.Contains(trimmed, "if false") || strings.Contains(trimmed, "assert.True(t, true)") || strings.Contains(trimmed, "assert.False(t, false)") || strings.Contains(trimmed, "if !ok") || strings.Contains(trimmed, "if !valid") || strings.Contains(trimmed, "if !pass") {
				violations = append(violations, fmt.Sprintf("test integrity violation: tautological assertion / dead test condition detected (%s)", trimmed))
			}

			// Detect self-comparison e.g. "a != a" or "x != x" or "a == a" or "if a != a { t.Fatal(1) }"
			for _, op := range []string{"!=", "==", "<=", ">=", "<", ">"} {
				if strings.Contains(trimmed, op) {
					parts := strings.Split(trimmed, op)
					if len(parts) >= 2 {
						lhsTokens := strings.Fields(parts[0])
						rhsTokens := strings.Fields(parts[1])
						if len(lhsTokens) > 0 && len(rhsTokens) > 0 {
							lhs := strings.Trim(lhsTokens[len(lhsTokens)-1], "(){},;[]")
							rhs := strings.Trim(rhsTokens[0], "(){},;[]")
							if lhs != "" && lhs == rhs {
								violations = append(violations, fmt.Sprintf("test integrity violation: self-comparison tautology detected (%s %s %s)", lhs, op, rhs))
								break
							}
						}
					}
				}
			}

			// Detect assertion inside loop over empty slice/collection e.g. for range []string{} { t.Fatal(...) }
			if (strings.Contains(trimmed, "range []") || strings.Contains(trimmed, "range []string{}") || strings.Contains(trimmed, "range []int{}") || strings.Contains(trimmed, "for i := 0; i < 0;")) {
				violations = append(violations, fmt.Sprintf("test integrity violation: assertion inside empty loop detected (%s)", trimmed))
			}

			// Detect defer recover() panic swallowing in tests
			if strings.Contains(trimmed, "recover()") && (strings.Contains(trimmed, "defer") || strings.Contains(trimmed, "func()")) {
				violations = append(violations, fmt.Sprintf("test integrity violation: defer recover() panic swallowing detected in test (%s)", trimmed))
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
	if sel, ok := call.Fun.(*ast.SelectorExpr); ok {
		name := strings.ToLower(sel.Sel.Name)
		if name == "fatal" || name == "fatalf" || name == "error" || name == "errorf" || name == "fail" || name == "failnow" ||
			strings.HasPrefix(name, "assert") || strings.HasPrefix(name, "require") {
			return true
		}
	}
	return false
}


// CheckSemanticASTTaboos checks diff against taboo patterns using AST parsing for Go and comment-aware filtering.
// Note: The deterministic rule and taboo checks serve as defense-in-depth pre-filters and are not claimed to be exhaustive.
// Autonomous approval strictly requires passing both the deterministic layer and the model-backed Critic evaluation.
// If workspaceDir is provided, whole post-patch files on disk are parsed to catch multi-statement and cross-declaration patterns.
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
				if node, err := parser.ParseFile(fset, fullPath, nil, parser.AllErrors); err == nil {
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
	checkShellExec := strings.Contains(taboosJoined, "exec.command") || strings.Contains(taboosJoined, "shell") || strings.Contains(taboosJoined, "raw shell") || true // Inherent taboo
	checkReflection := strings.Contains(taboosJoined, "reflection") || strings.Contains(taboosJoined, "reflect")

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

		// Build local function call graph and sink tracking
		funcCalls := make(map[string][]string)
		funcHasNet := make(map[string]bool)
		funcHasSecret := make(map[string]bool)
		funcHasSink := make(map[string]bool)

		for _, decl := range node.Decls {
			if fn, ok := decl.(*ast.FuncDecl); ok && fn.Body != nil {
				fnName := fn.Name.Name
				ast.Inspect(fn.Body, func(in ast.Node) bool {
					// 1. Binary expression self-comparison check
					if bin, ok := in.(*ast.BinaryExpr); ok {
						if bin.Op == token.NEQ || bin.Op == token.EQL || bin.Op == token.LSS || bin.Op == token.GTR || bin.Op == token.LEQ || bin.Op == token.GEQ {
							xStr := formatExpr(bin.X)
							yStr := formatExpr(bin.Y)
							if xStr != "" && xStr == yStr {
								violations = append(violations, fmt.Sprintf("test integrity violation: self-comparison tautology detected (%s %s %s)", xStr, bin.Op.String(), yStr))
							}
						}
					}

					// 2. Empty range loop with assertions
					if rStmt, ok := in.(*ast.RangeStmt); ok {
						isEmpty := false
						if cl, ok := rStmt.X.(*ast.CompositeLit); ok && len(cl.Elts) == 0 {
							isEmpty = true
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
						if ident, ok := call.Fun.(*ast.Ident); ok {
							funcCalls[fnName] = append(funcCalls[fnName], ident.Name)
							// If calling helper with secret or any helper in general
							for _, arg := range call.Args {
								argS := fmt.Sprintf("%v", arg)
								if strings.Contains(argS, "Getenv") || strings.Contains(argS, "SECRET") || strings.Contains(argS, "TOKEN") || strings.Contains(argS, "KEY") {
									funcHasSecret[fnName] = true
									funcHasSink[fnName] = true
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
							}

							// Process execution sinks
							if sName == "Command" || sName == "CommandContext" || sName == "Exec" {
								funcHasNet[fnName] = true
								funcHasSink[fnName] = true
							}

							// File write sinks
							if sName == "WriteFile" || sName == "Create" || sName == "OpenFile" || sName == "WriteString" {
								funcHasSink[fnName] = true
							}

							// Output / stdout / log sinks
							if pkgName == "fmt" && (sName == "Println" || sName == "Printf" || sName == "Print" || sName == "Fprintf" || sName == "Sprintf" || sName == "Errorf") {
								funcHasSink[fnName] = true
							}
							if pkgName == "log" && (sName == "Println" || sName == "Printf" || sName == "Print" || sName == "Fatal" || sName == "Fatalf") {
								funcHasSink[fnName] = true
							}
							if pkgName == "errors" && sName == "New" {
								funcHasSink[fnName] = true
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

							// Secret reads
							if sName == "Getenv" || sName == "LookupEnv" {
								funcHasSecret[fnName] = true
								for _, arg := range call.Args {
									if lit, ok := arg.(*ast.BasicLit); ok {
										v := strings.ToUpper(lit.Value)
										if strings.Contains(v, "SECRET") || strings.Contains(v, "KEY") || strings.Contains(v, "TOKEN") || strings.Contains(v, "AWS") || strings.Contains(v, "GITHUB") || strings.Contains(v, "PASS") || strings.Contains(v, "CRED") || strings.Contains(v, "AUTH") || strings.Contains(v, "API") {
											funcHasSecret[fnName] = true
										}
									}
								}
							}
							if sName == "Environ" {
								funcHasSecret[fnName] = true
							}
							if sName == "ReadFile" || sName == "Open" {
								for _, arg := range call.Args {
									argStr := fmt.Sprintf("%v", arg)
									argLower := strings.ToLower(argStr)
									if strings.Contains(argLower, "credential") || strings.Contains(argLower, ".aws") || strings.Contains(argLower, ".ssh") || strings.Contains(argLower, "token") || strings.Contains(argLower, "secret") || strings.Contains(argLower, "/etc/passwd") || strings.Contains(argLower, "/etc/shadow") {
										funcHasSecret[fnName] = true
										violations = append(violations, fmt.Sprintf("AST Taboo Violation: forbidden direct read of credential file %s in non-test code", argStr))
									}
								}
							}
						}
					}
					return true
				})
			}
		}

		// Check transitive secret exfiltration / sink reachability from any non-test function
		for fnName := range funcCalls {
			secretReachable := transitivelyHasProp(fnName, funcCalls, funcHasSecret, make(map[string]bool))
			sinkReachable := transitivelyHasProp(fnName, funcCalls, funcHasSink, make(map[string]bool))
			netReachable := transitivelyHasProp(fnName, funcCalls, funcHasNet, make(map[string]bool))
			if secretReachable && (sinkReachable || netReachable) {
				violations = append(violations, "AST Taboo Violation: forbidden secret exfiltration / secret read reaching sink in non-test code")
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
								// Check if any subsequent argument reads env or secrets
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

			// 4. Secret access and network/DNS egress in closures or functions
			if call, ok := n.(*ast.CallExpr); ok {
				if sel, ok := call.Fun.(*ast.SelectorExpr); ok {
					name := sel.Sel.Name
					if name == "Post" || name == "Get" || name == "Do" || name == "Dial" || name == "NewRequest" ||
						name == "LookupHost" || name == "LookupIP" || name == "LookupTXT" || name == "LookupCNAME" {
						// Inspect call arguments for direct secret / env reads
						for _, arg := range call.Args {
							argStr := fmt.Sprintf("%v", arg)
							if strings.Contains(argStr, "Environ") || strings.Contains(argStr, "Getenv") || strings.Contains(argStr, "ReadFile") || strings.Contains(argStr, "SECRET") || strings.Contains(argStr, "TOKEN") {
								violations = append(violations, "AST Taboo Violation: forbidden secret exfiltration / network egress in non-test code")
							}
						}
					}
				}
			}

			return true
		})
	}

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
	if strings.Contains(codeLower, "os.getenv") && (strings.Contains(codeLower, "fmt.printf") || strings.Contains(codeLower, "fmt.println") || strings.Contains(codeLower, "fmt.print") || strings.Contains(codeLower, "fmt.errorf") || strings.Contains(codeLower, "os.writefile") || strings.Contains(codeLower, "sink(")) {
		violations = append(violations, "AST Taboo Violation: forbidden secret read reaching sink in non-test code")
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
					} else if ifStmt, ok := firstStmt.(*ast.IfStmt); ok {
						for _, stmt := range ifStmt.Body.List {
							if _, isRet := stmt.(*ast.ReturnStmt); isRet {
								violations = append(violations, fmt.Sprintf("test integrity violation: conditional early return at top of test function %s in %s", fn.Name.Name, f))
								break
							}
						}
					}
				}

				// Check any if true { return } or if os.Getenv(...) == "" { return } in test body
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
									if sel, ok := call.Fun.(*ast.SelectorExpr); ok {
										if sel.Sel.Name == "Getenv" {
											violations = append(violations, fmt.Sprintf("test integrity violation: if os.Getenv(...) early return evasion in %s in %s", fn.Name.Name, f))
										}
									}
								}
								return true
							})
						}
					}
					return true
				})

				// 2b. Empty or no-op t.Run body & track calls
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
						// Track calls
						if ident, ok := call.Fun.(*ast.Ident); ok {
							calledFuncs[ident.Name] = true
						}
					}
					return true
				})
			} else if !strings.HasPrefix(fn.Name.Name, "Benchmark") && !strings.HasPrefix(fn.Name.Name, "Example") {
				helperFuncs = append(helperFuncs, fn)
			}
		}

		// 2c. Check helpers with assertions that are never called
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
			if hasAssertion && !calledFuncs[helper.Name.Name] {
				violations = append(violations, fmt.Sprintf("test integrity violation: assertions hidden in uncalled helper function %s in %s", helper.Name.Name, f))
			}
		}
	}

	return deduplicateStrings(violations)
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

