package api

import (
	"encoding/csv"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"socratix/pkg/benchmark"
	"socratix/pkg/model"
	"socratix/pkg/runner"
)

// handleListBenchmarkCases returns all bundled and custom benchmark cases.
func (s *Server) handleListBenchmarkCases(w http.ResponseWriter, r *http.Request) {
	cases := s.BenchmarkStore.ListCases()
	writeJSON(w, http.StatusOK, cases)
}

// handleCreateBenchmarkCase saves a new user-defined architectural dilemma.
func (s *Server) handleCreateBenchmarkCase(w http.ResponseWriter, r *http.Request) {
	var c benchmark.BenchmarkCase
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json payload: "+err.Error())
		return
	}

	if strings.TrimSpace(c.Title) == "" || strings.TrimSpace(c.Dilemma) == "" {
		writeError(w, http.StatusBadRequest, "title and dilemma are required")
		return
	}

	if c.ID == "" {
		c.ID = fmt.Sprintf("CUSTOM_%d", time.Now().UnixNano()%1000000)
	}

	if err := s.BenchmarkStore.AddCustomCase(c); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to save benchmark case: "+err.Error())
		return
	}

	writeJSON(w, http.StatusCreated, c)
}

// handleRunBenchmark executes a dual-arm evaluation comparing Solo vs Council.
func (s *Server) handleRunBenchmark(w http.ResponseWriter, r *http.Request) {
	var req benchmark.RunBenchmarkRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid json payload: "+err.Error())
		return
	}

	bCase := s.BenchmarkStore.GetCase(req.CaseID)
	if bCase == nil {
		writeError(w, http.StatusNotFound, "benchmark case not found: "+req.CaseID)
		return
	}

	// Default agents if not provided
	soloAgent := model.Agent{
		DisplayName: "Claude 3.7 Sonnet (Solo CoT)",
		Provider:    model.ProviderAnthropic,
		Model:       "claude-3-7-sonnet",
		RunMode:     model.RunModeAPI,
		Role:        "Principal Systems Architect",
	}
	if req.SoloAgent != nil {
		soloAgent = *req.SoloAgent
	}

	councilAgents := []model.Agent{
		{
			DisplayName: "Claude 3.7 Sonnet (Moderator)",
			Provider:    model.ProviderAnthropic,
			Model:       "claude-3-7-sonnet",
			RunMode:     model.RunModeAPI,
			Role:        "Moderator",
		},
		{
			DisplayName: "GPT-4o (Devil's Advocate)",
			Provider:    model.ProviderOpenAI,
			Model:       "gpt-4o",
			RunMode:     model.RunModeAPI,
			Role:        "Devil's Advocate",
		},
		{
			DisplayName: "Gemini 2.5 Pro (Pragmatist)",
			Provider:    model.ProviderGemini,
			Model:       "gemini-2.5-pro",
			RunMode:     model.RunModeAPI,
			Role:        "Pragmatist",
		},
		{
			DisplayName: "DeepSeek R1 (Risk Analyst)",
			Provider:    model.ProviderDeepSeek,
			Model:       "deepseek-reasoner",
			RunMode:     model.RunModeAPI,
			Role:        "Risk Analyst",
		},
	}
	if len(req.CouncilAgents) > 0 {
		councilAgents = req.CouncilAgents
	}

	var judgeAgent model.Agent
	if req.JudgeAgent != nil {
		judgeAgent = *req.JudgeAgent
	} else {
		state, err := s.Store.Load()
		if err != nil {
			state = model.NewAppState()
		}
		keys := state.ApiKeys.AsMap()
		var ok bool
		judgeAgent, ok = benchmark.PickIndependentJudge(append([]model.Agent{soloAgent}, councilAgents...),
			func(p model.Provider) bool { return keys[p] != "" })
		if !ok {
			writeError(w, http.StatusBadRequest, "no provider is independent of the arms; set judgeAgent and allowJudgeOverlap")
			return
		}
	}

	rounds := req.Rounds
	if rounds <= 0 {
		rounds = 2
	}

	if b := req.Baseline; b != "" && b != benchmark.BaselineSolo && b != benchmark.BaselineSelfConsistency {
		writeError(w, http.StatusBadRequest, "baseline must be \"solo\" or \"self_consistency\"")
		return
	}

	benchRunner := benchmark.NewRunner(func(agent model.Agent) runner.AgentRunner {
		return s.runnerForAgent(agent)
	})
	benchRunner.AllowJudgeOverlap = req.AllowJudgeOverlap

	run, err := benchRunner.ExecuteRunWithBaseline(
		r.Context(),
		*bCase,
		req.Baseline,
		soloAgent,
		councilAgents,
		judgeAgent,
		rounds,
		nil,
	)
	if errors.Is(err, benchmark.ErrJudgeNotIndependent) {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	if err != nil {
		writeError(w, http.StatusInternalServerError, "benchmark execution failed: "+err.Error())
		return
	}

	if err := s.BenchmarkStore.SaveRun(*run); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to persist benchmark run: "+err.Error())
		return
	}

	writeJSON(w, http.StatusOK, run)
}

