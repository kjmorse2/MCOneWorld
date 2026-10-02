// World-file access for MCOneWorld Tool: read-only inspection plus the End archive (ADR 0010).
// May depend on :shared:domain only (tool/AGENTS.md §2); enforced by verifyToolDependencies.
plugins { id("mconeworld.tool-module") }

dependencies {
    api(project(":shared:domain"))
    testImplementation(project(":shared:test-fixtures"))
}
