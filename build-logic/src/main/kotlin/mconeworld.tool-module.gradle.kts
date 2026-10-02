// Applied by every project under tool/ (MCOneWorld Tool, ADR 0009). Same baseline as the shared
// modules (mconeworld.java-conventions: Spotless, Error Prone, NullAway, locking, reproducible
// archives), but the Tool is an application on Java 25, and tool/AGENTS.md's module rules are
// enforced over the resolved graph and the compiled bytecode.

import io.github.kjmorse2.mconeworld.buildlogic.VerifyModuleBytecode
import io.github.kjmorse2.mconeworld.buildlogic.VerifyModuleDependencies

plugins { id("mconeworld.java-conventions") }

base.archivesName = "mconeworld-tool-${project.name}"

// Registered after java-conventions' action, so this release wins: the Tool runs on Java 25
// (the shared jars it uses stay Java 21 bytecode).
tasks.withType<JavaCompile>().configureEach { options.release = 25 }

val toolModule = project.name

val verifyToolDependencies =
    tasks.register<VerifyModuleDependencies>("verifyToolDependencies") {
        group = "verification"
        description = "Fails if production classpaths resolve banned or out-of-direction dependencies."
        ruleFamily = "tool"
        moduleName = toolModule
        productionRoots.add(configurations.compileClasspath.flatMap { it.incoming.resolutionResult.rootComponent })
        productionRoots.add(configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent })
        report = layout.buildDirectory.file("reports/architecture/dependencies.txt")
    }

val verifyToolBytecode =
    tasks.register<VerifyModuleBytecode>("verifyToolBytecode") {
        group = "verification"
        description = "Runs jdeps on main classes and fails on forbidden package references."
        ruleFamily = "tool"
        moduleName = toolModule
        release = 25
        classesDirs.from(sourceSets.main.map { it.output.classesDirs })
        classpath.from(configurations.compileClasspath)
        report = layout.buildDirectory.file("reports/architecture/bytecode.txt")
    }

tasks.named("check") { dependsOn(verifyToolDependencies, verifyToolBytecode) }
