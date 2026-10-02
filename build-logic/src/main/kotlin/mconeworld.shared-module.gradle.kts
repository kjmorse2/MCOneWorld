// Applied by every project under shared/. Adds the architecture checks that enforce
// shared/AGENTS.md: dependency direction between shared modules, no platform dependencies, and
// no production dependency on :shared:test-fixtures.

import io.github.kjmorse2.mconeworld.buildlogic.VerifyModuleBytecode
import io.github.kjmorse2.mconeworld.buildlogic.VerifyModuleDependencies

plugins { id("mconeworld.java-conventions") }

base.archivesName = "mconeworld-shared-${project.name}"

val sharedModule = project.name

val verifySharedDependencies =
    tasks.register<VerifyModuleDependencies>("verifySharedDependencies") {
        ruleFamily = "shared"
        group = "verification"
        description = "Fails if production classpaths resolve banned or out-of-direction dependencies."
        moduleName = sharedModule
        productionRoots.add(configurations.compileClasspath.flatMap { it.incoming.resolutionResult.rootComponent })
        productionRoots.add(configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent })
        report = layout.buildDirectory.file("reports/architecture/dependencies.txt")
    }

val verifySharedBytecode =
    tasks.register<VerifyModuleBytecode>("verifySharedBytecode") {
        ruleFamily = "shared"
        release = 21
        group = "verification"
        description = "Runs jdeps on main classes and fails on forbidden package references."
        moduleName = sharedModule
        classesDirs.from(sourceSets.main.map { it.output.classesDirs })
        classpath.from(configurations.compileClasspath)
        report = layout.buildDirectory.file("reports/architecture/bytecode.txt")
    }

tasks.named("check") { dependsOn(verifySharedDependencies, verifySharedBytecode) }
