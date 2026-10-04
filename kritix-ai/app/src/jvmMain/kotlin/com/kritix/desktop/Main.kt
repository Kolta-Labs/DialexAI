package com.kritix.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.kritix.desktop.ui.components.TabHeader
import com.kritix.desktop.ui.roi.RoiView
import com.kritix.desktop.ui.runner.RunnerView
import com.kritix.desktop.ui.security.SecurityPerfView
import com.kritix.desktop.ui.studio.TeachAgentView
import com.kritix.desktop.ui.theme.KritixTheme
import com.kritix.desktop.ui.triage.TriageView

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Kritix AI — Autonomous QA Testing Studio & Cockpit",
        state = WindowState(width = 1280.dp, height = 850.dp)
    ) {
        KritixTheme {
            var selectedTab by remember { mutableStateOf(0) }

            Column(modifier = Modifier.fillMaxSize()) {
                TabHeader(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> RunnerView()
                        1 -> TeachAgentView()
                        2 -> SecurityPerfView()
                        3 -> TriageView()
                        4 -> RoiView()
                    }
                }
            }
        }
    }
}
