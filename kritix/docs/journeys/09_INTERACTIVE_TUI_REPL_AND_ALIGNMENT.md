# Journey 09: Interactive TUI REPL & Alignment Interviews

## 1. Vision & Goals
Provide a developer-first terminal experience rivaling Claude Code and Google Antigravity. Rather than executing one-off shell invocations, developers can launch `kritix repl` (or run `kritix` without arguments in an interactive terminal) to enter a stateful, rich terminal UI with live progress indicators, context injection, and alignment interviews.

## 2. Key Capabilities
1. **Interactive Command Shell**:
   - Built with ANSI styling and terminal primitives.
   - Command history, autocompletion for slash commands and personas.
2. **Context Mentions (`@` and `#`)**:
   - `@file:<path>`: Read and attach file AST/content into prompt context.
   - `@spec:<id>`: Load active story spec into context.
   - `@rule:<name>`: Load bound steering rule.
   - `#symbol:<name>`: Extract specific symbol definitions.
3. **Slash Commands**:
   - `/plan <story>`: Triggers Stakeholder Council debate.
   - `/code [spec]`: Triggers Domain Coder & Adversarial Reviewer loop.
   - `/grill-me`: Interactive 3-question alignment interview where Council challenges user assumptions before committing to a spec.
   - `/review`: Instant adversarial inspection of current git diff.
   - `/compact`: Intelligently prune historical prompt tokens while preserving ADRs.
   - `/undo`: Revert the last applied patch.
4. **Live Deliberation & Diff Rendering**:
   - Colorized Gherkin acceptance criteria.
   - Syntax-highlighted unified diff preview with accept/reject prompts.
