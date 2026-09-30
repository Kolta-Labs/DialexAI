# Journey 11: Universal Language Server Protocol (LSP) Server Mode

## 1. Vision & Goals
Provide a universal bridge to every popular IDE (VS Code, IntelliJ, Cursor, Zed, Neovim) without building and maintaining 5 divergent native extensions. By implementing the standard Language Server Protocol (LSP 3.17), any editor capable of talking to an LSP server gets first-class Kritix AI capabilities.

## 2. Key Capabilities
1. **LSP Server (`pkg/lsp/server.go`)**:
   - `kritix lsp` launches a JSON-RPC 2.0 stdio server implementing core LSP methods:
     - `initialize`, `initialized`, `shutdown`, `exit`.
     - `textDocument/didOpen`, `textDocument/didChange`, `textDocument/didSave`.
2. **Taboo Space Real-Time Diagnostics (`textDocument/publishDiagnostics`)**:
   - As developers edit code, the active steering engine checks AST and text patterns against bound Persona Taboo Spaces.
   - Violations are rendered directly in the editor as warning/error squigglies (e.g. *"Taboo Violation: Direct SQLite usage in UI presentation layer"*).
3. **Code Actions & Quick Fixes (`textDocument/codeAction`)**:
   - `[Kritix: Fix Taboo Space Violation]`
   - `[Kritix: Consult Stakeholder Council]`
   - `[Kritix: Review Diff with Adversarial Reviewer]`
4. **CodeLens (`textDocument/codeLens`)**:
   - Displays clickable lenses above class declarations, public functions, or `#TODO` comments to trigger planning councils or tests.