// handleListBenchmarkRuns returns the history of benchmark executions.
func (s *Server) handleListBenchmarkRuns(w http.ResponseWriter, r *http.Request) {
	runs := s.BenchmarkStore.ListRuns()
	writeJSON(w, http.StatusOK, runs)
}

// handleGetBenchmarkRun returns a single historical benchmark run.
func (s *Server) handleGetBenchmarkRun(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if id == "" {
		writeError(w, http.StatusBadRequest, "run id is required")
		return
	}

	run := s.BenchmarkStore.GetRun(id)
	if run == nil {
		writeError(w, http.StatusNotFound, "benchmark run not found")
		return
	}

	writeJSON(w, http.StatusOK, run)
}

// handleGetBenchmarkSummary computes and returns aggregate scientific metrics.
func (s *Server) handleGetBenchmarkSummary(w http.ResponseWriter, r *http.Request) {
	summary := s.BenchmarkStore.GetSummary()
	writeJSON(w, http.StatusOK, summary)
}

// handleExportBenchmarks exports benchmark runs as Markdown, CSV, or JSON.
func (s *Server) handleExportBenchmarks(w http.ResponseWriter, r *http.Request) {
	format := strings.ToLower(r.URL.Query().Get("format"))
	runs := s.BenchmarkStore.ListRuns()
	summary := s.BenchmarkStore.GetSummary()

	if format == "csv" {
		w.Header().Set("Content-Type", "text/csv")
		w.Header().Set("Content-Disposition", "attachment; filename=\"dialex_benchmarks.csv\"")
		writer := csv.NewWriter(w)
		_ = writer.Write([]string{"RunID", "CaseID", "CaseTitle", "SoloScore", "CouncilScore", "DeltaQ", "Winner", "JudgeModel"})
		for _, run := range runs {
			_ = writer.Write([]string{
				run.ID,
				run.CaseID,
				run.CaseTitle,
				strconv.FormatFloat(run.SoloTotalScore, 'f', 2, 64),
				strconv.FormatFloat(run.CouncilTotalScore, 'f', 2, 64),
				strconv.FormatFloat(run.DeltaQ, 'f', 2, 64),
				run.Winner,
				run.JudgeModel,
			})
		}
		writer.Flush()
		return
	}

	if format == "json" {
		writeJSON(w, http.StatusOK, map[string]any{
			"summary": summary,
			"runs":    runs,
		})
		return
	}

	// Default: Markdown Report
	w.Header().Set("Content-Type", "text/markdown; charset=utf-8")
	var sb strings.Builder
	sb.WriteString("# Dialex AI — Null Hypothesis Benchmark & Scientific Evaluation Report\n\n")
	sb.WriteString(fmt.Sprintf("**Total Runs**: %d | **Council Win Rate**: %.1f%% | **Mean Delta Q**: %+.2f\n",
		summary.TotalRuns, summary.CouncilWinRate*100, summary.MeanDeltaQ))
	sb.WriteString(fmt.Sprintf("**Statistical Significance**: p = %.4f (H0 Rejected: %v)\n\n",
		summary.PValue, summary.IsStatSignificant))
	sb.WriteString("## Metric Deltas (Council vs Solo)\n")
	sb.WriteString(fmt.Sprintf("- Factuality Delta: %+.2f\n", summary.AvgFactualityDelta))
	sb.WriteString(fmt.Sprintf("- Blind Spots Delta: %+.2f\n", summary.AvgBlindSpotDelta))
	sb.WriteString(fmt.Sprintf("- Trade-Offs Delta: %+.2f\n", summary.AvgTradeOffDelta))
	sb.WriteString(fmt.Sprintf("- Actionability Delta: %+.2f\n\n", summary.AvgActionDelta))

	sb.WriteString("## Historical Runs\n\n")
	sb.WriteString("| Case ID | Title | Solo Score | Council Score | Delta Q | Winner |\n")
	sb.WriteString("|---|---|---|---|---|---|\n")
	for _, run := range runs {
		sb.WriteString(fmt.Sprintf("| %s | %s | %.1f | %.1f | %+.2f | %s |\n",
			run.CaseID, run.CaseTitle, run.SoloTotalScore, run.CouncilTotalScore, run.DeltaQ, run.Winner))
	}

	_, _ = w.Write([]byte(sb.String()))
}
