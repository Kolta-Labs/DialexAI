package com.dialex.presentation.setup

import androidx.lifecycle.viewModelScope
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.ProjectRepository
import com.dialex.domain.repository.SettingsRepository
import com.dialex.domain.repository.TemplateRepository
import com.dialex.model.Agent
import com.dialex.model.CouncilTemplate
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.PersonaSelectionResult
import com.dialex.model.Provider
import com.dialex.model.SystemPersonas
import com.dialex.model.brandName
import com.dialex.model.defaultModel
import com.dialex.model.knownModels
import com.dialex.model.DiscussionPresets
import com.dialex.model.PresetArchetype
import com.dialex.orchestrator.DeliberationEstimator
import io.github.koltalabs.kolt.utils.state.AsyncState
import com.dialex.presentation.base.MviViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.launch
import kotlin.random.Random

class SetupViewModel(
    private val projectRepository: ProjectRepository,
    private val discussionRepository: DiscussionRepository,
    private val settingsRepository: SettingsRepository,
    private val templateRepository: TemplateRepository,
    private val discussionId: String?,
    private val initialProjectId: String? = null,
    private val copyFromDiscussionId: String? = null,
    supportsCli: Boolean,
    private val ollamaService: com.dialex.service.OllamaService = com.dialex.service.OllamaService()
) : MviViewModel<SetupState, SetupIntent, SetupEffect>(
    SetupState(
        supportsCli = supportsCli,
        step = if (discussionId != null || copyFromDiscussionId != null) SetupStep.ConfigForm else SetupStep.FrontPage
    )
) {

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        setState { copy(loadAsync = AsyncState.Loading) }
        viewModelScope.launch {
            try {
                val projects = projectRepository.getProjects()
                val settings = settingsRepository.getSettings()
                
                var discussion = if (discussionId != null) {
                    discussionRepository.getDiscussion(discussionId)
                } else {
                    val defaultProjectId = initialProjectId?.takeIf { pId -> projects.any { it.id == pId } }
                        ?: projects.firstOrNull { it.name.equals("Ungrouped", ignoreCase = true) }?.id
                        ?: projects.firstOrNull()?.id ?: ""
                    val proj = projects.find { it.id == defaultProjectId }
                    val effectivePolicy = proj?.debatePolicy ?: settings.debatePolicy
                    Discussion(
                        id = "",
                        projectId = defaultProjectId,
                        name = "New Discussion",
                        config = DebateConfig(
                            topic = "",
                            commonContext = proj?.sharedContext ?: "",
                            commonInfo = proj?.sharedInstructions ?: "",
                            primary = Agent(provider = Provider.ANTHROPIC, model = Provider.ANTHROPIC.defaultModel()),
                            userInterventionPolicy = effectivePolicy.userInterventionPolicy,
                            humanDialogueMode = effectivePolicy.humanDialogueMode,
                            sharedMemory = effectivePolicy.sharedMemory,
                            moderation = effectivePolicy.moderation,
                            deliverable = effectivePolicy.deliverable,
                            output = effectivePolicy.output
                        ),
                        attachedFolders = proj?.workspaceScope?.folders ?: emptyList(),
                        status = DiscussionStatus.DRAFT
                    )
                }

                if (discussionId == null && copyFromDiscussionId != null) {
                    val source = runCatching { discussionRepository.getDiscussion(copyFromDiscussionId) }.getOrNull()
                    if (source != null) {
                        // Copy all configuration from source, explicitly excluding topic
                        val newConfig = source.config.copy(topic = "")
                        discussion = discussion.copy(
                            config = newConfig,
                            attachedFolders = if (source.attachedFolders.isNotEmpty()) source.attachedFolders else discussion.attachedFolders
                        )
                    }
                }
                
                discussion = discussion.copy(config = discussion.config.sanitizeStockPersonaPrompts())
                
                val selectedProject = projects.find { it.id == discussion.projectId } ?: projects.firstOrNull()
                val otherDiscussions = try {
                    discussionRepository.getDiscussions().filter { it.id != discussion.id }
                } catch (e: Exception) {
                    if (selectedProject != null) {
                        try {
                            discussionRepository.getDiscussions(selectedProject.id).filter { it.id != discussion.id }
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else emptyList()
                }

                val fetchedModels = try { settingsRepository.getAvailableModels() } catch (e: Exception) { emptyMap() }
                val ollamaModels = try {
                    if (settings.apiKeys.ollama.isNotBlank()) {
                        ollamaService.fetchInstalledModels(settings.apiKeys.ollama).map { it.name }
                    } else emptyList()
                } catch (_: Exception) {
                    emptyList()
                }

                val allModels = Provider.entries.associate { p ->
                    val dynamic = if (p == Provider.OLLAMA) {
                        ollamaModels
                    } else {
                        fetchedModels[p.name.lowercase()] ?: fetchedModels[p.name] ?: emptyList()
                    }
                    val combined = if (p == Provider.OLLAMA && dynamic.isNotEmpty()) {
                        (dynamic + p.knownModels()).distinct()
                    } else {
                        (p.knownModels() + dynamic).distinct()
                    }
                    p.name to combined.toImmutableList()
                }.toImmutableMap()
                
                val cliMap = try { settingsRepository.getCliStatus() } catch (e: Exception) { emptyMap() }
                val cliProviders = buildSet {
                    for (p in Provider.entries) {
                        val isAvail = cliMap[p.name] == true || cliMap[p.brandName()] == true || when (p) {
                            Provider.ANTHROPIC -> cliMap["Claude Code"] == true || cliMap["Claude"] == true || cliMap["claude"] == true || cliMap["ANTHROPIC"] == true
                            Provider.OPENAI -> cliMap["Codex (OpenAI)"] == true || cliMap["Codex"] == true || cliMap["codex"] == true || cliMap["ChatGPT"] == true || cliMap["OPENAI"] == true
                            Provider.GEMINI -> cliMap["Antigravity (Gemini)"] == true || cliMap["Antigravity"] == true || cliMap["Gemini"] == true || cliMap["agy"] == true || cliMap["antigravity"] == true || cliMap["gemini"] == true || cliMap["GEMINI"] == true
                            Provider.OLLAMA -> cliMap["Ollama"] == true || cliMap["ollama"] == true
                            else -> false
                        }
                        if (isAvail) add(p)
                    }
                }.toImmutableSet()
                
                val configuredProviders = buildSet {
                    if (settings.apiKeys.anthropic.isNotBlank()) add(Provider.ANTHROPIC)
                    if (settings.apiKeys.openai.isNotBlank()) add(Provider.OPENAI)
                    if (settings.apiKeys.gemini.isNotBlank()) add(Provider.GEMINI)
                    if (settings.apiKeys.grok.isNotBlank()) add(Provider.GROK)
                    if (settings.apiKeys.deepseek.isNotBlank()) add(Provider.DEEPSEEK)
                    if (settings.apiKeys.mistral.isNotBlank()) add(Provider.MISTRAL)
                    if (settings.apiKeys.ollama.isNotBlank()) add(Provider.OLLAMA)
                    add(Provider.CUSTOM)
                }.toImmutableSet()

                val templates = try { templateRepository.getTemplates() } catch (e: Exception) { emptyList() }
                val archetype = DiscussionPresets.detectArchetype(discussion.config)
                val estimate = DeliberationEstimator.estimate(discussion.config)

                setState { 
                    copy(
                        projects = projects.toImmutableList(),
                        selectedProject = selectedProject,
                        discussion = discussion,
                        templates = templates.toImmutableList(),
                        availablePersonas = (SystemPersonas + settings.personas).distinctBy { it.id }.toImmutableList(),
                        availableModels = allModels,
                        configuredApiProviders = configuredProviders,
                        availableCliProviders = cliProviders,
                        otherDiscussions = otherDiscussions.toImmutableList(),
                        loadAsync = AsyncState.Success(Unit),
                        showProjectContextBanner = selectedProject?.sharedContext?.isNotBlank() == true || selectedProject?.sharedInstructions?.isNotBlank() == true,
                        activeArchetype = archetype,
                        runEstimate = estimate
                    )
                }
                validateConfig(discussion.config)
            } catch (e: Exception) {
                setState { copy(loadAsync = AsyncState.Error(e)) }
            }
        }
    }

    private fun validateConfig(config: DebateConfig) {
        viewModelScope.launch {
            try {
                val settings = settingsRepository.getSettings()
                val cliMap = try { settingsRepository.getCliStatus() } catch (e: Exception) { emptyMap() }
                val cliProviders = buildSet {
                    for (p in Provider.entries) {
                        val isAvail = cliMap[p.name] == true || cliMap[p.brandName()] == true || when (p) {
                            Provider.ANTHROPIC -> cliMap["Claude Code"] == true || cliMap["Claude"] == true || cliMap["claude"] == true || cliMap["ANTHROPIC"] == true
                            Provider.OPENAI -> cliMap["Codex (OpenAI)"] == true || cliMap["Codex"] == true || cliMap["codex"] == true || cliMap["ChatGPT"] == true || cliMap["OPENAI"] == true
                            Provider.GEMINI -> cliMap["Antigravity (Gemini)"] == true || cliMap["Antigravity"] == true || cliMap["Gemini"] == true || cliMap["agy"] == true || cliMap["antigravity"] == true || cliMap["gemini"] == true || cliMap["GEMINI"] == true
                            Provider.OLLAMA -> cliMap["Ollama"] == true || cliMap["ollama"] == true
                            else -> false
                        }
                        if (isAvail) add(p)
                    }
                }.toImmutableSet()

                val errors = mutableListOf<String>()
                val configuredProviders = buildSet {
                    if (settings.apiKeys.anthropic.isNotBlank()) add(Provider.ANTHROPIC)
                    if (settings.apiKeys.openai.isNotBlank()) add(Provider.OPENAI)
                    if (settings.apiKeys.gemini.isNotBlank()) add(Provider.GEMINI)
                    if (settings.apiKeys.grok.isNotBlank()) add(Provider.GROK)
                    if (settings.apiKeys.deepseek.isNotBlank()) add(Provider.DEEPSEEK)
                    if (settings.apiKeys.mistral.isNotBlank()) add(Provider.MISTRAL)
                    if (settings.apiKeys.ollama.isNotBlank()) add(Provider.OLLAMA)
                    add(Provider.CUSTOM)
                }.toImmutableSet()
                
                if (config.topic.isBlank()) errors.add("Discussion Objective & Topic is required.")
                if (config.primary.model.isBlank()) errors.add("Primary agent must have a model.")
                if (config.agents.size < 2) errors.add("At least 2 participant agents are required to start (currently ${config.agents.size})")
                
                for (agent in config.agents) {
                    if (agent.runMode == com.dialex.model.RunMode.API) {
                        val hasKey = configuredProviders.contains(agent.provider)
                        if (!hasKey) {
                            errors.add("API key missing for ${agent.provider.brandName()}")
                        }
                    } else if (agent.runMode == com.dialex.model.RunMode.CLI) {
                        val hasCli = cliProviders.contains(agent.provider)
                        if (!hasCli) {
                            errors.add("CLI not available on engine system for ${agent.provider.brandName()}")
                        }
                    }
                }

                val discFolders = state.value.discussion?.attachedFolders.orEmpty()
                val projFolders = state.value.selectedProject?.workspaceScope?.folders.orEmpty()
                val allFolders = (discFolders + projFolders).distinctBy { it.path }
                val invalidFolders = com.dialex.util.validateFolders(allFolders)
                for ((folder, reason) in invalidFolders) {
                    errors.add("Workspace folder '${folder.path}' was removed or is invalid: $reason. Further processing is blocked until resolved.")
                }

                setState {
                    copy(
                        validationErrors = errors.toImmutableList(),
                        invalidFolders = invalidFolders.toImmutableList(),
                        configuredApiProviders = configuredProviders,
                        availableCliProviders = cliProviders
                    )
                }
            } catch (e: Exception) {
                // Ignore validation transient errors
            }
        }
    }

    override fun onIntent(intent: SetupIntent) {
        when (intent) {
            is SetupIntent.SelectProject -> {
                val project = state.value.projects.find { it.id == intent.projectId } ?: return
                viewModelScope.launch {
                    val otherDiscussions = try {
                        discussionRepository.getDiscussions().filter { it.id != (state.value.discussion?.id ?: "") }
                    } catch (e: Exception) {
                        state.value.otherDiscussions
                    }
                    val currentDisc = state.value.discussion
                    val updatedDisc = if (currentDisc != null && currentDisc.id.isBlank()) {
                        val pol = project.debatePolicy
                        currentDisc.copy(
                            projectId = project.id,
                            config = currentDisc.config.copy(
                                commonContext = if (currentDisc.config.commonContext.isBlank()) project.sharedContext else currentDisc.config.commonContext,
                                commonInfo = if (currentDisc.config.commonInfo.isBlank()) project.sharedInstructions else currentDisc.config.commonInfo,
                                userInterventionPolicy = pol?.userInterventionPolicy ?: currentDisc.config.userInterventionPolicy,
                                humanDialogueMode = pol?.humanDialogueMode ?: currentDisc.config.humanDialogueMode,
                                sharedMemory = pol?.sharedMemory ?: currentDisc.config.sharedMemory,
                                moderation = pol?.moderation ?: currentDisc.config.moderation,
                                deliverable = pol?.deliverable ?: currentDisc.config.deliverable,
                                output = pol?.output ?: currentDisc.config.output
                            ),
                            attachedFolders = if (currentDisc.attachedFolders.isEmpty()) project.workspaceScope.folders else currentDisc.attachedFolders
                        )
                    } else {
                        currentDisc?.copy(projectId = project.id)
                    }
                    setState { 
                        copy(
                            selectedProject = project,
                            discussion = updatedDisc,
                            otherDiscussions = otherDiscussions.toImmutableList(),
                            showProjectContextBanner = project.sharedContext.isNotBlank() || project.sharedInstructions.isNotBlank()
                        ) 
                    }
                }
            }
            is SetupIntent.CreateProject -> {
                viewModelScope.launch {
                    try {
                        val project = projectRepository.createProject(intent.name)
                        val newProjects = (state.value.projects + project).toImmutableList()
                        setState { copy(projects = newProjects) }
                        onIntent(SetupIntent.SelectProject(project.id))
                    } catch (e: Exception) {
                        sendEffect(SetupEffect.ShowSnackbar("Failed to create project"))
                    }
                }
            }
            is SetupIntent.UpdateProjectContext -> {
                val project = state.value.selectedProject ?: return
                viewModelScope.launch {
                    try {
                        val updated = projectRepository.updateProject(project.copy(sharedContext = intent.sharedContext, sharedInstructions = intent.sharedInstructions))
                        val newProjects = state.value.projects.map { if (it.id == updated.id) updated else it }.toImmutableList()
                        setState { 
                            copy(
                                projects = newProjects,
                                selectedProject = updated,
                                showProjectContextBanner = updated.sharedContext.isNotBlank() || updated.sharedInstructions.isNotBlank()
                            )
                        }
                    } catch (e: Exception) {
                        sendEffect(SetupEffect.ShowSnackbar("Failed to update project"))
                    }
                }
            }
            is SetupIntent.DiscussionChanged -> {
                val archetype = DiscussionPresets.detectArchetype(intent.discussion.config)
                val estimate = DeliberationEstimator.estimate(intent.discussion.config)
                setState { copy(discussion = intent.discussion, activeArchetype = archetype, runEstimate = estimate) }
                validateConfig(intent.discussion.config)
            }
            is SetupIntent.ConfigChanged -> {
                val discussion = state.value.discussion ?: return
                val newDiscussion = discussion.copy(config = intent.config)
                val archetype = DiscussionPresets.detectArchetype(intent.config)
                val estimate = DeliberationEstimator.estimate(intent.config)
                setState { copy(discussion = newDiscussion, activeArchetype = archetype, runEstimate = estimate) }
                validateConfig(intent.config)
            }
            is SetupIntent.SelectArchetype -> {
                val currentDisc = state.value.discussion ?: return
                val newConfig = DiscussionPresets.applyArchetype(currentDisc.config, intent.archetype)
                val newDiscussion = currentDisc.copy(config = newConfig)
                val estimate = DeliberationEstimator.estimate(newConfig)
                setState {
                    copy(
                        discussion = newDiscussion,
                        step = SetupStep.ConfigForm,
                        activeArchetype = intent.archetype,
                        runEstimate = estimate
                    )
                }
                validateConfig(newConfig)
            }
            is SetupIntent.ToggleAdvancedDrawer -> {
                setState { copy(advancedExpanded = !advancedExpanded) }
            }
            is SetupIntent.SelectTemplate -> {
                val currentDisc = state.value.discussion ?: return
                val newConfig = intent.template.toDebateConfig(currentDisc.config)
                val newDiscussion = currentDisc.copy(config = newConfig)
                val archetype = DiscussionPresets.detectArchetype(newConfig)
                val estimate = DeliberationEstimator.estimate(newConfig)
                setState {
                    copy(
                        discussion = newDiscussion,
                        step = SetupStep.ConfigForm,
                        selectedTemplate = intent.template,
                        activeArchetype = archetype,
                        runEstimate = estimate
                    )
                }
                validateConfig(newConfig)
            }
            is SetupIntent.SelectBlankConfig -> {
                setState { copy(step = SetupStep.ConfigForm, selectedTemplate = null) }
            }
            is SetupIntent.NavigateToFrontPage -> {
                setState { copy(step = SetupStep.FrontPage, activeAgentForPersonaPicker = null) }
            }
            is SetupIntent.SaveCurrentAsTemplate -> {
                val disc = state.value.discussion ?: return
                val tpl = CouncilTemplate.fromDebateConfig(
                    id = "custom_${System.currentTimeMillis()}",
                    title = intent.title.trim(),
                    subtitle = intent.subtitle.trim(),
                    badgeLabel = intent.badgeLabel.trim().ifBlank { "Custom" },
                    config = disc.config
                )
                viewModelScope.launch {
                    try {
                        templateRepository.saveTemplate(tpl)
                        val updated = templateRepository.getTemplates().toImmutableList()
                        setState { copy(templates = updated) }
                        sendEffect(SetupEffect.ShowSnackbar("Saved as one-click preset '${tpl.title}'"))
                    } catch (e: Exception) {
                        sendEffect(SetupEffect.ShowSnackbar("Failed to save preset: ${e.message}"))
                    }
                }
            }
            is SetupIntent.DeleteCustomTemplate -> {
                viewModelScope.launch {
                    try {
                        templateRepository.deleteTemplate(intent.templateId)
                        val updated = templateRepository.getTemplates().toImmutableList()
                        setState { copy(templates = updated) }
                        sendEffect(SetupEffect.ShowSnackbar("Preset removed"))
                    } catch (e: Exception) {
                        sendEffect(SetupEffect.ShowSnackbar("Failed to delete preset"))
                    }
                }
            }
            is SetupIntent.OpenPersonaPicker -> {
                setState { copy(activeAgentForPersonaPicker = intent.agentIndex) }
            }
            is SetupIntent.DismissPersonaPicker -> {
                setState { copy(activeAgentForPersonaPicker = null) }
            }
            is SetupIntent.ApplyPersonaToAgent -> {
                val discussion = state.value.discussion ?: return
                val agents = discussion.config.agents
                if (intent.agentIndex in agents.indices) {
                    val agent = agents[intent.agentIndex]
                    val updatedAgent = when (val result = intent.result) {
                        is PersonaSelectionResult.None -> agent.copy(
                            personaId = null,
                            role = "",
                            systemPrompt = "",
                            caveman = false,
                            ponytail = false,
                            displayName = "Agent ${intent.agentIndex + 1}"
                        )
                        is PersonaSelectionResult.Stock -> agent.copy(
                            personaId = result.persona.id,
                            role = result.persona.role,
                            caveman = result.persona.caveman,
                            ponytail = result.persona.ponytail,
                            systemPrompt = "",
                            displayName = result.persona.name
                        )
                        is PersonaSelectionResult.Custom -> agent.copy(
                            personaId = "${result.basePersonaId}_custom",
                            role = result.role,
                            caveman = result.caveman,
                            ponytail = result.ponytail,
                            systemPrompt = result.systemPrompt,
                            displayName = "${result.displayName} (Custom)"
                        )
                    }
                    val newConfig = when (intent.agentIndex) {
                        0 -> discussion.config.copy(primary = updatedAgent)
                        1 -> discussion.config.copy(secondary = updatedAgent)
                        2 -> discussion.config.copy(tertiary = updatedAgent)
                        3 -> discussion.config.copy(quaternary = updatedAgent)
                        4 -> discussion.config.copy(quinary = updatedAgent)
                        5 -> discussion.config.copy(senary = updatedAgent)
                        else -> discussion.config
                    }
                    val newDiscussion = discussion.copy(config = newConfig)
                    setState { copy(discussion = newDiscussion, activeAgentForPersonaPicker = null) }
                    validateConfig(newConfig)
                }
            }
            is SetupIntent.AttachFile -> {
                val discussion = state.value.discussion ?: return
                val tokens = com.dialex.util.AttachmentTokenOptimizer.estimateTokens(intent.content)
                val size = com.dialex.util.AttachmentTokenOptimizer.formatFileSize(intent.content.length)
                val newFile = com.dialex.model.AttachedFile(
                    id = "file_${Random.nextInt()}",
                    name = intent.fileName,
                    sizeLabel = size,
                    content = intent.content,
                    scope = intent.scope,
                    estimatedTokens = tokens
                )
                val updatedFiles = discussion.attachedFiles + newFile
                val updatedConfig = discussion.config.copy(attachedFiles = updatedFiles)
                val newDiscussion = discussion.copy(
                    config = updatedConfig,
                    attachedFiles = updatedFiles
                )
                setState { copy(discussion = newDiscussion) }
            }
            is SetupIntent.RemoveFile -> {
                val discussion = state.value.discussion ?: return
                val newFiles = discussion.attachedFiles.filter { it.id != intent.fileId }
                val updatedConfig = discussion.config.copy(attachedFiles = newFiles)
                setState { copy(discussion = discussion.copy(config = updatedConfig, attachedFiles = newFiles)) }
            }
            is SetupIntent.AddFolder -> {
                val discussion = state.value.discussion ?: return
                val newFolder = com.dialex.model.FolderScope(path = intent.path, isReadOnly = intent.isReadOnly)
                val updatedFolders = (discussion.attachedFolders + newFolder).distinctBy { it.path }
                val updatedDisc = discussion.copy(attachedFolders = updatedFolders)
                setState { copy(discussion = updatedDisc) }
                validateConfig(updatedDisc.config)
            }
            is SetupIntent.RemoveFolder -> {
                val discussion = state.value.discussion ?: return
                val updatedFolders = discussion.attachedFolders.filter { it.path != intent.path }
                val project = state.value.selectedProject
                if (project != null && project.workspaceScope.folders.any { it.path == intent.path }) {
                    val updatedProjFolders = project.workspaceScope.folders.filter { it.path != intent.path }
                    val updatedProj = project.copy(workspaceScope = project.workspaceScope.copy(folders = updatedProjFolders))
                    viewModelScope.launch {
                        try { projectRepository.updateProject(updatedProj) } catch (e: Exception) {}
                    }
                    val updatedProjects = state.value.projects.map { if (it.id == updatedProj.id) updatedProj else it }.toImmutableList()
                    setState { copy(selectedProject = updatedProj, projects = updatedProjects) }
                }
                val updatedDisc = discussion.copy(attachedFolders = updatedFolders)
                setState { copy(discussion = updatedDisc) }
                validateConfig(updatedDisc.config)
            }
            is SetupIntent.RetryFolderValidation -> {
                val discussion = state.value.discussion ?: return
                validateConfig(discussion.config)
            }
            is SetupIntent.ToggleFolderTrust -> {
                val discussion = state.value.discussion ?: return
                val updatedFolders = discussion.attachedFolders.map { f ->
                    if (f.path == intent.path) {
                        val newTrust = !f.isTrusted
                        f.copy(
                            isTrusted = newTrust,
                            trustMode = if (newTrust) com.dialex.model.WorkspaceTrustMode.TRUSTED else com.dialex.model.WorkspaceTrustMode.RESTRICTED_READONLY
                        )
                    } else f
                }
                val updatedDisc = discussion.copy(attachedFolders = updatedFolders)
                setState { copy(discussion = updatedDisc) }
            }
            is SetupIntent.ToggleDiscussionWebSearch -> {
                val discussion = state.value.discussion ?: return
                val updatedPerms = discussion.config.permissions.copy(allowWebSearch = intent.enabled)
                val updatedConfig = discussion.config.copy(permissions = updatedPerms)
                setState { copy(discussion = discussion.copy(config = updatedConfig)) }
            }
            is SetupIntent.ToggleAgentWebSearch -> {
                val discussion = state.value.discussion ?: return
                val agents = discussion.config.agents
                if (intent.agentIndex in agents.indices) {
                    val target = agents[intent.agentIndex]
                    val updatedAgent = target.copy(allowWebSearch = intent.enabled)
                    val newConfig = when (intent.agentIndex) {
                        0 -> discussion.config.copy(primary = updatedAgent)
                        1 -> discussion.config.copy(secondary = updatedAgent)
                        2 -> discussion.config.copy(tertiary = updatedAgent)
                        3 -> discussion.config.copy(quaternary = updatedAgent)
                        4 -> discussion.config.copy(quinary = updatedAgent)
                        5 -> discussion.config.copy(senary = updatedAgent)
                        else -> discussion.config
                    }
                    setState { copy(discussion = discussion.copy(config = newConfig)) }
                }
            }
            is SetupIntent.RefreshAvailableModels -> {
                viewModelScope.launch {
                    try {
                        val fetchedModels = settingsRepository.getAvailableModels()
                        val allModels = Provider.entries.associate { p ->
                            val dynamic = fetchedModels[p.name.lowercase()] ?: fetchedModels[p.name] ?: emptyList()
                            val combined = (p.knownModels() + dynamic).distinct()
                            p.name to combined.toImmutableList()
                        }.toImmutableMap()
                        setState { copy(availableModels = allModels) }
                    } catch (e: Exception) {}
                }
            }
            is SetupIntent.CopySettingsFrom -> {
                viewModelScope.launch {
                    try {
                        val source = discussionRepository.getDiscussion(intent.sourceDiscussionId)
                        val discussion = state.value.discussion ?: return@launch
                        // Preserve the current discussion's topic (never copy source discussion topic)
                        val newConfig = source.config.copy(topic = discussion.config.topic).sanitizeStockPersonaPrompts()
                        val newDiscussion = discussion.copy(
                            config = newConfig,
                            attachedFolders = if (source.attachedFolders.isNotEmpty()) source.attachedFolders else discussion.attachedFolders
                        )
                        val archetype = DiscussionPresets.detectArchetype(newConfig)
                        val estimate = DeliberationEstimator.estimate(newConfig)
                        setState { 
                            copy(
                                discussion = newDiscussion,
                                step = SetupStep.ConfigForm,
                                activeArchetype = archetype,
                                runEstimate = estimate
                            ) 
                        }
                        validateConfig(newDiscussion.config)
                    } catch (e: Exception) {
                        sendEffect(SetupEffect.ShowSnackbar("Failed to copy settings"))
                    }
                }
            }
            is SetupIntent.StartDiscussion -> {
                val discussion = state.value.discussion ?: return
                if (discussion.name.isBlank()) return
                if (discussion.config.topic.isBlank() && discussion.attachedFiles.none { it.scope == "topic" }) return
                if (discussion.config.agents.size < 2) return
                if (state.value.invalidFolders.isNotEmpty()) {
                    sendEffect(SetupEffect.ShowSnackbar("Cannot start discussion: One or more workspace folders are removed or invalid."))
                    return
                }
                if (state.value.validationErrors.isNotEmpty()) return

                setState { copy(saveAsync = AsyncState.Loading) }
                viewModelScope.launch {
                    try {
                        val settings = try { settingsRepository.getSettings() } catch (e: Exception) { null }
                        val proj = state.value.selectedProject
                        val effectivePolicy = proj?.debatePolicy ?: settings?.debatePolicy ?: com.dialex.model.DebatePolicy()

                        val resolvedConfig = discussion.config.copy(
                            primary = discussion.config.primary.resolvePersonaPrompt(),
                            secondary = discussion.config.secondary?.resolvePersonaPrompt(),
                            tertiary = discussion.config.tertiary?.resolvePersonaPrompt(),
                            quaternary = discussion.config.quaternary?.resolvePersonaPrompt(),
                            quinary = discussion.config.quinary?.resolvePersonaPrompt(),
                            senary = discussion.config.senary?.resolvePersonaPrompt(),
                            humanDialogueDirective = if (discussion.config.humanDialogueMode && discussion.config.humanDialogueDirective.isBlank()) {
                                effectivePolicy.humanDialogueDirective.ifBlank { com.dialex.model.DEFAULT_HUMAN_DIALOGUE_DIRECTIVE }
                            } else discussion.config.humanDialogueDirective,
                            moderation = discussion.config.moderation.copy(
                                topicDriftDirective = if (discussion.config.moderation.detectTopicDrift && discussion.config.moderation.topicDriftDirective.isBlank()) {
                                    effectivePolicy.moderation.topicDriftDirective.ifBlank { com.dialex.model.DEFAULT_TOPIC_DRIFT_DIRECTIVE }
                                } else discussion.config.moderation.topicDriftDirective
                            )
                        )

                        val savedDiscussion = if (discussionId == null) {
                            val created = discussionRepository.createDiscussion(discussion.projectId, discussion.name, resolvedConfig)
                            val withFiles = if (discussion.attachedFiles.isNotEmpty()) {
                                val updated = created.copy(
                                    attachedFiles = discussion.attachedFiles,
                                    config = created.config.copy(attachedFiles = discussion.attachedFiles)
                                )
                                discussionRepository.updateDiscussion(updated)
                            } else created
                            discussionRepository.startDiscussion(withFiles.id)
                            withFiles
                        } else {
                            val updated = discussionRepository.updateDiscussion(discussion.copy(config = resolvedConfig))
                            if (discussion.status == DiscussionStatus.DRAFT) {
                                try {
                                    discussionRepository.startDiscussion(updated.id)
                                } catch (e: Exception) {
                                    // Discussion already active
                                }
                            }
                            updated
                        }
                        setState { copy(saveAsync = AsyncState.Success(Unit)) }
                        sendEffect(SetupEffect.NavigateToChat(savedDiscussion.id))
                    } catch (e: Exception) {
                        setState { copy(saveAsync = AsyncState.Error(e)) }
                    }
                }
            }
            is SetupIntent.NavigateBack -> {
                val discId = discussionId ?: state.value.discussion?.id?.takeIf { it.isNotBlank() }
                if (discId != null) {
                    sendEffect(SetupEffect.NavigateToChat(discId))
                } else {
                    sendEffect(SetupEffect.NavigateBack)
                }
            }
            is SetupIntent.OpenSettings -> sendEffect(SetupEffect.NavigateToSettings)
            is SetupIntent.OpenPersonaBuilder -> sendEffect(SetupEffect.NavigateToPersonaBuilder)
            is SetupIntent.DismissError -> setState { copy(saveAsync = AsyncState.Idle) }
        }
    }

    private fun Agent.resolvePersonaPrompt(): Agent {
        val id = personaId ?: return this
        if (id.endsWith("_custom")) return this
        if (systemPrompt.isNotBlank()) return this
        val catalog = SystemPersonas.find { it.id == id } ?: return this
        return copy(systemPrompt = catalog.systemPrompt)
    }

    private fun Agent.sanitizeStockPersonaPrompt(): Agent {
        val id = personaId ?: return this
        if (id.endsWith("_custom")) return this
        val catalog = SystemPersonas.find { it.id == id } ?: return this
        return if (systemPrompt == catalog.systemPrompt) copy(systemPrompt = "") else this
    }

    private fun DebateConfig.sanitizeStockPersonaPrompts(): DebateConfig = copy(
        primary = primary.sanitizeStockPersonaPrompt(),
        secondary = secondary?.sanitizeStockPersonaPrompt(),
        tertiary = tertiary?.sanitizeStockPersonaPrompt(),
        quaternary = quaternary?.sanitizeStockPersonaPrompt(),
        quinary = quinary?.sanitizeStockPersonaPrompt(),
        senary = senary?.sanitizeStockPersonaPrompt(),
    )
}
