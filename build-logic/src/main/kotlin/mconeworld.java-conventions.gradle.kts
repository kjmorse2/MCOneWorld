// Baseline for every MCOneWorld Java library: pinned toolchain, strict compilation, static
// analysis, formatting, deterministic tests, dependency locking, and reproducible archives.
// Decisions are recorded in docs/decisions/0002-build-and-test-toolchain.md.

import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    jacoco
    id("com.diffplug.spotless")
    id("net.ltgt.errorprone")
}

val libs = the<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

fun version(alias: String) = libs.findVersion(alias).get().requiredVersion

group = "io.github.kjmorse2.mconeworld"

java {
    // Build and test with JDK 25; emit Java 21 bytecode so the Java 21 offline tool can consume
    // the shared libraries (ADR 0002, Q3).
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

dependencies {
    // Nullness annotations are part of the public API contract; `api` (not compileOnly) so that
    // consumers compiling with -Xlint:all -Werror do not fail on missing annotation classes.
    api(lib("jspecify"))

    errorprone(lib("errorprone-core"))
    errorprone(lib("nullaway"))

    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testImplementation(lib("assertj-core"))
    testImplementation(lib("jqwik"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    // Warnings are errors; never silence a lint category globally to get a build green.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror", "-parameters"))
    options.errorprone {
        disableWarningsInGeneratedCode = true
        // NullAway checks code inside @NullMarked scopes (every shared package-info is @NullMarked).
        check("NullAway", if (name.contains("Test")) CheckSeverity.OFF else CheckSeverity.ERROR)
        option("NullAway:OnlyNullMarked", "true")
        option("NullAway:JSpecifyMode", "true")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }
    // Tests must not depend on the machine's timezone or locale (shared/AGENTS.md "Test quality").
    systemProperty("user.timezone", "UTC")
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
    systemProperty("file.encoding", "UTF-8")
    // jqwik remembers failing seeds here so a failure reproduces on the next run.
    systemProperty("jqwik.database", "build/jqwik-database")
    testLogging { events("failed", "skipped") ; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    finalizedBy(tasks.named("jacocoTestReport"))
}

// Coverage is a diagnostic only; there is deliberately no threshold gate (shared/AGENTS.md).
tasks.named<JacocoReport>("jacocoTestReport") {
    reports { xml.required = false; html.required = true }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat(version("google-java-format"))
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
        // Spotless does not touch package-info.java; those files carry the same line by hand.
        licenseHeader("// SPDX-License-Identifier: MIT\n")
    }
}

dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

// Reproducible archives: no timestamps, stable entry order, no machine-specific manifest data.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named<Jar>("jar") {
    // MIT requires the copyright and permission notice to travel with every copy.
    from(rootDir.resolve("LICENSE")) { into("META-INF") }
    manifest {
        attributes(
            "Implementation-Title" to "${project.group}:${base.archivesName.get()}",
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// Resolves every lockable configuration; used with --write-locks to refresh gradle.lockfile.
tasks.register("resolveAndLockAll") {
    group = "help"
    description = "Resolves all configurations; run with --write-locks to update dependency lockfiles."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time by design.")
    doFirst { require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" } }
    doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
}
