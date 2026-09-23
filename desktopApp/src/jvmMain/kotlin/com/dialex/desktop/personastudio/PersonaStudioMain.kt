package com.dialex.desktop.personastudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.dialex.data.loader.PersonaLoader
import com.dialex.data.repository.LocalFilePersonaRepository
import com.dialex.model.PredefinedPersona
import com.dialex.presentation.settings.personas.PersonaBuilderEffect
import com.dialex.presentation.settings.personas.PersonaBuilderIntent
import com.dialex.presentation.settings.personas.PersonaBuilderScreen
import com.dialex.presentation.settings.personas.PersonaBuilderState
import com.dialex.presentation.settings.personas.PersonaBuilderViewModel
import com.dialex.theme.AppTheme
import com.dialex.theme.LocalCcColors
import com.dialex.theme.ThemeMode
import com.dialex.ui.GradientButton
import com.dialex.ui.PersonaIconView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Dimension
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Descriptor for a JSON persona catalog opened in the Studio.
 */
data class CatalogDescriptor(
    val file: File,
    val isDefault: Boolean,
    val personas: List<PredefinedPersona>,
    val hasUnsavedChanges: Boolean = false
)

/**
 * Standalone Desktop App for Authoring & Curating Personas to pre-feed Dialex on deployment.
 * Supports multiple open JSON catalogs, double-clicking to edit, and copying personas interchangeably.
 */
fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "Persona Studio")
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    application {
        val windowState = rememberWindowState(
            width = 1360.dp,
            height = 840.dp,
            position = WindowPosition.Aligned(Alignment.Center)
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "Dialex Persona Studio — Multi-Catalog Persona Authoring",
            state = windowState
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(1060, 620)
            }
            AppTheme(ThemeMode.SYSTEM) {
                PersonaStudioApp()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonaStudioApp() {
    val cc = LocalCcColors.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Locate bundled personas file by default if in project root
    val defaultProjectFile = remember {
        val f = File("shared/src/commonMain/composeResources/files/personas/bundled_personas.json")
        if (f.exists()) f else File("bundled_personas.json")
    }

    val jsonSerializer = remember {
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    var catalogs by remember { mutableStateOf<List<CatalogDescriptor>>(emptyList()) }
    var activeFilePath by remember { mutableStateOf<String?>(null) }
    var selectedPersonaId by remember { mutableStateOf<String?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var catalogToDelete by remember { mutableStateOf<CatalogDescriptor?>(null) }

    // Initialize Default In-Codebase Catalog
    LaunchedEffect(defaultProjectFile) {
        if (catalogs.isEmpty()) {
            val initialPersonas = if (defaultProjectFile.exists()) {
                try {
                    val text = defaultProjectFile.readText()
                    jsonSerializer.decodeFromString<List<PredefinedPersona>>(text)
                } catch (e: Exception) {
                    PersonaLoader.getCachedBundledPersonas()
                }
            } else {
                PersonaLoader.getCachedBundledPersonas()
            }

            val defaultCatalog = CatalogDescriptor(
                file = defaultProjectFile,
                isDefault = true,
                personas = initialPersonas,
                hasUnsavedChanges = false
            )
            catalogs = listOf(defaultCatalog)
            activeFilePath = defaultProjectFile.canonicalPath
        }
    }

    val activeCatalog = catalogs.find { it.file.canonicalPath == activeFilePath } ?: catalogs.firstOrNull()

    fun saveCatalog(targetCatalog: CatalogDescriptor) {
        scope.launch(Dispatchers.IO) {
            try {
                val text = jsonSerializer.encodeToString(targetCatalog.personas)
                targetCatalog.file.writeText(text)
                withContext(Dispatchers.Main) {
                    catalogs = catalogs.map { cat ->
                        if (cat.file.canonicalPath == targetCatalog.file.canonicalPath) {
                            cat.copy(hasUnsavedChanges = false)
                        } else cat
                    }
                    snackbarHostState.showSnackbar("Saved ${targetCatalog.personas.size} personas to ${targetCatalog.file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackbarHostState.showSnackbar("Error saving ${targetCatalog.file.name}: ${e.message}")
                }
            }
        }
    }

    fun createNewExternalJson() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Create New Personas JSON Catalog"
            fileFilter = FileNameExtensionFilter("JSON Files (*.json)", "json")
            selectedFile = File("custom_personas.json")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.endsWith(".json", ignoreCase = true)) {
                file = File(file.parentFile, file.name + ".json")
            }
            if (!file.exists()) {
                file.writeText("[]")
            }
            val path = file.canonicalPath
            val alreadyLoaded = catalogs.find { it.file.canonicalPath == path }
            if (alreadyLoaded != null) {
                activeFilePath = path
                selectedPersonaId = null
                isCreatingNew = false
            } else {
                val newCatalog = CatalogDescriptor(
                    file = file,
                    isDefault = false,
                    personas = emptyList(),
                    hasUnsavedChanges = false
                )
                catalogs = catalogs + newCatalog
                activeFilePath = path
                selectedPersonaId = null
                isCreatingNew = false
                scope.launch {
                    snackbarHostState.showSnackbar("Created & opened ${file.name}")
                }
            }
        }
    }

    fun openExistingExternalJson() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Open Personas JSON Catalog"
            fileFilter = FileNameExtensionFilter("JSON Files (*.json)", "json")
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            val path = file.canonicalPath
            val alreadyLoaded = catalogs.find { it.file.canonicalPath == path }
            if (alreadyLoaded != null) {
                activeFilePath = path
                selectedPersonaId = null
                isCreatingNew = false
            } else {
                try {
                    val text = file.readText()
                    val list = jsonSerializer.decodeFromString<List<PredefinedPersona>>(text)
                    val newCatalog = CatalogDescriptor(
                        file = file,
                        isDefault = false,
                        personas = list,
                        hasUnsavedChanges = false
                    )
                    catalogs = catalogs + newCatalog
                    activeFilePath = path
                    selectedPersonaId = null
                    isCreatingNew = false
                    scope.launch {
                        snackbarHostState.showSnackbar("Loaded ${list.size} personas from ${file.name}")
                    }
                } catch (e: Exception) {
                    scope.launch {
                        snackbarHostState.showSnackbar("Failed to open ${file.name}: ${e.message}")
                    }
                }
            }
        }
    }

    fun copyPersonaToCatalog(persona: PredefinedPersona, targetCatalog: CatalogDescriptor) {
        scope.launch(Dispatchers.IO) {
            try {
                // Check if duplicate ID exists in destination
                val idExists = targetCatalog.personas.any { it.id == persona.id }
                val copiedPersona = if (idExists) {
                    val newId = "${persona.id}_copy_${System.currentTimeMillis() % 1000}"
                    persona.copy(id = newId, name = "${persona.name} (Copy)")
                } else {
                    persona.copy()
                }

                val updatedPersonas = targetCatalog.personas + copiedPersona
                val text = jsonSerializer.encodeToString(updatedPersonas)
                targetCatalog.file.writeText(text)

                withContext(Dispatchers.Main) {
                    catalogs = catalogs.map { cat ->
                        if (cat.file.canonicalPath == targetCatalog.file.canonicalPath) {
                            cat.copy(personas = updatedPersonas, hasUnsavedChanges = false)
                        } else cat
                    }
                    snackbarHostState.showSnackbar("Copied '${persona.name}' to ${targetCatalog.file.name}")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    snackbarHostState.showSnackbar("Failed to copy persona: ${e.message}")
                }
            }
        }
    }

    // Filter personas in active catalog
    val activePersonas = activeCatalog?.personas.orEmpty()
    val filteredPersonas = remember(activePersonas, searchQuery) {
        if (searchQuery.isBlank()) activePersonas
        else activePersonas.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.category.contains(searchQuery, ignoreCase = true) ||
            it.role.contains(searchQuery, ignoreCase = true) ||
            it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    // Local Repository backing the ViewModel for the active catalog
    val repository = remember(activeCatalog, activePersonas) {
        LocalFilePersonaRepository(
            initialPersonas = activePersonas,
            onPersist = { updated ->
                if (activeCatalog != null) {
                    catalogs = catalogs.map { cat ->
                        if (cat.file.canonicalPath == activeCatalog.file.canonicalPath) {
                            cat.copy(personas = updated, hasUnsavedChanges = true)
                        } else cat
                    }
                    // Auto-write to active file
                    scope.launch(Dispatchers.IO) {
                        try {
                            val text = jsonSerializer.encodeToString(updated)
                            activeCatalog.file.writeText(text)
                            withContext(Dispatchers.Main) {
                                catalogs = catalogs.map { cat ->
                                    if (cat.file.canonicalPath == activeCatalog.file.canonicalPath) {
                                        cat.copy(hasUnsavedChanges = false)
                                    } else cat
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(cc.bg)) {
        Row(Modifier.fillMaxSize()) {
            // ══════════════════════════════════════════════════════════════════
            // PANE 1: CATALOG EXPLORER (Far Left, 250.dp)
            // ══════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .width(250.dp)
                    .fillMaxHeight()
                    .background(cc.panel)
                    .padding(14.dp)
            ) {
                // Header: Catalogs Title & Add/Open Icons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Catalogs",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = cc.textPrimary
                        )
                        Text(
                            "${catalogs.size} open",
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.textMuted
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { createNewExternalJson() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Add, contentDescription = "New External JSON", tint = cc.accent, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { openExistingExternalJson() }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = "Open JSON File", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = cc.border.copy(alpha = 0.35f))
                Spacer(Modifier.height(10.dp))

                // Catalogs List
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pinned Default In-Codebase Catalog
                    val defaultCatalog = catalogs.find { it.isDefault }
                    if (defaultCatalog != null) {
                        item(key = "default_catalog") {
                            val isActive = activeFilePath == defaultCatalog.file.canonicalPath
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) cc.accent.copy(alpha = 0.16f) else cc.panelAlt,
                                border = BorderStroke(1.dp, if (isActive) cc.accent else cc.border.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (!isActive) {
                                                activeFilePath = defaultCatalog.file.canonicalPath
                                                selectedPersonaId = null
                                                isCreatingNew = false
                                            }
                                        },
                                        onDoubleClick = {
                                            activeFilePath = defaultCatalog.file.canonicalPath
                                            selectedPersonaId = null
                                            isCreatingNew = false
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Editing Default In-Codebase Catalog")
                                            }
                                        }
                                    )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Outlined.Lock,
                                                contentDescription = "Default Bundled File (Protected)",
                                                tint = cc.accent,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                defaultCatalog.file.name + if (defaultCatalog.hasUnsavedChanges) " *" else "",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                color = if (isActive) cc.accent else cc.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                activeFilePath = defaultCatalog.file.canonicalPath
                                                selectedPersonaId = null
                                                isCreatingNew = true
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = "Add Persona to Default", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "DEFAULT (BUNDLED)",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                            color = cc.accent
                                        )
                                        Text(
                                            "${defaultCatalog.personas.size} personas",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = cc.textMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // External Catalogs Header
                    val externalCatalogs = catalogs.filter { !it.isDefault }
                    item {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "EXTERNAL JSONS (${externalCatalogs.size})",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    if (externalCatalogs.isEmpty()) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = cc.panelAlt.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text(
                                    "No external catalogs open.\nClick '+' to create or open a JSON.",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
                                    color = cc.textMuted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    } else {
                        items(externalCatalogs, key = { it.file.canonicalPath }) { catalog ->
                            val isActive = activeFilePath == catalog.file.canonicalPath

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isActive) cc.accent.copy(alpha = 0.16f) else cc.panelAlt,
                                border = BorderStroke(1.dp, if (isActive) cc.accent else cc.border.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (!isActive) {
                                                activeFilePath = catalog.file.canonicalPath
                                                selectedPersonaId = null
                                                isCreatingNew = false
                                            }
                                        },
                                        onDoubleClick = {
                                            activeFilePath = catalog.file.canonicalPath
                                            selectedPersonaId = null
                                            isCreatingNew = false
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Editing catalog: ${catalog.file.name}")
                                            }
                                        }
                                    )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Outlined.Folder,
                                                contentDescription = null,
                                                tint = if (isActive) cc.accent else cc.textMuted,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                catalog.file.name + if (catalog.hasUnsavedChanges) " *" else "",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                color = if (isActive) cc.accent else cc.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    activeFilePath = catalog.file.canonicalPath
                                                    selectedPersonaId = null
                                                    isCreatingNew = true
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = "Add Persona to this catalog", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                            }
                                            IconButton(
                                                onClick = { catalogToDelete = catalog },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete External Catalog",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "EXTERNAL",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                            color = cc.textMuted
                                        )
                                        Text(
                                            "${catalog.personas.size} personas",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = cc.textMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = cc.border.copy(alpha = 0.4f))

            // ══════════════════════════════════════════════════════════════════
            // PANE 2: PERSONAS IN ACTIVE CATALOG (Middle, 320.dp)
            // ══════════════════════════════════════════════════════════════════
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(cc.panelAlt)
                    .padding(14.dp)
            ) {
                // Active Catalog Title & Save Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            activeCatalog?.file?.name ?: "No Catalog Selected",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = cc.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (activeCatalog?.isDefault == true) "Default Bundled Catalog" else "External Catalog",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (activeCatalog?.isDefault == true) cc.accent else cc.textMuted
                        )
                    }

                    if (activeCatalog?.hasUnsavedChanges == true) {
                        IconButton(onClick = { saveCatalog(activeCatalog) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Outlined.Save, contentDescription = "Save Catalog", tint = cc.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter personas...", color = cc.textMuted.copy(alpha = 0.6f), fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp)) },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = cc.textMuted, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                )

                Spacer(Modifier.height(10.dp))

                // "New Persona" Button with Target Selector
                var showAddTargetMenu by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GradientButton(
                        text = "New Persona",
                        icon = Icons.Default.Add,
                        onClick = {
                            selectedPersonaId = null
                            isCreatingNew = true
                        },
                        height = 34.dp,
                        modifier = Modifier.weight(1f)
                    )

                    Box {
                        OutlinedButton(
                            onClick = { showAddTargetMenu = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Target ▾", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp))
                        }

                        DropdownMenu(
                            expanded = showAddTargetMenu,
                            onDismissRequest = { showAddTargetMenu = false }
                        ) {
                            Text(
                                "Add persona to:",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                            catalogs.forEach { cat ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            cat.file.name + if (cat.isDefault) " (Default)" else "",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    },
                                    onClick = {
                                        activeFilePath = cat.file.canonicalPath
                                        selectedPersonaId = null
                                        isCreatingNew = true
                                        showAddTargetMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = cc.border.copy(alpha = 0.35f))
                Spacer(Modifier.height(8.dp))

                // Personas List Count
                Text(
                    "PERSONAS (${filteredPersonas.size})",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    color = cc.textMuted
                )

                Spacer(Modifier.height(8.dp))

                if (filteredPersonas.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                if (searchQuery.isNotBlank()) "No matching personas" else "No personas in catalog",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = cc.textMuted,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                if (searchQuery.isNotBlank()) "Try another search term"
                                else "Click 'New Persona' or copy personas from another catalog.",
                                style = MaterialTheme.typography.labelSmall,
                                color = cc.textMuted.copy(alpha = 0.8f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredPersonas, key = { it.id }) { persona ->
                            val isSelected = !isCreatingNew && persona.id == selectedPersonaId
                            var showCopyMenu by remember { mutableStateOf(false) }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) cc.accent.copy(alpha = 0.15f) else cc.panel,
                                border = BorderStroke(1.dp, if (isSelected) cc.accent else cc.border.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    isCreatingNew = false
                                    selectedPersonaId = persona.id
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        PersonaIconView(icon = persona.icon, category = persona.category, size = 30.dp)
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                persona.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = cc.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                persona.category,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = cc.textMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // "Copy To..." Action Button
                                        val otherCatalogs = catalogs.filter { it.file.canonicalPath != activeCatalog?.file?.canonicalPath }
                                        if (otherCatalogs.isNotEmpty()) {
                                            Box {
                                                IconButton(
                                                    onClick = { showCopyMenu = true },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.ContentCopy,
                                                        contentDescription = "Copy to another JSON catalog",
                                                        tint = cc.textMuted,
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }

                                                DropdownMenu(
                                                    expanded = showCopyMenu,
                                                    onDismissRequest = { showCopyMenu = false }
                                                ) {
                                                    Text(
                                                        "Copy to catalog:",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = cc.textMuted,
                                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                    )
                                                    otherCatalogs.forEach { targetCat ->
                                                        DropdownMenuItem(
                                                            text = {
                                                                Text(
                                                                    targetCat.file.name + if (targetCat.isDefault) " (Default)" else "",
                                                                    style = MaterialTheme.typography.bodySmall
                                                                )
                                                            },
                                                            onClick = {
                                                                copyPersonaToCatalog(persona, targetCat)
                                                                showCopyMenu = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // Delete Persona Button
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    repository.deletePersona(persona.id)
                                                    if (selectedPersonaId == persona.id) {
                                                        selectedPersonaId = null
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "Delete Persona",
                                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight(), color = cc.border.copy(alpha = 0.4f))

            // ══════════════════════════════════════════════════════════════════
            // PANE 3: PERSONA BUILDER / EDITOR (Right, weight = 1f)
            // ══════════════════════════════════════════════════════════════════
            Box(Modifier.weight(1f).fillMaxHeight().background(cc.bg)) {
                val currentTargetId = if (isCreatingNew) null else selectedPersonaId

                if (currentTargetId != null || isCreatingNew) {
                    val viewModel = remember(currentTargetId, isCreatingNew, activeFilePath) {
                        PersonaBuilderViewModel(
                            personaRepository = repository,
                            personaId = currentTargetId
                        )
                    }
                    var editorState by remember { mutableStateOf(PersonaBuilderState(isEditing = currentTargetId != null)) }

                    LaunchedEffect(viewModel) {
                        viewModel.state.collect { editorState = it }
                    }

                    LaunchedEffect(viewModel) {
                        viewModel.effect.collect { effect ->
                            when (effect) {
                                is PersonaBuilderEffect.NavigateBack -> {
                                    isCreatingNew = false
                                    selectedPersonaId = null
                                }
                                is PersonaBuilderEffect.ShowSnackbar -> {
                                    snackbarHostState.showSnackbar(effect.message)
                                }
                            }
                        }
                    }

                    PersonaBuilderScreen(
                        state = editorState,
                        onIntent = viewModel::onIntent,
                        onBack = {
                            isCreatingNew = false
                            selectedPersonaId = null
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.padding(36.dp)
                        ) {
                            Text(
                                "Dialex Persona Studio",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = cc.textPrimary
                            )
                            Text(
                                if (activeCatalog?.isDefault == true)
                                    "Editing canonical in-codebase catalog (${activeCatalog.file.name}).\nAny personas added or edited here will be pre-fed into the compiled app deliverable."
                                else
                                    "Editing external JSON catalog (${activeCatalog?.file?.name ?: ""}).\nYou can author personas, export them, or copy them interchangeably to the default bundle.",
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                                color = cc.textMuted,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                GradientButton(
                                    text = "Create New Persona",
                                    icon = Icons.Default.Add,
                                    onClick = { isCreatingNew = true },
                                    height = 40.dp
                                )
                                OutlinedButton(
                                    onClick = { createNewExternalJson() },
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, cc.border),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = cc.panelAlt, contentColor = cc.textPrimary),
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("New JSON Catalog", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Delete Catalog Confirmation Dialog
        if (catalogToDelete != null) {
            val target = catalogToDelete!!
            AlertDialog(
                onDismissRequest = { catalogToDelete = null },
                title = { Text("Delete Catalog") },
                text = {
                    Text("How would you like to remove '${target.file.name}'?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            try {
                                target.file.delete()
                            } catch (_: Exception) {}
                            catalogs = catalogs.filter { it.file.canonicalPath != target.file.canonicalPath }
                            if (activeFilePath == target.file.canonicalPath) {
                                activeFilePath = defaultProjectFile.canonicalPath
                                selectedPersonaId = null
                                isCreatingNew = false
                            }
                            catalogToDelete = null
                            scope.launch {
                                snackbarHostState.showSnackbar("Deleted ${target.file.name} from disk")
                            }
                        }
                    ) {
                        Text("Delete File from Disk", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                catalogs = catalogs.filter { it.file.canonicalPath != target.file.canonicalPath }
                                if (activeFilePath == target.file.canonicalPath) {
                                    activeFilePath = defaultProjectFile.canonicalPath
                                    selectedPersonaId = null
                                    isCreatingNew = false
                                }
                                catalogToDelete = null
                                scope.launch {
                                    snackbarHostState.showSnackbar("Removed ${target.file.name} from studio")
                                }
                            }
                        ) {
                            Text("Remove from Studio")
                        }
                        TextButton(onClick = { catalogToDelete = null }) {
                            Text("Cancel")
                        }
                    }
                }
            )
        }

        com.dialex.ui.ThemedSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        )
    }
}
