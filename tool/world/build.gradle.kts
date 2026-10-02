// World-file access for MCOneWorld Tool: read-only inspection plus the End archive (ADR 0010).
// May depend on :shared:domain only (tool/AGENTS.md §2); enforced by verifyToolDependencies.
plugins {
    id("mconeworld.tool-module")
    // SyntheticWorld and a test-only NBT writer, shared with tool:core tests.
    `java-test-fixtures`
}

dependencies {
    api(project(":shared:domain"))
    testImplementation(project(":shared:test-fixtures"))
    testFixturesApi(project(":shared:domain"))
}

// Real worlds stay local (TestSaves/ is git-ignored). Tests tagged "local-worlds" read them
// read-only and are excluded from check; run them on demand:
//   ./gradlew :tool:world:localWorldTest -PlocalWorlds=TestSaves
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("local-worlds") } }

val localWorlds = providers.gradleProperty("localWorlds").map { rootDir.resolve(it).absolutePath }

tasks.register<Test>("localWorldTest") {
    group = "verification"
    description = "Reads the world directories under -PlocalWorlds=<dir> read-only and checks the reader on them."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("local-worlds") }
    systemProperty("mconeworld.localWorlds", localWorlds.getOrElse(""))
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
