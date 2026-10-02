package com.artix.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import com.artix.desktop.ui.components.TabHeader
import com.artix.desktop.ui.council.CouncilChamberView
import com.artix.desktop.ui.knowledge.KnowledgeInsightsView
import com.artix.desktop.ui.lab.EngineeringLabView
import com.artix.desktop.ui.persona.PersonaStudioView
import com.artix.desktop.ui.steering.SteeringStudioView
import com.artix.desktop.ui.theme.ArtixTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Artix AI — Autonomous Engineering & Cockpit Studio",
        state = WindowState(width = 1280.dp, height = 850.dp)
    ) {
        ArtixTheme {
            var selectedTab by remember { mutableStateOf(0) }

            Column(modifier = Modifier.fillMaxSize()) {
                TabHeader(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> CouncilChamberView(onSendToLab = { selectedTab = 1 })
                        1 -> EngineeringLabView()
                        2 -> SteeringStudioView()
                        3 -> PersonaStudioView()
                        4 -> KnowledgeInsightsView()
                    }
                }
            }
        }
    }
}
