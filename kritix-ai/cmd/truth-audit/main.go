package main

import (
	"fmt"
	"os"

	"kritix/pkg/security/truthaudit"
)

func main() {
	cwd, err := os.Getwd()
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to get current working directory: %v\n", err)
		os.Exit(1)
	}

	auditor := truthaudit.NewAuditor(cwd)
	res, err := auditor.RunAll()
	if err != nil {
		fmt.Fprintf(os.Stderr, "truth audit execution failed: %v\n", err)
		os.Exit(1)
	}

	if !res.Passed {
		fmt.Printf("⛔ [TRUTH AUDIT FAILED] %d violation(s) detected:\n\n", len(res.Violations))
		for i, v := range res.Violations {
			lineStr := ""
			if v.Line > 0 {
				lineStr = fmt.Sprintf(":%d", v.Line)
			}
			fmt.Printf("  [%d] Gate: %-22s File: %s%s\n      Description: %s\n", i+1, v.Gate, v.File, lineStr, v.Description)
		}
		os.Exit(1)
	}

	fmt.Println("✅ [TRUTH AUDIT PASSED] Structural gates clean (provenance, label honesty, event integrity, self-cert, AST secrets, stats gate, paths).")
	os.Exit(0)
}
