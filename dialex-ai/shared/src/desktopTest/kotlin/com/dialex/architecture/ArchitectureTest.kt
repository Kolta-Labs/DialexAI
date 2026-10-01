package com.dialex.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import kotlin.test.Test
import kotlin.test.assertTrue

/** Machine-checked subset of the CLAUDE.md non-negotiables. */
class ArchitectureTest {
    private val files = Konsist.scopeFromProject().files.filter { "/commonMain/" in it.path }

    @Test
    fun `the rules actually see the code`() {
        // guards against a silent pass if the project layout or path matching changes
        assertTrue(files.size > 100, "expected >100 commonMain files, saw ${files.size}")
        assertTrue(Konsist.scopeFromProject().classes().withNameEndingWith("UseCase").size > 10)
        assertTrue(Konsist.scopeFromProject().classes().withNameEndingWith("RepositoryImpl").isNotEmpty())
        assertTrue(files.any { ".domain." in (it.packagee?.name ?: "") + "." })
    }

    private fun fail(rule: String, offenders: List<String>) =
        assertTrue(offenders.isEmpty(), "$rule\n  " + offenders.joinToString("\n  "))

    @Test
    fun `domain has no platform, UI or network imports`() {
        val banned = listOf("android.", "androidx.", "io.ktor.", "org.jetbrains.compose.")
        fail(
            "domain must stay platform-free (rule 1)",
            files.filter { ".domain." in (it.packagee?.name ?: "") + "." }
                .flatMap { f -> f.imports.filter { i -> banned.any { i.name.startsWith(it) } }.map { "${f.name}: ${it.name}" } },
        )
    }

    @Test
    fun `a UseCase never depends on another UseCase`() {
        val useCases = Konsist.scopeFromProject().classes().withNameEndingWith("UseCase")
        fail(
            "no same-layer dependencies (rule 2)",
            useCases.flatMap { c ->
                c.primaryConstructor?.parameters.orEmpty()
                    .filter { it.type.name.endsWith("UseCase") }
                    .map { "${c.name} <- ${it.type.name}" }
            },
        )
    }

    @Test
    fun `a RepositoryImpl never depends on another Repository`() {
        val impls = Konsist.scopeFromProject().classes().withNameEndingWith("RepositoryImpl")
        fail(
            "no same-layer dependencies (rule 2)",
            impls.flatMap { c ->
                c.primaryConstructor?.parameters.orEmpty()
                    .filter { it.type.name.endsWith("Repository") }
                    .map { "${c.name} <- ${it.type.name}" }
            },
        )
    }

    @Test
    fun `contract State classes expose no plain collections`() {
        val plain = Regex("""\b(List|Map|Set|MutableList|MutableMap|MutableSet)<""")
        fail(
            "use ImmutableList/Map/Set in State (rule 9)",
            files.filter { it.name.endsWith("Contract") }
                .flatMap { f ->
                    f.classes().filter { it.name == "State" || it.name.endsWith("State") }
                        .flatMap { c -> c.properties().filter { plain.containsMatchIn(it.type?.name ?: "") }.map { "${f.name}: ${c.name}.${it.name}" } }
                },
        )
    }
}
