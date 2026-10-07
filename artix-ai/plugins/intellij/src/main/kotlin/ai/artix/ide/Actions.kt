package ai.artix.ide

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private fun notify(p: Project, type: NotificationType, msg: String) =
    NotificationGroupManager.getInstance().getNotificationGroup("Artix").createNotification("Artix", msg, type).notify(p)

/** Runs `artix <sub> --json [--provider/--model from env] <args>`; never autonomous. */
private fun runArtix(p: Project, sub: String, vararg args: String, onDone: (JsonObject?, String) -> Unit) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(p, "Artix $sub", true) {
        override fun run(indicator: ProgressIndicator) {
            val env = System.getenv()
            val model = buildList {
                env["ARTIX_PROVIDER"]?.takeIf { it.isNotBlank() }?.let { add("--provider"); add(it) }
                env["ARTIX_MODEL"]?.takeIf { it.isNotBlank() }?.let { add("--model"); add(it) }
            }
            val cmd = GeneralCommandLine(env["ARTIX_BIN"] ?: "artix", sub, "--json").withParameters(model).withParameters(*args)
                .withWorkDirectory(p.basePath)
            val out = ExecUtil.execAndGetOutput(cmd, 30 * 60 * 1000)
            val json = runCatching { JsonParser.parseString(out.stdout.trim().lines().last()).asJsonObject }.getOrNull()
            onDone(json, out.stderr)
        }
    })
}

private fun JsonObject.str(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString

class PlanAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        val story = Messages.showInputDialog(p, "User story", "Artix Plan", null) ?: return
        runArtix(p, "plan", story) { j, err ->
            val path = j?.str("specPath")
            if (path == null) return@runArtix notify(p, NotificationType.ERROR, "Plan failed: ${err.takeLast(400)}")
            notify(p, NotificationType.INFORMATION, "Spec: $path")
            LocalFileSystem.getInstance().refreshAndFindFileByPath(path)?.let { FileEditorManager.getInstance(p).openFile(it, true) }
        }
    }
}

class CodeAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        runArtix(p, "code", "--autonomy", "supervised") { j, err ->
            if (j?.get("success")?.asBoolean == true)
                notify(p, NotificationType.INFORMATION, "Converged in ${j.get("roundsRun")} round(s). Review the diff; nothing was committed.")
            else notify(p, NotificationType.ERROR, "Code failed: ${j?.str("error") ?: err.takeLast(400)}")
        }
    }
}

class ReviewAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val p = e.project ?: return
        runArtix(p, "review") { j, err ->
            when (j?.str("status")) {
                "approved" -> notify(p, NotificationType.INFORMATION, "Review passed: ${j.str("summary")}")
                "unreviewed" -> notify(p, NotificationType.WARNING, "Unreviewed: no model Critic ran (set ARTIX_PROVIDER/ARTIX_MODEL). NOT an approval.")
                "rejected" -> notify(p, NotificationType.ERROR, "Review rejected: ${j.str("summary")}\n${j.str("actionableFeedback") ?: ""}")
                else -> notify(p, NotificationType.ERROR, "Review failed: ${err.takeLast(400)}")
            }
        }
    }
}
