// May depend on :shared:domain only (shared/AGENTS.md); enforced by verifySharedDependencies.
plugins { id("mconeworld.shared-module") }

dependencies {
    api(project(":shared:domain"))
    testImplementation(project(":shared:test-fixtures"))
}
