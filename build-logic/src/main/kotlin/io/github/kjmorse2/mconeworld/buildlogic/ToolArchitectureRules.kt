package io.github.kjmorse2.mconeworld.buildlogic

import io.github.kjmorse2.mconeworld.buildlogic.ArchitectureRules.Companion.isWithin

/**
 * Pure statement of the MCOneWorld Tool's module rules (`tool/AGENTS.md` §2):
 *
 * - `tool/world` reads and archives world files; it sees only `shared:domain`.
 * - `tool/core` holds headless services; it sees `tool/world`, `shared:domain`, `shared:sidecar`.
 * - `tool/app` is the JavaFX UI; it sees `tool/core` (and, through it, the shared types its API
 *   exposes) but never `tool/world` directly.
 * - Only `tool/app` may use JavaFX. No tool module uses a Minecraft, Paper, Fabric, or MCA
 *   Selector artifact: vendored MCA Selector code lives in our own packages (ADR 0009).
 * - No tool module uses `shared:protocol` (the Tool speaks no network protocol) or
 *   `shared:test-fixtures` in production code.
 */
object ToolArchitectureRules : ArchitectureRules {
    private const val ROOT = SharedArchitectureRules.ROOT_PACKAGE

    override val family = "tool"

    override val source = "tool/AGENTS.md"

    val modules: List<String> = listOf("world", "core", "app")

    /** Projects each module's production classpath may resolve, transitively. */
    val allowedProjects: Map<String, Set<String>> =
        mapOf(
            "world" to setOf(":shared:domain"),
            "core" to setOf(":tool:world", ":shared:domain", ":shared:sidecar"),
            "app" to setOf(":tool:core", ":tool:world", ":shared:domain", ":shared:sidecar"),
        )

    /** Root package of a tool module. */
    fun packageOf(module: String): String = "$ROOT.tool.$module"

    private fun mayUseJavaFx(module: String) = module == "app"

    override fun artifactViolations(module: String, group: String, displayName: String): List<String> {
        requireModule(module)
        val banned =
            SharedArchitectureRules.bannedGroups.filter { mayUseJavaFx(module).not() || it != "org.openjfx" }
        return if (banned.any { group == it || group.startsWith("$it.") }) {
            listOf("tool:$module resolves banned artifact $displayName")
        } else {
            emptyList()
        }
    }

    override fun projectEdgeViolations(module: String, projectPath: String): List<String> {
        requireModule(module)
        if (projectPath == ":tool:$module") return emptyList()
        return if (projectPath in allowedProjects.getValue(module)) {
            emptyList()
        } else {
            listOf("tool:$module production code may not depend on project $projectPath")
        }
    }

    override fun packageEdgeViolations(module: String, fromPackage: String, toPackage: String): List<String> {
        requireModule(module)
        val violations = mutableListOf<String>()
        val platform =
            SharedArchitectureRules.bannedPackagePrefixes
                .map { it.removeSuffix(".") }
                .filter { mayUseJavaFx(module).not() || it != "javafx" }
        platform.firstOrNull { isWithin(toPackage, it) }
            ?.let { violations += "$fromPackage -> $toPackage (package '$it' is forbidden in tool:$module)" }

        val forbidden = mutableListOf("$ROOT.protocol", "$ROOT.fixtures", "$ROOT.sidecar.internal")
        when (module) {
            "world" -> forbidden += listOf(packageOf("core"), packageOf("app"), "$ROOT.sidecar")
            "core" -> forbidden += packageOf("app")
            "app" -> forbidden += packageOf("world")
        }
        forbidden.firstOrNull { isWithin(toPackage, it) }
            ?.let { violations += "$fromPackage -> $toPackage (tool:$module may not depend on '$it')" }
        return violations
    }

    private fun requireModule(module: String) = require(module in modules) { "Unknown tool module '$module'" }
}
