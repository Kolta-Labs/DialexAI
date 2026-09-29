# Journey 12: Auto-Evolving Knowledge Items (KI) & PR-to-Rule Synthesizer

## 1. Vision & Goals
Ground the platform with persistent institutional memory. When agents or human engineers solve intricate repository bugs, overcome subtle build quirks, or establish design conventions, those insights should be permanently retained and injected into future deliberation councils.

## 2. Key Capabilities
1. **Knowledge Item (KI) Store (`pkg/knowledge/store.go`)**:
   - Stores knowledge units in `.kritix/knowledge/<id>.json` and global `~/.kritix/knowledge/`.
   - Schema:
     - `ID`: unique slug
     - `Title`: descriptive summary
     - `Category`: `architecture`, `debugging`, `flaky_test`, `build_manifest`
     - `Context`: code context, error traces, file paths
     - `Solution`: verified pattern to adhere to
     - `AssociatedRules`: links to Taboo Space or Heuristics
2. **Auto-Learning Extraction (`pkg/knowledge/extractor.go`)**:
   - When the Domain Coder $\leftrightarrow$ Reviewer loop converges after 2 or more iterations, automatically extract the breakthrough insight that turned the build from Red to Green.
   - Saves new Knowledge Item candidate for developer confirmation.
3. **PR-to-Rule Synthesizer**:
   - Parses code review comments or commit rejection reasons.
   - Synthesizes suggested steering rules for immediate addition into `.standards/steering/` or `.kritix/steering.json`.
