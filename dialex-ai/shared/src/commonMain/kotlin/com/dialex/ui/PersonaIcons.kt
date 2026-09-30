@file:Suppress("DEPRECATION")

package com.dialex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.ui.graphics.vector.ImageVector

data class PersonaIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val category: String = "General"
)

val PredefinedPersonaIcons: List<PersonaIconOption> = listOf(
    PersonaIconOption("psychology", "Mind / Brain", Icons.Outlined.Psychology, "General Debate"),
    PersonaIconOption("code", "Code / Dev", Icons.Outlined.Code, "Software Engineering"),
    PersonaIconOption("shield", "Security / Defense", Icons.Outlined.Shield, "Software Engineering"),
    PersonaIconOption("storage", "Database / Storage", Icons.Outlined.Storage, "Software Engineering"),
    PersonaIconOption("build", "SRE / Tooling", Icons.Outlined.Build, "Software Engineering"),
    PersonaIconOption("speed", "Performance / Speed", Icons.Outlined.Bolt, "Software Engineering"),
    PersonaIconOption("terminal", "CLI / Terminal", Icons.Outlined.Terminal, "Software Engineering"),
    PersonaIconOption("science", "Science / Research", Icons.Outlined.Science, "Scientific Research"),
    PersonaIconOption("biotech", "Biotech / Genetics", Icons.Outlined.Biotech, "Scientific Research"),
    PersonaIconOption("analytics", "Analytics / Stats", Icons.Outlined.Analytics, "Scientific Research"),
    PersonaIconOption("edit_note", "Writing / Pen", Icons.Outlined.EditNote, "Writing & Journalism"),
    PersonaIconOption("menu_book", "Editorial / Book", Icons.Outlined.MenuBook, "Writing & Journalism"),
    PersonaIconOption("visibility", "Audit / Investigate", Icons.Outlined.Visibility, "Writing & Journalism"),
    PersonaIconOption("trending_up", "Growth / Strategy", Icons.Outlined.TrendingUp, "Product & Strategy"),
    PersonaIconOption("work", "Business / Ops", Icons.Outlined.WorkOutline, "Product & Strategy"),
    PersonaIconOption("lightbulb", "Idea / Disruption", Icons.Outlined.Lightbulb, "Product & Strategy"),
    PersonaIconOption("gavel", "Legal / Court", Icons.Outlined.Gavel, "Legal & Governance"),
    PersonaIconOption("balance", "Justice / Ethics", Icons.Outlined.Balance, "Legal & Governance"),
    PersonaIconOption("flag", "Policy / State", Icons.Outlined.Flag, "Legal & Governance"),
    PersonaIconOption("local_hospital", "Clinical / Hospital", Icons.Outlined.LocalHospital, "Healthcare & Medicine"),
    PersonaIconOption("health_and_safety", "Health / Safety", Icons.Outlined.HealthAndSafety, "Healthcare & Medicine"),
    PersonaIconOption("healing", "Medicine / Care", Icons.Outlined.Healing, "Healthcare & Medicine"),
    PersonaIconOption("smart_toy", "AI / Automation", Icons.Outlined.SmartToy, "General Debate"),
    PersonaIconOption("auto_awesome", "Star / Creative", Icons.Outlined.AutoAwesome, "General Debate"),
    PersonaIconOption("person", "Human / Persona", Icons.Outlined.Person, "General Debate"),
)

fun resolvePersonaIcon(iconKey: String?, category: String? = null): ImageVector {
    if (!iconKey.isNullOrBlank()) {
        val found = PredefinedPersonaIcons.firstOrNull { it.key.equals(iconKey, ignoreCase = true) }
        if (found != null) return found.icon
    }

    return when (category?.trim()?.lowercase()) {
        "software engineering" -> Icons.Outlined.Code
        "scientific research" -> Icons.Outlined.Science
        "writing & journalism" -> Icons.Outlined.EditNote
        "book writing & publishing", "writing & publishing" -> Icons.Outlined.MenuBook
        "product & strategy" -> Icons.Outlined.TrendingUp
        "legal & governance" -> Icons.Outlined.Gavel
        "healthcare & medicine" -> Icons.Outlined.LocalHospital
        else -> Icons.Outlined.Psychology
    }
}
