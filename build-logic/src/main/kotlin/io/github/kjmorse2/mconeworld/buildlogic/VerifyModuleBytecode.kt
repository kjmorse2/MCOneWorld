package io.github.kjmorse2.mconeworld.buildlogic

import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Architecture test over compiled bytecode: runs the JDK's `jdeps` on a module's main classes and
 * fails on any package-level reference its rule family ([ArchitectureRules]) forbids. This catches
 * references that never appear as `import` statements.
 *
 * `jdeps` is obtained through [ToolProvider] from the JDK running Gradle, which must be a full JDK
 * (the repository requires JDK 25; see README.md).
 */
abstract class VerifyModuleBytecode : DefaultTask() {
    /** Rule family: `shared` or `tool` (see [ArchitectureRules.named]). */
    @get:Input abstract val ruleFamily: Property<String>

    @get:Input abstract val moduleName: Property<String>

    /** The Java release the classes target; multi-release jars are analysed as that release. */
    @get:Input abstract val release: Property<Int>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classesDirs: ConfigurableFileCollection

    @get:Classpath abstract val classpath: ConfigurableFileCollection

    @get:OutputFile abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val module = moduleName.get()
        val rules = ArchitectureRules.named(ruleFamily.get())
        val dirs = classesDirs.files.filter { it.isDirectory && (it.list()?.isNotEmpty() ?: false) }
        if (dirs.isEmpty()) {
            report.get().asFile.writeText("${rules.family}:$module has no compiled classes\n")
            return
        }
        val jdeps =
            ToolProvider.findFirst("jdeps").orElseThrow {
                GradleException("jdeps is unavailable: run Gradle on a full JDK 25, not a JRE.")
            }
        // Dependencies such as jackson-core are multi-release jars; analyse them as the release the
        // module targets (21 for shared modules, ADR 0002; 25 for the Tool).
        val args = mutableListOf("-verbose:package", "--multi-release", release.get().toString())
        val cp = classpath.files.filter { it.exists() }
        if (cp.isNotEmpty()) {
            args += listOf("--class-path", cp.joinToString(java.io.File.pathSeparator))
        }
        args += dirs.map { it.absolutePath }

        val out = StringWriter()
        val err = StringWriter()
        val exit = jdeps.run(PrintWriter(out), PrintWriter(err), *args.toTypedArray())
        if (exit != 0) {
            throw GradleException("jdeps failed with exit code $exit:\n$err\n$out")
        }

        val edges = ArchitectureRules.parseJdepsPackageEdges(out.toString())
        val violations = edges.flatMap { (from, to) -> rules.packageEdgeViolations(module, from, to) }.toSortedSet()
        report.get().asFile.writeText(
            buildString {
                appendLine("${rules.family}:$module bytecode package references (jdeps -verbose:package)")
                edges.map { (from, to) -> "  $from -> $to" }.toSortedSet().forEach { appendLine(it) }
                appendLine(if (violations.isEmpty()) "OK" else "VIOLATIONS:")
                violations.forEach { appendLine("  $it") }
            },
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "${rules.family} bytecode references forbidden packages (see ${rules.source}):\n  " + violations.joinToString("\n  "),
            )
        }
    }
}
