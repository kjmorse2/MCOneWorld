package io.github.kjmorse2.mconeworld.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Fails if a module's *production* classpaths (compile and runtime of the `main` source set)
 * resolve a banned artifact or a project its rule family ([ArchitectureRules]) forbids.
 *
 * Test classpaths are deliberately not inspected: tests may use `test-fixtures`.
 */
abstract class VerifyModuleDependencies : DefaultTask() {
    /** Rule family: `shared` or `tool` (see [ArchitectureRules.named]). */
    @get:Input abstract val ruleFamily: Property<String>

    /** Module name within the family, e.g. `domain` or `core`. */
    @get:Input abstract val moduleName: Property<String>

    /** Root components of the resolved production classpaths (configuration-cache compatible). */
    @get:Input abstract val productionRoots: ListProperty<ResolvedComponentResult>

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val module = moduleName.get()
        val rules = ArchitectureRules.named(ruleFamily.get())
        val violations = sortedSetOf<String>()
        val seen = sortedSetOf<String>()
        for (root in productionRoots.get()) {
            walk(root) { component ->
                when (val id = component.id) {
                    is ModuleComponentIdentifier -> {
                        seen += "${id.group}:${id.module}:${id.version}"
                        violations += rules.artifactViolations(module, id.group, id.displayName)
                    }
                    is ProjectComponentIdentifier -> {
                        seen += "project ${id.projectPath}"
                        violations += rules.projectEdgeViolations(module, id.projectPath)
                    }
                    else -> seen += id.displayName
                }
            }
        }
        report.get().asFile.writeText(
            buildString {
                appendLine("${rules.family}:$module production dependency graph")
                seen.forEach { appendLine("  $it") }
                appendLine(if (violations.isEmpty()) "OK" else "VIOLATIONS:")
                violations.forEach { appendLine("  $it") }
            },
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "${rules.family} dependency rules violated (see ${rules.source}):\n  " + violations.joinToString("\n  "),
            )
        }
    }

    private fun walk(root: ResolvedComponentResult, visit: (ResolvedComponentResult) -> Unit) {
        val queue = ArrayDeque(listOf(root))
        val visited = mutableSetOf<Any>()
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!visited.add(next.id)) continue
            visit(next)
            next.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
        }
    }
}
