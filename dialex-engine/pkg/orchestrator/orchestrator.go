// Package orchestrator is the Go port of Kotlin's DebateOrchestrator — the round-robin turn
// loop, transcript compaction, retry-on-failure, and token-budget enforcement.
package orchestrator

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"dialex/pkg/consensus"
	"dialex/pkg/graph"
	"dialex/pkg/model"
	"dialex/pkg/retrieval"
	"dialex/pkg/runner"
)

var consensusPrefixRegex = regexp.MustCompile(`(?mi)^(?:>\s*)*(?:#{1,6}\s*)?(?:\[\s*)?(?:\*{1,2}|_{1,2})?\s*(?:AGREED|CONCUR|CONSENSUS REACHED|UNANIMOUS AGREEMENT|I AGREE)\b\s*(?:\])?\s*[:—\-]?(?:\*{1,2}|_{1,2})?`)

const (
	consensusDirective = "[DELIBERATION CONVERGENCE RULES]\n" +
		"1. If the arguments presented by other participants have resolved the core contentions and you have no substantially new empirical data, theoretical models, or counter-arguments to introduce:\n" +
		"   - Begin your reply immediately with the single token: AGREED:\n" +
		"   - Provide a concise 2-4 sentence summary explaining why you concur.\n" +
		"2. DO NOT artificially prolong the debate if consensus is reached.\n" +
		"3. DO NOT concede simply to end the debate if you still have an unaddressed factual, ethical, or logical objection."

	defaultHumanDialogueDirective = "Dialogue mode: 2–4 natural sentences per turn. No pleasantries, no markdown headers/bullets, no recaps. Address others directly by name (@Agent). Disagree or challenge directly."
	defaultTopicDriftDirective    = "Stay strictly anchored to the core question and topic. Do not digress into peripheral tangents, speculative rabbit holes, or pedantic semantic debates. If another participant veers off-topic, explicitly steer the discussion back to the core decision."

	agreedPrefix = "agreed"

	// Every turn resends the transcript from scratch, so a long debate gets expensive fast —
	// once it crosses this many messages, everything but the most recent turns gets folded
	// into one summary instead of resent verbatim. Request-shaping only: the persisted
	// transcript, caller, and resume math all still see the real, full history — only what's
	// sent *to the model* is compacted.
	compactThreshold  = 20
	compactKeepRecent = 8
	compactDirective  = "Summarize this debate so far concisely but thoroughly: preserve every participant's key " +
		"positions, strongest arguments, and how the discussion evolved. This summary will " +
		"replace the verbatim transcript for future turns, so don't lose anything important — " +
		"but be much shorter than the original."

	// Transient failures (a dropped connection, a momentary 5xx, a CLI hiccup) shouldn't end
	// a whole debate — one retry after a short delay turns "the debate died" into "one turn
	// took a few seconds longer" for the common case.
	retryDelay = 1500 * time.Millisecond

	// Primary's very first turn lays out the topic/context/info and hands off to the
	// others — everyone else just responds to what's in the transcript so far.
	openingDirective = "Lay out the key points on the topic using the context and " +
		"information given, then explicitly ask the other participant(s) what they think."
)

// RunnerFor builds the right AgentRunner (API or CLI) for one agent.
type RunnerFor func(agent model.Agent) runner.AgentRunner

// Orchestrator drives the round-robin turn loop, independent of any transport or storage.
type Orchestrator struct {
	RunnerFor RunnerFor
}

// RunOptions configures one Run call. Only Config is required — everything else has a
// sensible default matching the Kotlin side's default parameter values.
type RunOptions struct {
	Config              model.DebateConfig
	InitialTranscript   []model.DebateMessage
	IsStopped           func() bool
	GetInjected         func() []model.DebateMessage
	CompactionModel     string
	CompactionThreshold int
	TokenBudget         int
	OnMessage           func(model.DebateMessage)
	AttachedFolders     []model.FolderScope
	Permissions         *model.PermissionConfig
	InitialTensions     []model.TensionPair
	OnTensionsUpdated   func([]model.TensionPair)
	GraphStore          graph.GraphStore
	ProjectID           string
	InitialEvidence     []model.RoundEvidence
	OnEvidenceRetrieved func([]model.RoundEvidence)
}

