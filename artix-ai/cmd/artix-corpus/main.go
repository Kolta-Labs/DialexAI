package main

import (
	"context"
	"encoding/csv"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"artix/pkg/persona"
	"artix/pkg/reviewer"
	"artix/pkg/sandbox"
)

var targetPackages = []string{
	"strings",
	"bytes",
	"sort",
	"strconv",
	"errors",
	"path",
	"bufio",
	"container/heap",
	"container/list",
	"container/ring",
	"encoding/ascii85",
	"encoding/base32",
	"encoding/base64",
	"encoding/binary",
	"encoding/csv",
	"encoding/gob",
	"encoding/hex",
	"encoding/json",
	"encoding/pem",
	"encoding/xml",
	"html",
	"net/url",
	"regexp",
	"sync",
	"time",
	"io",
	"fmt",
	"math/big",
	"hash/fnv",
	"flag",
	"slices",
	"maps",
	"os",
	"path/filepath",
}

func main() {
	outCSV := flag.String("output", "docs/pilot/benign_corpus_results.csv", "Output CSV file path")
	flag.Parse()

	gorootBytes, err := exec.Command("go", "env", "GOROOT").Output()
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to get GOROOT: %v\n", err)
		os.Exit(1)
	}
	goroot := strings.TrimSpace(string(gorootBytes))
	goVerBytes, _ := exec.Command("go", "version").Output()
	goVersion := strings.TrimSpace(string(goVerBytes))

	fmt.Printf("Running benign stdlib corpus evaluation...\n")
	fmt.Printf("GOROOT: %s\n", goroot)
	fmt.Printf("Go Version: %s\n", goVersion)

	srcDir := filepath.Join(goroot, "src")
	var testFiles []string

	for _, pkg := range targetPackages {
		pkgDir := filepath.Join(srcDir, pkg)
		entries, err := os.ReadDir(pkgDir)
		if err != nil {
			continue
		}
		for _, e := range entries {
			if !e.IsDir() && strings.HasSuffix(e.Name(), "_test.go") {
				testFiles = append(testFiles, filepath.Join(pkgDir, e.Name()))
			}
		}
	}

	fmt.Printf("Discovered %d test files across %d stdlib packages.\n", len(testFiles), len(targetPackages))

	rev := reviewer.NewAdversarialReviewer(persona.NewRegistry(""))
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})

	_ = os.MkdirAll(filepath.Dir(*outCSV), 0755)
	f, err := os.Create(*outCSV)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to create CSV %s: %v\n", *outCSV, err)
		os.Exit(1)
	}
	defer f.Close()

	writer := csv.NewWriter(f)
	_ = writer.Write([]string{"file", "verdict", "blocking_reason"})

	total := len(testFiles)
	passed := 0
	rejected := 0

	reasonCounts := make(map[string]int)

	for _, path := range testFiles {
		relPath, _ := filepath.Rel(srcDir, path)
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		lines := strings.Split(string(data), "\n")
		var diffBuilder strings.Builder
		diffBuilder.WriteString(fmt.Sprintf("diff --git a/%s b/%s\n", relPath, relPath))
		diffBuilder.WriteString("new file mode 100644\n")
		diffBuilder.WriteString("--- /dev/null\n")
		diffBuilder.WriteString(fmt.Sprintf("+++ b/%s\n", relPath))
		diffBuilder.WriteString(fmt.Sprintf("@@ -0,0 +1,%d @@\n", len(lines)))
		for _, l := range lines {
			diffBuilder.WriteString("+" + l + "\n")
		}

		tempDir, _ := os.MkdirTemp("", "artix_corpus_eval")
		subPath := filepath.Join(tempDir, relPath)
		_ = os.MkdirAll(filepath.Dir(subPath), 0755)
		_ = os.WriteFile(subPath, data, 0644)

		ctx := &reviewer.ReviewContext{
			Ctx:          context.Background(),
			WorkspaceDir: tempDir,
			Diff:         diffBuilder.String(),
			TestResults: []*sandbox.ExecResult{
				{Command: "go test ./...", ExitCode: 0},
			},
		}

		verdict := rev.Evaluate(ctx)
		_ = os.RemoveAll(tempDir)

		verdictStr := string(verdict.Status)
		blocking := ""
		if len(verdict.BlockingIssues) > 0 {
			blocking = verdict.BlockingIssues[0]
		}

		if verdict.Approved {
			passed++
		} else {
			rejected++
			reasonCounts[blocking]++
		}

		_ = writer.Write([]string{relPath, verdictStr, blocking})
	}

	writer.Flush()

	fpr := 0.0
	if total > 0 {
		fpr = (float64(rejected) / float64(total)) * 100.0
	}

	fmt.Printf("\n=== Benign Stdlib Corpus Evaluation Results ===\n")
	fmt.Printf("Total files evaluated: %d\n", total)
	fmt.Printf("Approved (True Negatives): %d\n", passed)
	fmt.Printf("Rejected (False Positives): %d\n", rejected)
	fmt.Printf("False Positive Rate (FPR): %.2f%%\n", fpr)
	fmt.Printf("Results written to: %s\n", *outCSV)

	if rejected > 0 {
		fmt.Printf("\nTop Rejection Reasons:\n")
		for reason, count := range reasonCounts {
			fmt.Printf("  [%d] %s\n", count, reason)
		}
	}
}
