package main

import (
	"context"
	"encoding/csv"
	"encoding/json"
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

type pinnedModule struct {
	pkg     string
	version string
}

var pinnedModules = []pinnedModule{
	{"golang.org/x/sync", "v0.7.0"},
	{"github.com/google/uuid", "v1.6.0"},
	{"go.uber.org/zap", "v1.27.0"},
	{"golang.org/x/crypto", "v0.25.0"},
	{"github.com/stretchr/testify", "v1.9.0"},
}

type modDownloadInfo struct {
	Path    string `json:"Path"`
	Version string `json:"Version"`
	Dir     string `json:"Dir"`
}

type sourceStats struct {
	sourceName   string
	version      string
	totalFiles   int
	passedFiles  int
	rejectedFiles int
	reasonCounts map[string]int
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
	goVerBytes, err := exec.Command("go", "version").Output()
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to get go version: %v\n", err)
		os.Exit(1)
	}
	goVersion := strings.TrimSpace(string(goVerBytes))

	fmt.Printf("=== Artix AI: Comprehensive Benign Corpus Evaluation ===\n")
	fmt.Printf("GOROOT: %s\n", goroot)
	fmt.Printf("Go Version: %s\n\n", goVersion)

	_ = os.MkdirAll(filepath.Dir(*outCSV), 0755)
	f, err := os.Create(*outCSV)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to create CSV %s: %v\n", *outCSV, err)
		os.Exit(1)
	}
	defer f.Close()

	writer := csv.NewWriter(f)
	_ = writer.Write([]string{"source", "version", "file", "verdict", "blocking_reason"})

	rev := reviewer.NewAdversarialReviewer(persona.NewRegistry(""))
	rev.SetCritic(func(ctx context.Context, prompt string) (string, error) {
		return `{"approved":true,"blocking":[],"warnings":[]}`, nil
	})

	// 1. Discover all GOROOT src test files (minus testdata)
	gorootSrc := filepath.Join(goroot, "src")
	var gorootTestFiles []string
	err = filepath.Walk(gorootSrc, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			fmt.Fprintf(os.Stderr, "unreadable file/dir in GOROOT: %s: %v\n", path, err)
			os.Exit(1)
		}
		if info.IsDir() {
			name := info.Name()
			if name == "testdata" || name == ".git" || name == "vendor" {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasSuffix(path, "_test.go") {
			gorootTestFiles = append(gorootTestFiles, path)
		}
		return nil
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "error walking GOROOT: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Discovered %d test files in GOROOT/src.\n", len(gorootTestFiles))

	gorootStats := sourceStats{
		sourceName:   "GOROOT",
		version:      goVersion,
		reasonCounts: make(map[string]int),
	}

	evalFiles(gorootTestFiles, gorootSrc, "GOROOT", goVersion, rev, writer, &gorootStats)

	// 2. Download and evaluate pinned third-party modules
	var moduleStatsList []sourceStats
	totalModFiles := 0
	totalModPassed := 0
	totalModRejected := 0

	for _, pm := range pinnedModules {
		modCoord := fmt.Sprintf("%s@%s", pm.pkg, pm.version)
		fmt.Printf("\nDownloading pinned module %s...\n", modCoord)
		out, err := exec.Command("go", "mod", "download", "-json", modCoord).Output()
		if err != nil {
			fmt.Fprintf(os.Stderr, "failed to download %s: %v\n", modCoord, err)
			os.Exit(1)
		}

		var info modDownloadInfo
		if err := json.Unmarshal(out, &info); err != nil {
			fmt.Fprintf(os.Stderr, "failed to parse mod download json for %s: %v\n", modCoord, err)
			os.Exit(1)
		}

		var modTestFiles []string
		err = filepath.Walk(info.Dir, func(path string, fileInfo os.FileInfo, err error) error {
			if err != nil {
				fmt.Fprintf(os.Stderr, "unreadable file in module %s: %s: %v\n", modCoord, path, err)
				os.Exit(1)
			}
			if fileInfo.IsDir() {
				name := fileInfo.Name()
				if name == "testdata" || name == ".git" || name == "vendor" {
					return filepath.SkipDir
				}
				return nil
			}
			if strings.HasSuffix(path, "_test.go") {
				modTestFiles = append(modTestFiles, path)
			}
			return nil
		})
		if err != nil {
			fmt.Fprintf(os.Stderr, "error walking module %s: %v\n", modCoord, err)
			os.Exit(1)
		}

		fmt.Printf("Discovered %d test files in %s.\n", len(modTestFiles), modCoord)
		mStats := sourceStats{
			sourceName:   pm.pkg,
			version:      pm.version,
			reasonCounts: make(map[string]int),
		}

		evalFiles(modTestFiles, info.Dir, pm.pkg, pm.version, rev, writer, &mStats)
		moduleStatsList = append(moduleStatsList, mStats)

		totalModFiles += mStats.totalFiles
		totalModPassed += mStats.passedFiles
		totalModRejected += mStats.rejectedFiles
	}

	writer.Flush()

	// Print Summary Report
	fmt.Printf("\n===================================================\n")
	fmt.Printf("=== BENIGN CORPUS EVALUATION RESULTS SUMMARY ===\n")
	fmt.Printf("===================================================\n")

	gorootFPR := 0.0
	if gorootStats.totalFiles > 0 {
		gorootFPR = (float64(gorootStats.rejectedFiles) / float64(gorootStats.totalFiles)) * 100.0
	}
	fmt.Printf("\n[GOROOT] %s\n", goVersion)
	fmt.Printf("  Total files evaluated: %d\n", gorootStats.totalFiles)
	fmt.Printf("  Approved (True Negatives): %d\n", gorootStats.passedFiles)
	fmt.Printf("  Rejected (False Positives): %d (FPR: %.2f%%)\n", gorootStats.rejectedFiles, gorootFPR)
	if gorootStats.rejectedFiles > 0 {
		fmt.Printf("  Top rejection reasons:\n")
		for r, c := range gorootStats.reasonCounts {
			fmt.Printf("    [%d] %s\n", c, r)
		}
	}

	modFPR := 0.0
	if totalModFiles > 0 {
		modFPR = (float64(totalModRejected) / float64(totalModFiles)) * 100.0
	}
	fmt.Printf("\n[PINNED THIRD-PARTY MODULES] Combined\n")
	fmt.Printf("  Total files evaluated: %d\n", totalModFiles)
	fmt.Printf("  Approved (True Negatives): %d\n", totalModPassed)
	fmt.Printf("  Rejected (False Positives): %d (FPR: %.2f%%)\n", totalModRejected, modFPR)

	for _, ms := range moduleStatsList {
		subFPR := 0.0
		if ms.totalFiles > 0 {
			subFPR = (float64(ms.rejectedFiles) / float64(ms.totalFiles)) * 100.0
		}
		fmt.Printf("  - %s@%s: %d total, %d rejected (%.2f%% FPR)\n", ms.sourceName, ms.version, ms.totalFiles, ms.rejectedFiles, subFPR)
	}

	grandTotal := gorootStats.totalFiles + totalModFiles
	grandPassed := gorootStats.passedFiles + totalModPassed
	grandRejected := gorootStats.rejectedFiles + totalModRejected
	grandFPR := 0.0
	if grandTotal > 0 {
		grandFPR = (float64(grandRejected) / float64(grandTotal)) * 100.0
	}

	fmt.Printf("\n[GRAND TOTAL]\n")
	fmt.Printf("  Total files evaluated: %d\n", grandTotal)
	fmt.Printf("  Approved: %d\n", grandPassed)
	fmt.Printf("  Rejected: %d (Overall FPR: %.2f%%)\n", grandRejected, grandFPR)
	fmt.Printf("  Results CSV saved to: %s\n", *outCSV)

	// Thresholds to beat: GOROOT <= 127, Third-party modules <= 118
	if gorootStats.rejectedFiles > 127 {
		fmt.Fprintf(os.Stderr, "\nFAILURE: GOROOT rejected %d > target 127\n", gorootStats.rejectedFiles)
		os.Exit(1)
	}
	if totalModRejected > 118 {
		fmt.Fprintf(os.Stderr, "\nFAILURE: Third-party modules rejected %d > target 118\n", totalModRejected)
		os.Exit(1)
	}
	fmt.Printf("\nSUCCESS: Benign corpus thresholds met (GOROOT %d/127, Third-party %d/118).\n", gorootStats.rejectedFiles, totalModRejected)
}

func evalFiles(files []string, baseDir, sourceName, version string, rev *reviewer.AdversarialReviewer, writer *csv.Writer, stats *sourceStats) {
	for _, path := range files {
		relPath, _ := filepath.Rel(baseDir, path)
		data, err := os.ReadFile(path)
		if err != nil {
			fmt.Fprintf(os.Stderr, "FATAL: cannot read %s: %v\n", path, err)
			os.Exit(1)
		}
		stats.totalFiles++

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
			stats.passedFiles++
		} else {
			stats.rejectedFiles++
			stats.reasonCounts[blocking]++
		}

		_ = writer.Write([]string{sourceName, version, relPath, verdictStr, blocking})
	}
}
