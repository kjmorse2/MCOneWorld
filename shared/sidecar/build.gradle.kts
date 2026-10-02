// May depend on :shared:domain only (shared/AGENTS.md); enforced by verifySharedDependencies.
plugins { id("mconeworld.shared-module") }

dependencies {
    api(project(":shared:domain"))
    // Internal JSON parsing (ADR 0003). Never exposed in the public API; see sidecar/README.md.
    implementation(libs.jackson.core)
    testImplementation(project(":shared:test-fixtures"))
}

// ---------------------------------------------------------------------------------------------
// Prompt 08 spike (ADR 0007, Proposed): chunk-index candidate encodings and the benchmark harness.
// The `bench` source set is never packaged, never on a production classpath (so the architecture
// guards, which inspect main classpaths, never see it), and is removed or promoted once ADR 0007
// is decided. See src/bench/README.md.
// ---------------------------------------------------------------------------------------------
val bench: SourceSet =
    sourceSets.create("bench") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }

dependencies {
    "benchImplementation"(project(":shared:test-fixtures"))
    "benchImplementation"(libs.spike.sqlite.jdbc)
    "benchImplementation"(libs.spike.roaringbitmap)
    "benchImplementation"(libs.spike.h2.mvstore)
    "benchImplementation"(platform(libs.junit.bom))
    "benchImplementation"(libs.junit.jupiter)
    "benchImplementation"(libs.assertj.core)
    "benchImplementation"(libs.jqwik)
    "benchRuntimeOnly"(libs.junit.platform.launcher)
}

// sqlite-jdbc loads a native library; JDK 25 warns unless native access is granted explicitly.
val benchJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")

// Differential tests: every candidate must answer exactly like the reference index before any of
// its numbers mean anything. Part of `check` while the spike exists.
val benchTest =
    tasks.register<Test>("benchTest") {
        group = "verification"
        description = "Differential and crash tests of the Prompt 08 chunk-index candidates."
        testClassesDirs = bench.output.classesDirs
        classpath = bench.runtimeClasspath
        jvmArgs(benchJvmArgs)
    }

tasks.named("check") { dependsOn(benchTest) }

// ./gradlew :shared:sidecar:benchChunkIndex -Pbench.args="--datasets friend-server --candidates all"
tasks.register<JavaExec>("benchChunkIndex") {
    group = "benchmark"
    description = "Runs the Prompt 08 chunk-index benchmarks (each case in its own JVM)."
    classpath = bench.runtimeClasspath
    mainClass = "io.github.kjmorse2.mconeworld.sidecar.index.spike.bench.BenchmarkDriver"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    jvmArgs(benchJvmArgs)
    workingDir = rootDir
    args(
        (providers.gradleProperty("bench.args").orNull ?: "--help").split(" ").filter { it.isNotBlank() },
    )
}

// ./gradlew -q :shared:sidecar:benchSummarize -Pbench.args="docs/benchmarks/chunk-index/raw/<run>.jsonl"
tasks.register<JavaExec>("benchSummarize") {
    group = "benchmark"
    description = "Prints Markdown budget verdicts and tables for chunk-index result files."
    classpath = bench.runtimeClasspath
    mainClass = "io.github.kjmorse2.mconeworld.sidecar.index.spike.bench.Summarize"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    workingDir = rootDir
    args((providers.gradleProperty("bench.args").orNull ?: "").split(" ").filter { it.isNotBlank() })
}

// Bounded-memory regression (Prompt 09): a million-record chunk index must be built, scanned,
// updated, verified, and reopened inside 128 MiB. Tagged "memory" and kept out of `test`.
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("memory") } }

val indexMemoryTest =
    tasks.register<Test>("indexMemoryTest") {
        group = "verification"
        description = "Runs the chunk index's bounded-memory test in a JVM capped at 128 MiB."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags("memory") }
        maxHeapSize = "128m"
    }

tasks.named("check") { dependsOn(indexMemoryTest) }
