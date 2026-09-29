package io.github.kjmorse2.mconeworld.buildlogic

/**
 * Pure, Gradle-independent statement of the shared-module dependency rules from `shared/AGENTS.md`.
 *
 * Two independent checks consume these rules so a violation is caught whichever way it enters:
 *  - [VerifySharedDependencyGraph] inspects the *resolved Gradle graph* (declared dependencies).
 *  - [VerifySharedBytecode] inspects *compiled bytecode* via `jdeps` (actual package references,
 *    including fully qualified names and reflection-free constant-pool references that an
 *    import-statement scan would miss).
 *
 * Keep this file free of Gradle types so it can be unit tested directly.
 */
object SharedArchitectureRules {
    const val ROOT_PACKAGE = "io.github.kjmorse2.mconeworld"

    /** Maven groups that no shared module may resolve, directly or transitively. */
    val bannedGroups: List<String> =
        listOf(
            "io.papermc", // Paper API, paperweight
            "com.destroystokyo.paper", // legacy Paper API
            "org.bukkit",
            "org.spigotmc",
            "net.md-5", // BungeeCord chat, shipped with Spigot/Paper
            "net.fabricmc",
            "com.mojang",
            "net.minecraft",
            "net.kyori", // Adventure
            "org.openjfx", // JavaFX
            "net.querz", // MCA Selector and Querz NBT
            "com.github.Querz", // Querz NBT via JitPack
            "org.parchmentmc", // mappings
            "net.neoforged",
        )

    /** Java package prefixes that no shared production class may reference. */
    val bannedPackagePrefixes: List<String> =
        listOf(
            "net.minecraft.",
            "com.mojang.",
            "org.bukkit.",
            "io.papermc.",
            "com.destroystokyo.paper.",
            "org.spigotmc.",
            "net.md_5.",
            "net.fabricmc.",
            "net.kyori.",
            "javafx.",
            "net.querz.",
        )

    /** Shared module name -> shared module names its *production* code may depend on. */
    val allowedSharedDependencies: Map<String, Set<String>> =
        mapOf(
            "domain" to emptySet(),
            "sidecar" to setOf("domain"),
            "protocol" to setOf("domain"),
            // test-fixtures is test support; it may see every production module.
            "test-fixtures" to setOf("domain", "sidecar", "protocol"),
        )

    /** Root package of each shared module's production code. */
    fun packageOf(module: String): String =
        when (module) {
            "test-fixtures" -> "$ROOT_PACKAGE.fixtures"
            else -> "$ROOT_PACKAGE.$module"
        }

    fun isBannedGroup(group: String): Boolean =
        bannedGroups.any { group == it || group.startsWith("$it.") }

    /**
     * Returns human-readable violations for one package-level edge `fromPackage -> toPackage`
     * found in [module]'s compiled classes, or an empty list if the edge is allowed.
     */
    fun packageEdgeViolations(module: String, fromPackage: String, toPackage: String): List<String> {
        val violations = mutableListOf<String>()
        val target = "$toPackage."
        bannedPackagePrefixes
            .firstOrNull { target.startsWith(it) }
            ?.let { violations += "$fromPackage -> $toPackage (platform package '${it.removeSuffix(".")}' is forbidden in shared modules)" }

        val allowed = allowedSharedDependencies[module] ?: error("Unknown shared module '$module'")
        for (other in allowedSharedDependencies.keys) {
            if (other == module || other in allowed) continue
            val otherPackage = packageOf(other)
            if (toPackage == otherPackage || toPackage.startsWith("$otherPackage.")) {
                violations += "$fromPackage -> $toPackage (shared:$module may not depend on shared:$other)"
            }
        }
        return violations
    }

    /** Violations for a resolved project dependency `:shared:<module> -> <projectPath>`. */
    fun projectEdgeViolations(module: String, projectPath: String): List<String> {
        if (projectPath == ":shared:$module") return emptyList()
        val allowed = allowedSharedDependencies[module] ?: error("Unknown shared module '$module'")
        val other = projectPath.removePrefix(":shared:")
        return if (projectPath.startsWith(":shared:") && other in allowed) {
            emptyList()
        } else {
            listOf("shared:$module production code may not depend on project $projectPath")
        }
    }

    private val jdepsEdge = Regex("""^\s+(\S+)\s+->\s+(\S+)\s+.*$""")

    /** Parses `jdeps -verbose:package` output into `(fromPackage, toPackage)` pairs. */
    fun parseJdepsPackageEdges(output: String): List<Pair<String, String>> =
        output.lineSequence().mapNotNull { jdepsEdge.matchEntire(it) }.map { it.groupValues[1] to it.groupValues[2] }.toList()
}
