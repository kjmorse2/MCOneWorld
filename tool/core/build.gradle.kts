// Headless services of MCOneWorld Tool: session, init, reconcile, backup, seasons (tool/AGENTS.md).
// May depend on :tool:world, :shared:domain, :shared:sidecar; never on JavaFX.
plugins { id("mconeworld.tool-module") }

dependencies {
    api(project(":tool:world"))
    api(project(":shared:domain"))
    api(project(":shared:sidecar"))
    testImplementation(project(":shared:test-fixtures"))
}
