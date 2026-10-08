import * as vscode from "vscode";
import { execFile } from "child_process";

const out = vscode.window.createOutputChannel("Artix");

function run(args: string[]): Promise<{ json: any; stderr: string; code: number }> {
  const cfg = vscode.workspace.getConfiguration("artix");
  const cwd = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  const model = [cfg.get<string>("provider") ? ["--provider", cfg.get<string>("provider")!] : [],
                 cfg.get<string>("model") ? ["--model", cfg.get<string>("model")!] : []].flat();
  const full = [...args.slice(0, 1), "--json", ...model, ...args.slice(1)];
  return new Promise((resolve) => {
    execFile(cfg.get<string>("binary") || "artix", full, { cwd, maxBuffer: 64 << 20 }, (err, stdout, stderr) => {
      let json: any = null;
      try { json = JSON.parse(stdout.trim().split("\n").pop() || ""); } catch { /* not JSON */ }
      resolve({ json, stderr, code: err ? ((err as any).code ?? 1) : 0 });
    });
  });
}

function show(title: string, r: { json: any; stderr: string; code: number }) {
  out.clear(); out.appendLine(`# ${title}`);
  out.appendLine(r.json ? JSON.stringify(r.json, null, 2) : "(no JSON output)");
  if (r.stderr) out.appendLine("\n--- log ---\n" + r.stderr);
  out.show(true);
}

export function activate(ctx: vscode.ExtensionContext) {
  const reg = (id: string, fn: () => Promise<void>) =>
    ctx.subscriptions.push(vscode.commands.registerCommand(id, () =>
      vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: id }, fn)));

  reg("artix.plan", async () => {
    const story = await vscode.window.showInputBox({ prompt: "User story" });
    if (!story) return;
    const r = await run(["plan", story]); show("Plan", r);
    if (r.json?.specPath) vscode.window.showTextDocument(await vscode.workspace.openTextDocument(r.json.specPath));
    else vscode.window.showErrorMessage("Artix plan failed; see Output > Artix.");
  });

  reg("artix.code", async () => {
    // Never autonomous from the IDE. Query plan for planned test commands
    const planRes = await run(["plan", "--json"]);
    const testCmds: string[] = planRes.json?.testCommands ?? [];
    const cmdListStr = testCmds.length > 0 ? testCmds.map(c => `  • ${c}`).join("\n") : "  (default test runner)";
    const confirm = await vscode.window.showInformationMessage(
      `Artix supervised mode will execute the following test commands:\n${cmdListStr}\n\nDo you want to proceed?`,
      { modal: true },
      "Confirm & Run", "Cancel"
    );
    if (confirm !== "Confirm & Run") return;
    const r = await run(["code", "--autonomy", "supervised", "--confirm-tests"]); show("Code", r);
    if (r.json?.status === "awaiting_approval" || r.json?.awaitingApproval) {
      vscode.window.showInformationMessage(`Candidate commit ${r.json.commitHash ?? ""} pushed to PR branch. Awaiting human approval on forge.`);
    } else if (r.json?.success) {
      if (r.json.commitHash) {
        vscode.window.showInformationMessage(`Converged in ${r.json.roundsRun} round(s). Committed: ${r.json.commitHash}`);
      } else {
        vscode.window.showInformationMessage(`Converged in ${r.json.roundsRun} round(s). Review the diff; nothing was committed.`);
      }
    } else {
      vscode.window.showErrorMessage(`Artix code: ${r.json?.error ?? "failed"}`);
    }
  });

  reg("artix.review", async () => {
    const r = await run(["review"]); show("Review", r);
    const v = r.json;
    if (!v) return void vscode.window.showErrorMessage("Artix review failed; see Output > Artix.");
    if (v.status === "approved") vscode.window.showInformationMessage(`Review passed: ${v.summary}`);
    else if (v.status === "unreviewed") vscode.window.showWarningMessage("Unreviewed: no model Critic ran. Set artix.provider/model. This is NOT an approval.");
    else vscode.window.showErrorMessage(`Review rejected: ${v.summary}`);
  });
}
export function deactivate() {}