// Run executes the round-robin turn loop: config.Primary always speaks first each round
// (config.Agents() is ordered primary, secondary?, tertiary?, ...), then whichever others
// are in the debate, each seeing the full transcript so far. IsStopped is checked before
// every turn — the caller can flip it (Pause) and the debate halts right there, resumable
// later by calling Run again with the same InitialTranscript the result returned.
//
// A round ends the debate early — before FIXED's MaxRounds, or at any point in UNLIMITED —
// when every agent's turn that round opened with "AGREED:".
//
// The returned error is non-nil ONLY for a hard stop (ctx cancelled mid-turn) — everything
// else (a turn failing, pausing, a token-budget stop) is reported inside the DebateResult,
// never as a Go error. That mirrors the Kotlin side's CancellationException-vs-DebateResult
// split: cancellation must propagate so the caller's own context machinery unwinds
// correctly; a turn failure is just data.
func (o *Orchestrator) Run(ctx context.Context, opts RunOptions) (model.DebateResult, error) {
	config := opts.Config
	isStopped := opts.IsStopped
	if isStopped == nil {
		isStopped = func() bool { return false }
	}
	getInjected := opts.GetInjected
	if getInjected == nil {
		getInjected = func() []model.DebateMessage { return nil }
	}
	onMessage := opts.OnMessage
	if onMessage == nil {
		onMessage = func(model.DebateMessage) {}
	}
	compactionModel := opts.CompactionModel
	if compactionModel == "" {
		compactionModel = model.DefaultCompactionModel
	}
	tokenBudget := opts.TokenBudget

	currentTensions := append([]model.TensionPair{}, opts.InitialTensions...)
	onTensionsUpdated := opts.OnTensionsUpdated
	tensionDetector := consensus.NewTensionDetector()

	currentEvidence := append([]model.RoundEvidence{}, opts.InitialEvidence...)
	onEvidenceRetrieved := opts.OnEvidenceRetrieved
	retriever := retrieval.NewDynamicRetriever(opts.GraphStore)

	transcript := append([]model.DebateMessage{}, opts.InitialTranscript...)

	effectiveTopic := formatWithAttachments(config.Topic, config.AttachedFiles, "topic")
	effectiveContext := formatWithAttachments(config.CommonContext, config.AttachedFiles, "common_context")
	effectiveInfo := formatWithAttachments(config.CommonInfo, config.AttachedFiles, "common_info")

	if len(opts.AttachedFolders) > 0 {
		var wb strings.Builder
		wb.WriteString("\n\n[ATTACHED WORKSPACES & CODEBASE COVERAGE]\n")
		for i, f := range opts.AttachedFolders {
			trustLabel := "Restricted"
			if f.IsTrusted {
				trustLabel = "Trusted"
			}
			wb.WriteString(fmt.Sprintf("- Workspace %d (%s): %s\n", i+1, trustLabel, f.Path))
		}
		wb.WriteString("- You have full coverage of these workspaces. When analyzing code, architecture, or configurations, anchor your arguments to these directories.\n")
		effectiveContext += wb.String()
	}

	var instructions strings.Builder
	instructions.WriteString(consensusDirective)
	if config.HumanDialogueMode {
		dir := config.HumanDialogueDirective
		if dir == "" {
			dir = defaultHumanDialogueDirective
		}
		fmt.Fprintf(&instructions, "\n\n%s", dir)
	}
	if config.Moderation != nil && config.Moderation.DetectTopicDrift {
		dir := config.Moderation.TopicDriftDirective
		if dir == "" {
			dir = defaultTopicDriftDirective
		}
		fmt.Fprintf(&instructions, "\n\nTOPIC FOCUS & ANTI-RABBIT-HOLE GUARDRAIL:\n%s", dir)
	}
	if effectiveInfo != "" {
		fmt.Fprintf(&instructions, "\nAdditional information: %s", effectiveInfo)
	}
	if len(config.AttachedFiles) > 0 {
		instructions.WriteString("\n\nDOCUMENT GROUNDING MANDATE: Authoritative reference documents have been attached to this deliberation. In your arguments, cite specific figures, sections, and clauses from the provided documents. Directly challenge opposing claims that contradict the provided source materials.")
	}
	if config.MasterInstructions != "" {
		fmt.Fprintf(&instructions, "\n%s", config.MasterInstructions)
	}

	// Compacted once per Run call (not per turn) — cheap to re-slice on every turn after
	// Shared Memory & Compaction state
	sharedMem := config.SharedMemory
	useSharedMem := sharedMem != nil && sharedMem.Enabled
	var sharedMemoryBlackboard string

	updateSharedMemory := func() error {
		if !useSharedMem || len(transcript) < 2 {
			return nil
		}
		summaryModel := sharedMem.SummaryModel
		if summaryModel == "" {
			summaryModel = compactionModel
		}
		maxWords := sharedMem.MaxSummaryTokens
		if maxWords <= 0 {
			maxWords = 600
		}
		summaryAgent := config.Primary
		summaryAgent.SystemPrompt = fmt.Sprintf(
			"You are the debate knowledge synthesizer. Maintain and update the Shared Memory Blackboard of this debate.\n"+
				"Structure your output in clear, ultra-concise bullet points:\n"+
				"- [ESTABLISHED FACTS & PREMISES]: core verified facts and background constraints.\n"+
				"- [AREAS OF CONSENSUS]: points all participants have agreed on.\n"+
				"- [OPEN CONTENTIONS]: active disagreements and competing trade-offs.\n"+
				"Target under %d words total. Eliminate filler words while preserving exact numbers, technical names, and arguments.",
			maxWords,
		)
		summary, err := respondWithRetry(ctx, o.RunnerFor(summaryAgent), summaryAgent, effectiveTopic, effectiveContext, "", transcript, summaryModel)
		if err != nil {
			if isCancellation(err) {
				return err
			}
			// Non-fatal optimization failure
		} else {
			sharedMemoryBlackboard = summary.Content
		}
		return nil
	}

	// Compacted turns state
	var compactedSummary string
	compactedCount := 0
	threshold := opts.CompactionThreshold
	if threshold <= 0 {
		threshold = compactThreshold
	}

	maybeCompact := func() error {
		if useSharedMem {
			return nil
		}
		if len(transcript)-compactedCount > threshold {
			cutoff := len(transcript) - compactKeepRecent
			if cutoff <= compactedCount {
				return nil
			}
			older := transcript[:cutoff]
			compactionAgent := config.Primary
			compactionAgent.SystemPrompt = compactDirective
			summary, err := respondWithRetry(ctx, o.RunnerFor(compactionAgent), compactionAgent, effectiveTopic, effectiveContext, "", older, compactionModel)
			if err != nil {
				if isCancellation(err) {
					return err
				}
				// Compaction is an optimization, not a fatal failure
			} else {
				compactedSummary = summary.Content
				compactedCount = cutoff
			}
		}
		return nil
	}

	// Initial check before loop
	if err := maybeCompact(); err != nil && isCancellation(err) {
		return model.DebateResult{Transcript: transcript}, err
	}
	if err := updateSharedMemory(); err != nil && isCancellation(err) {
		return model.DebateResult{Transcript: transcript}, err
	}

	contextView := func(forAgent model.Agent) []model.DebateMessage {
		var rawViews []model.DebateMessage
		if useSharedMem && sharedMemoryBlackboard != "" {
			var views []model.DebateMessage
			// 1. Shared Memory Blackboard
			views = append(views, model.DebateMessage{
				SeatID:            config.Primary.ID,
				Provider:          config.Primary.Provider,
				AuthorDisplayName: config.Primary.Label(),
				AgentID:           config.Primary.Provider,
				Round:             0,
				Content:           fmt.Sprintf("[SHARED MEMORY BLACKBOARD]\n\n%s", sharedMemoryBlackboard),
			})

			// 2. Round 1 anchor if configured
			if sharedMem.IncludeFullRound1 {
				for _, m := range transcript {
					if m.Round == 1 && !m.IsError {
						views = append(views, m)
					}
				}
			}

			// 3. Agent's own last turn if configured
			if sharedMem.IncludeOwnLastTurn {
				var ownLast *model.DebateMessage
				for i := len(transcript) - 1; i >= 0; i-- {
					m := transcript[i]
					if ownedBySeat(m, forAgent) && !m.IsError && (!sharedMem.IncludeFullRound1 || m.Round > 1) {
						ownLast = &m
						break
					}
				}
				if ownLast != nil {
					views = append(views, *ownLast)
				}
			}

			// 4. Most recent turns (immediate context from current exchange)
			recentCount := 2
			if len(transcript) > 0 {
				start := len(transcript) - recentCount
				if start < 0 {
					start = 0
				}
				for _, m := range transcript[start:] {
					alreadyIncluded := false
					for _, v := range views {
						if v.AgentID == m.AgentID && v.SeatID == m.SeatID && v.Round == m.Round && v.Content == m.Content {
							alreadyIncluded = true
							break
						}
					}
					if !alreadyIncluded {
						views = append(views, m)
					}
				}
			}
			rawViews = views
		} else if compactedSummary == "" {
			rawViews = transcript
		} else {
			recap := model.DebateMessage{
				SeatID:            config.Primary.ID,
				Provider:          config.Primary.Provider,
				AuthorDisplayName: config.Primary.Label(),
				AgentID:           config.Primary.Provider,
				Round:             0,
				Content:           fmt.Sprintf("[Summary of earlier discussion]\n\n%s", compactedSummary),
			}
			rawViews = append([]model.DebateMessage{recap}, transcript[compactedCount:]...)
		}
		return model.EnsurePayloadWithinCeiling(forAgent.Provider, rawViews)
	}

	// speak returns (turnErrorMessage, hardStopErr). Exactly one is non-nil-ish on failure;
	// both are zero on success (and the reply is appended to transcript + reported via
	// onMessage before returning).
	speak := func(agent model.Agent, round int) (string, error) {
		isOpeningTurn := len(transcript) == 0 && agent.Provider == config.Primary.Provider
		effective := agent
		
		var modifierParts []string
		if effective.SystemPrompt != "" {
			modifierParts = append(modifierParts, effective.SystemPrompt)
		}

		// Evaluate WebSearch permission for this agent seat
		webSearchAllowed := true
		if opts.Permissions != nil {
			webSearchAllowed = opts.Permissions.IsWebSearchAllowedFor(string(effective.ID))
		}
		if effective.AllowWebSearch != nil {
			webSearchAllowed = *effective.AllowWebSearch
		}
		if webSearchAllowed {
			modifierParts = append(modifierParts, "[LIVE WEB SEARCH & GROUNDING: ENABLED]\n- You have permission to search the web for live documentation, factual checks, external library versions, and domain checks.\n- When citing external facts or domain checks, explicitly cite your source or domain.")
		} else {
			modifierParts = append(modifierParts, "[LIVE WEB SEARCH: DISABLED]\n- Web search is disabled for your seat in this deliberation.\n- Rely strictly on the attached workspace files and your existing parametric knowledge.")
		}

		if len(modifierParts) > 0 {
			effective.SystemPrompt = strings.Join(modifierParts, "\n")
		}

		if isOpeningTurn {
			segments := []string{}
			if effective.SystemPrompt != "" {
				segments = append(segments, effective.SystemPrompt)
			}
			segments = append(segments, openingDirective)
			effective.SystemPrompt = strings.Join(segments, "\n")
		}
		effective.Context = formatWithAttachments(effective.Context, config.AttachedFiles, fmt.Sprintf("agent_%s", effective.Provider))
		turnContext := effectiveContext
		for _, re := range currentEvidence {
			if re.Round == round && re.SummaryContext != "" {
				turnContext += "\n\n" + re.SummaryContext
				break
			}
		}
		reply, err := respondWithRetry(ctx, o.RunnerFor(effective), effective, effectiveTopic, turnContext, instructions.String(), contextView(effective), "")
		if err != nil {
			if isCancellation(err) {
				return "", err
			}
			message := err.Error()
			msg := model.DebateMessage{
				SeatID:            agent.ID,
				Provider:          agent.Provider,
				AuthorDisplayName: agent.Label(),
				AgentID:           agent.Provider,
				Round:             round,
				Content:           "Turn dropped due to network/provider error: " + message,
				IsError:           true,
				TimestampMs:       time.Now().UnixMilli(),
			}
			transcript = append(transcript, msg)
			onMessage(msg)
			return message, nil
		}
		msg := model.DebateMessage{
			SeatID:            agent.ID,
			Provider:          agent.Provider,
			AuthorDisplayName: agent.Label(),
			AgentID:           agent.Provider,
			Round:             round,
			Content:           reply.Content,
			TokensIn:          reply.TokensIn,
			TokensOut:         reply.TokensOut,
			TokensCached:      reply.TokensCached,
			TimestampMs:       time.Now().UnixMilli(),
		}
		transcript = append(transcript, msg)
		onMessage(msg)
		return "", nil
	}

	handleTurnFailureGracefully := func(failedAgent model.Agent, round int, errorMessage string) (model.DebateResult, error) {
		round1Turns := 0
		for _, m := range transcript {
			if m.Round == 1 && !m.IsError && !m.IsSystem && !m.IsUserComment {
				round1Turns++
			}
		}
		if round <= 1 || round1Turns < len(config.Agents()) {
			return model.DebateResult{Transcript: transcript, Error: &errorMessage, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, nil
		}

		emergencyWrapUpPrompt := fmt.Sprintf("You are the Deliberation Moderator. A late participant turn was interrupted by a connection error.\n"+
			"Synthesize a definitive final conclusion and outcome answering the core topic based on the extensive arguments already established by the council:\n\n"+
			"### 🎯 Final Outcome & Recommendation\n"+
			"State the definitive decision answering the topic in 1-2 clear sentences.\n\n"+
			"### 💡 Core Established Consensus\n"+
			"- 2-3 bullet points on the strongest points agreed across the completed rounds.\n\n"+
			"### ⚖️ Remaining Open Trade-offs\n"+
			"- Key remaining points of contention.\n\n"+
			"Note: Note that the discussion concluded gracefully based on %d completed deliberation rounds.", round-1)

		moderatorAgent := config.Primary
		if config.Moderation != nil && config.Moderation.ModeratorProvider != "" {
			for _, a := range config.Agents() {
				if a.Provider == config.Moderation.ModeratorProvider {
					moderatorAgent = a
					break
				}
			}
		}

		modCopy := moderatorAgent
		modCopy.SystemPrompt = emergencyWrapUpPrompt
		conclusionReply, err := respondWithRetry(ctx, o.RunnerFor(modCopy), modCopy, effectiveTopic, effectiveContext, "", contextView(modCopy), "")
		if err != nil {
			if isCancellation(err) {
				return model.DebateResult{Transcript: transcript, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, err
			}
			return model.DebateResult{Transcript: transcript, Error: &errorMessage, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, nil
		}
		warning := fmt.Sprintf("Discussion concluded with emergency synthesis due to provider timeout in Round %d: %s", round, errorMessage)
		return model.DebateResult{
			Transcript:        transcript,
			Conclusion:        &conclusionReply.Content,
			Warning:           &warning,
			TensionPairs:      currentTensions,
			RetrievedEvidence: currentEvidence,
		}, nil
	}

	isTurnInConsensus := func(content string) bool {
		trimmed := strings.TrimSpace(content)
		if trimmed == "" {
			return false
		}
		if consensusPrefixRegex.MatchString(trimmed) {
			return true
		}
		lower := strings.ToLower(trimmed)
		concessionPhrases := []string{
			"nothing left to contest",
			"nothing left to audit",
			"i concede to",
			"concede to the position",
			"no further substantive disagreement",
			"no further disagreement",
			"fully concur with",
			"i fully concur",
			"i align with the council",
		}
		for _, phrase := range concessionPhrases {
			if strings.Contains(lower, phrase) {
				return true
			}
		}
		return false
	}

	evaluateConsensus := func(curRound int) (bool, float64) {
		mode := model.ConsensusModeUnanimous
		minRounds := 2
		threshold := 1.0
		if config.Consensus != nil {
			if config.Consensus.Mode != "" {
				mode = config.Consensus.Mode
			}
			if config.Consensus.MinRoundsBeforeExit > 0 {
				minRounds = config.Consensus.MinRoundsBeforeExit
			}
			if config.Consensus.ConsensusThreshold > 0 {
				threshold = config.Consensus.ConsensusThreshold
			}
		} else if config.ConsensusTolerance > 0 {
			threshold = config.ConsensusTolerance
			if threshold < 1.0 {
				mode = model.ConsensusModeSupermajority
			}
		}
		if mode == model.ConsensusModeDisabled {
			return false, 0
		}
		if curRound < minRounds {
			return false, 0
		}
		agents := config.Agents()
		if len(agents) == 0 {
			return false, 0
		}
		// Find index of the most recent user comment (if any)
		lastUserCommentIdx := -1
		for i := len(transcript) - 1; i >= 0; i-- {
			if transcript[i].IsUserComment {
				lastUserCommentIdx = i
				break
			}
		}

		agreedSeats := 0
		for _, agent := range agents {
			var latestTurn *model.DebateMessage
			for i := len(transcript) - 1; i > lastUserCommentIdx; i-- {
				m := transcript[i]
				if (m.SeatID == agent.ID || (m.SeatID == "" && m.AgentID == agent.Provider)) && !m.IsError && !m.IsUserComment && !m.IsSystem {
					latestTurn = &m
					break
				}
			}
			if latestTurn == nil {
				return false, 0
			}
			if isTurnInConsensus(latestTurn.Content) {
				agreedSeats++
			}
		}
		ratio := float64(agreedSeats) / float64(len(agents))
		isConsensus := false
		switch mode {
		case model.ConsensusModeUnanimous:
			isConsensus = agreedSeats == len(agents)
		case model.ConsensusModeSupermajority:
			target := threshold
			if target < 0.5 || target > 0.999 {
				target = 0.66
			}
			isConsensus = ratio >= target
		case model.ConsensusModeSimpleMajority:
			isConsensus = ratio > 0.50
		}
		// Paraconsistent Invariant: cannot conclude consensus if there are active OPEN or EXPLORED tensions!
		if isConsensus && model.HasOpenTensions(currentTensions) {
			var curRoundMessages []model.DebateMessage
			for _, m := range transcript {
				if m.Round == curRound && !m.IsError && !m.IsSystem && !m.IsUserComment {
					curRoundMessages = append(curRoundMessages, m)
				}
			}
			if len(curRoundMessages) > 0 {
				currentTensions = tensionDetector.HeuristicAnalyzeRound(config.Topic, curRound, curRoundMessages, currentTensions)
			}
			if model.HasOpenTensions(currentTensions) {
				isConsensus = false
			}
		}
		return isConsensus, ratio
	}

	// Resume point: which round and which agent within that round to continue from.
	// Count only actual agent turns (excluding user comments) to determine the cycle position.
	agents := config.Agents()
	agentCount := len(agents)
	agentTurns := make([]model.DebateMessage, 0, len(transcript))
	for _, m := range transcript {
		if !m.IsUserComment && !m.IsError {
			agentTurns = append(agentTurns, m)
		}
	}
	round := 1
	resumeIndex := 0
	if len(agentTurns) > 0 {
		maxRound := agentTurns[0].Round
		for _, m := range agentTurns {
			if m.Round > maxRound {
				maxRound = m.Round
			}
		}
		round = maxRound
		lastRoundAgentTurns := 0
		for _, m := range agentTurns {
			if m.Round == round {
				lastRoundAgentTurns++
			}
		}
		resumeIndex = lastRoundAgentTurns % agentCount
		if resumeIndex == 0 && lastRoundAgentTurns > 0 {
			round++
			resumeIndex = 0
		}
	}
	agreedEarly := false

	checkInjected := func() {
		if newMsgs := getInjected(); len(newMsgs) > 0 {
			for _, m := range newMsgs {
				transcript = append(transcript, m)
				onMessage(m)
			}
		}
	}

	allowMidRound := config.Consensus == nil || config.Consensus.AllowMidRoundTermination

	bounded := config.RoundMode == model.RoundModeFixed
	for !bounded || round <= config.MaxRounds {
		for _, agent := range agents[resumeIndex:] {
			checkInjected()
			if isStopped() {
				return model.DebateResult{Transcript: transcript, Paused: true, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, nil
			}
			// Dynamic mid-loop compaction check before every turn
			if err := maybeCompact(); err != nil && isCancellation(err) {
				return model.DebateResult{Transcript: transcript, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, err
			}
			turnErr, hardStop := speak(agent, round)
			if hardStop != nil {
				return model.DebateResult{Transcript: transcript, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, hardStop
			}
			if turnErr != "" {
				return handleTurnFailureGracefully(agent, round, turnErr)
			}
			if tokenBudget > 0 {
				used := sumTokens(transcript)
				if used >= tokenBudget && (config.TokenBudgetAction == model.TokenBudgetActionHardStop || config.TokenBudgetAction == "") {
					msg := fmt.Sprintf("Token budget of %d reached (%d used so far). Raise it in Settings > Limits, then Resume to continue.", tokenBudget, used)
					exitReason := "TOKEN_BUDGET"
					return model.DebateResult{Transcript: transcript, Error: &msg, EarlyExitReason: &exitReason, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, nil
				}
			}

			// Mid-round consensus check
			if allowMidRound {
				if reached, ratio := evaluateConsensus(round); reached {
					agreedEarly = true
					percent := int(ratio * 100)
					marker := model.DebateMessage{
						SeatID:            "system",
						Provider:          config.Primary.Provider,
						AuthorDisplayName: "System",
						AgentID:           config.Primary.Provider,
						Round:             round,
						Content:           fmt.Sprintf("Consensus achieved at Round %d (%d%% agreement). Early stopping triggered.", round, percent),
						IsSystem:          true,
					}
					transcript = append(transcript, marker)
					onMessage(marker)
					break
				}
			}
		}
		if agreedEarly {
			break
		}
		resumeIndex = 0
		// Shared Memory update at round boundary
		if useSharedMem {
			if err := updateSharedMemory(); err != nil && isCancellation(err) {
				return model.DebateResult{Transcript: transcript, TensionPairs: currentTensions}, err
			}
		}

		// Dialectic Tension Evaluation at round boundary
		var roundMessages []model.DebateMessage
		for _, m := range transcript {
			if m.Round == round && !m.IsError && !m.IsSystem && !m.IsUserComment {
				roundMessages = append(roundMessages, m)
			}
		}
		if len(roundMessages) >= 2 {
			analysisAgent := config.Primary
			ctxTension, cancelTension := context.WithTimeout(ctx, 12*time.Second)
			updatedTensions, err := tensionDetector.AnalyzeRound(
				ctxTension,
				o.RunnerFor(analysisAgent),
				analysisAgent,
				config.Topic,
				round,
				roundMessages,
				currentTensions,
				"",
			)
			cancelTension()
			if err == nil && len(updatedTensions) > 0 {
				currentTensions = updatedTensions
				if onTensionsUpdated != nil {
					onTensionsUpdated(currentTensions)
				}
			}
		}

		// In-Debate Dynamic Graph & Document Retrieval (Round-Aware RAG) for Round N+1
		if len(roundMessages) >= 1 && (opts.GraphStore != nil || len(config.AttachedFiles) > 0) {
			analysisAgent := config.Primary
			ctxEvidence, cancelEvidence := context.WithTimeout(ctx, 12*time.Second)
			roundEvidence, err := retriever.RetrieveForRound(
				ctxEvidence,
				o.RunnerFor(analysisAgent),
				analysisAgent,
				opts.ProjectID,
				config.Topic,
				round,
				roundMessages,
				config.AttachedFiles,
				currentEvidence,
			)
			cancelEvidence()
			if err == nil && len(roundEvidence.Items) > 0 {
				currentEvidence = append(currentEvidence, roundEvidence)
				if onEvidenceRetrieved != nil {
					onEvidenceRetrieved(currentEvidence)
				}
				marker := model.DebateMessage{
					SeatID:            "system",
					Provider:          config.Primary.Provider,
					AuthorDisplayName: "System",
					AgentID:           config.Primary.Provider,
					Round:             round + 1,
					Content:           fmt.Sprintf("🔍 Grounding evidence retrieved for Round %d (%d items). Authoritative sources: %s", round+1, len(roundEvidence.Items), strings.Join(roundEvidence.TriggerQueries, ", ")),
					IsSystem:          true,
					TimestampMs:       time.Now().UnixMilli(),
				}
				transcript = append(transcript, marker)
				onMessage(marker)
			}
		}

		if !allowMidRound {
			reached, ratio := evaluateConsensus(round)
			if reached {
				agreedEarly = true
				percent := int(ratio * 100)
				marker := model.DebateMessage{
					SeatID:            "system",
					Provider:          config.Primary.Provider,
					AuthorDisplayName: "System",
					AgentID:           config.Primary.Provider,
					Round:             round,
					Content:           fmt.Sprintf("Consensus achieved at Round %d (%d%% agreement). Early stopping triggered.", round, percent),
					IsSystem:          true,
				}
				transcript = append(transcript, marker)
				onMessage(marker)
				break
			} else if ratio >= 1.0 && model.HasOpenTensions(currentTensions) {
				openCount := model.OpenTensionCount(currentTensions)
				marker := model.DebateMessage{
					SeatID:            "system",
					Provider:          config.Primary.Provider,
					AuthorDisplayName: "System",
					AgentID:           config.Primary.Provider,
					Round:             round,
					Content:           fmt.Sprintf("⚠️ Superficial consensus detected: Agreement signaled by participants, but %d active dialectic tension(s) remain open. Paraconsistent consensus invariant requires all tensions to be synthesized or logged as accepted trade-offs before conclusion.", openCount),
					IsSystem:          true,
				}
				transcript = append(transcript, marker)
				onMessage(marker)
			}
		}
		round++
	}

	// Conclude using the designated moderator agent (moderatorProvider if configured, otherwise primary)
	moderatorAgent := config.Primary
	if config.Moderation != nil && config.Moderation.ModeratorProvider != "" {
		for _, a := range config.Agents() {
			if a.Provider == config.Moderation.ModeratorProvider {
				moderatorAgent = a
				break
			}
		}
	}

	var wrapUp string
	if agreedEarly {
		wrapUp = "You are the Deliberation Moderator. The participants have reached unanimous consensus on the topic.\n" +
			"Synthesize a brief, crisp consensus summary answering the core topic/question in discussion directly:\n\n" +
			"### 🎯 Outcome\n" +
			"State the direct answer/conclusion to the topic/question in 1–2 clear, decisive sentences (what the user needs to know).\n\n" +
			"### 🤝 Key Consensus Points\n" +
			"- 2–3 concise bullet points outlining the core arguments and agreement reached.\n\n" +
			"### ⚠️ Key Caveats / Trade-offs\n" +
			"- 1–2 bullet points on critical constraints or risks agreed upon (if any).\n\n" +
			"Eliminate all conversational fluff, preamble, and filler words. Keep it ultra-crisp, high-signal, and actionable."
	} else {
		wrapUp = "You are the Deliberation Moderator. The deliberation rounds have completed.\n" +
			"Synthesize a brief, crisp final outcome summary answering the topic/question:\n\n" +
			"### 🎯 Final Outcome & Recommendation\n" +
			"State the definitive decision or recommendation answering the topic/question in 1–2 clear, decisive sentences.\n\n" +
			"### 💡 Core Rationale\n" +
			"- 2–3 concise bullet points on the strongest deciding arguments.\n\n" +
			"### ⚖️ Trade-offs & Next Steps\n" +
			"- Key remaining trade-offs or recommended next actions.\n\n" +
			"Eliminate all conversational fluff, preamble, and filler words. Keep it ultra-crisp, high-signal, and actionable."
	}
	concludeAgent := moderatorAgent
	concludeAgent.SystemPrompt = wrapUp
	conclusion, err := respondWithRetry(ctx, o.RunnerFor(concludeAgent), concludeAgent, config.Topic, config.CommonContext, instructions.String(), contextView(moderatorAgent), "")
	var earlyExitReason *string
	if agreedEarly {
		reason := "CONSENSUS"
		earlyExitReason = &reason
	}
	if err != nil {
		if isCancellation(err) {
			return model.DebateResult{Transcript: transcript, TensionPairs: currentTensions, RetrievedEvidence: currentEvidence}, err
		}
		message := err.Error()
		msg := model.DebateMessage{AgentID: moderatorAgent.Provider, Round: round, Content: message, IsError: true}
		transcript = append(transcript, msg)
		onMessage(msg)
		return model.DebateResult{
			Transcript:         transcript,
			Error:              &message,
			IsConsensusReached: agreedEarly,
			EarlyExitReason:    earlyExitReason,
			TensionPairs:       currentTensions,
			RetrievedEvidence:  currentEvidence,
		}, nil
	}
	return model.DebateResult{
		Transcript:         transcript,
		Conclusion:         &conclusion.Content,
		IsConsensusReached: agreedEarly,
		EarlyExitReason:    earlyExitReason,
		TensionPairs:       currentTensions,
		RetrievedEvidence:  currentEvidence,
	}, nil
}

func sumTokens(transcript []model.DebateMessage) int {
	total := 0
	for _, m := range transcript {
		if m.TokensIn != nil {
			total += *m.TokensIn
		}
		if m.TokensOut != nil {
			total += *m.TokensOut
		}
	}
	return total
}

func isCancellation(err error) bool {
	return errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded)
}

// respondWithRetry retries once, after a short delay, on any non-cancellation failure — CLI
// and API failures don't share an error taxonomy here, so this doesn't try to distinguish
// retryable from permanent, it just retries once, which is cheap and usually right.
func respondWithRetry(
	ctx context.Context,
	r runner.AgentRunner,
	agent model.Agent,
	topic, commonContext, commonInstructions string,
	transcript []model.DebateMessage,
	modelOverride string,
) (runner.AgentReply, error) {
	reply, err := r.Respond(ctx, agent, topic, commonContext, commonInstructions, transcript, modelOverride)
	if err == nil {
		return reply, nil
	}
	if isCancellation(err) || ctx.Err() != nil {
		return runner.AgentReply{}, err
	}
	select {
	case <-ctx.Done():
		return runner.AgentReply{}, ctx.Err()
	case <-time.After(retryDelay):
	}
	return r.Respond(ctx, agent, topic, commonContext, commonInstructions, transcript, modelOverride)
}

func formatWithAttachments(baseText string, files []model.AttachedFile, scope string) string {
	var matching []model.AttachedFile
	for _, f := range files {
		cleanScope := strings.ToLower(strings.TrimSpace(f.Scope))
		// Explicit target scope match
		if cleanScope == scope {
			matching = append(matching, f)
		} else if scope == "common_context" && (cleanScope == "all" || cleanScope == "global" || cleanScope == "reference" || cleanScope == "") {
			// Global / unspecified attachments attach strictly to common_context, never duplicating into topic/rules/agent scopes
			matching = append(matching, f)
		}
	}
	if len(matching) == 0 {
		return strings.TrimSpace(baseText)
	}
	var sb strings.Builder
	cleanBase := strings.TrimSpace(baseText)
	if cleanBase != "" {
		sb.WriteString(cleanBase)
		sb.WriteString("\n\n")
	}
	// Bound total attachment characters to prevent token explosion across multiple files
	const maxTotalChars = 30000
	budgetPerFile := maxTotalChars / len(matching)
	if budgetPerFile < 3000 {
		budgetPerFile = 3000
	}
	if budgetPerFile > 20000 {
		budgetPerFile = 20000
	}
	for i, f := range matching {
		sb.WriteString(fmt.Sprintf("<attached_file name=%q>\n%s\n</attached_file>", f.Name, optimizeAttachmentContent(f.Content, budgetPerFile)))
		if i < len(matching)-1 {
			sb.WriteString("\n\n")
		}
	}
	return strings.TrimSpace(sb.String())
}

func optimizeAttachmentContent(raw string, maxChars int) string {
	if strings.TrimSpace(raw) == "" {
		return ""
	}
	text := strings.ReplaceAll(raw, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	lines := strings.Split(text, "\n")
	cleanLines := make([]string, 0, len(lines))
	for _, line := range lines {
		cleanLines = append(cleanLines, strings.TrimRight(line, " \t"))
	}
	text = strings.Join(cleanLines, "\n")
	if len(text) > maxChars {
		headLen := int(float64(maxChars) * 0.70)
		tailLen := int(float64(maxChars) * 0.30)
		trunc := len(text) - (headLen + tailLen)
		return fmt.Sprintf("%s\n\n[... Truncated %d characters to conserve token budget ...]\n\n%s",
			strings.TrimSpace(text[:headLen]), trunc, strings.TrimSpace(text[len(text)-tailLen:]))
	}
	return strings.TrimSpace(text)
}

// ownedBySeat reports whether a message came from this agent seat. Seat IDs distinguish
// several personas on one provider; the provider only matches messages that carry no seat ID.
func ownedBySeat(m model.DebateMessage, a model.Agent) bool {
	if m.SeatID != "" && a.ID != "" {
		return m.SeatID == a.ID
	}
	return m.AgentID == a.Provider
}

