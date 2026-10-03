// Headless services of MCOneWorld Tool: session, init, reconcile, backup, seasons (tool/AGENTS.md).
// May depend on :tool:world, :shared:domain, :shared:sidecar; never on JavaFX.
plugins { id("mconeworld.tool-module") }

dependencies {
    api(project(":tool:world"))
    api(project(":shared:domain"))
    api(project(":shared:sidecar"))
    testImplementation(project(":shared:test-fixtures"))
    testImplementation(testFixtures(project(":tool:world")))
}

// Real worlds stay local (TestSaves/ is git-ignored). Tests tagged "local-worlds" read them
// read-only (sidecars go to temporary directories) and are excluded from check:
//   ./gradlew :tool:core:localWorldTest -PlocalWorlds=TestSaves
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("local-worlds") } }

val localWorlds = providers.gradleProperty("localWorlds").map { rootDir.resolve(it).absolutePath }

tasks.register<Test>("localWorldTest") {
    group = "verification"
    description = "Initializes sidecars (in temporary directories) for the worlds under -PlocalWorlds=<dir>."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("local-worlds") }
    systemProperty("mconeworld.localWorlds", localWorlds.getOrElse(""))
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
