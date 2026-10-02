package io.github.kjmorse2.mconeworld.buildlogic

/**
 * A family of module-dependency rules checked by [VerifyModuleDependencies] (the resolved Gradle
 * graph) and [VerifyModuleBytecode] (`jdeps` over compiled classes). Implementations are pure and
 * free of Gradle types so they can be unit tested directly.
 */
interface ArchitectureRules {
    /** Name of the module family in messages, e.g. `shared`. */
    val family: String

    /** Where the rules are written down for humans, e.g. `shared/AGENTS.md`. */
    val source: String

    /** Violations for a resolved external artifact in [module]'s production graph. */
    fun artifactViolations(module: String, group: String, displayName: String): List<String>

    /** Violations for a resolved project dependency of [module]'s production graph. */
    fun projectEdgeViolations(module: String, projectPath: String): List<String>

    /** Violations for one package-level bytecode edge in [module]'s main classes. */
    fun packageEdgeViolations(module: String, fromPackage: String, toPackage: String): List<String>

    companion object {
        /** The rule family a convention plugin selects by name. */
        fun named(name: String): ArchitectureRules =
            when (name) {
                SharedArchitectureRules.family -> SharedArchitectureRules
                ToolArchitectureRules.family -> ToolArchitectureRules
                else -> error("Unknown architecture rule family '$name'")
            }

        private val jdepsEdge = Regex("""^\s+(\S+)\s+->\s+(\S+)\s+.*$""")

        /** Parses `jdeps -verbose:package` output into `(fromPackage, toPackage)` pairs. */
        fun parseJdepsPackageEdges(output: String): List<Pair<String, String>> =
            output.lineSequence().mapNotNull { jdepsEdge.matchEntire(it) }.map { it.groupValues[1] to it.groupValues[2] }.toList()

        /** Whether [pkg] is [prefix] itself or one of its subpackages (whole segments only). */
        fun isWithin(pkg: String, prefix: String): Boolean = pkg == prefix || pkg.startsWith("$prefix.")
    }
}
