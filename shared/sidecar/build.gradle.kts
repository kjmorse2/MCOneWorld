// May depend on :shared:domain only (shared/AGENTS.md); enforced by verifySharedDependencies.
plugins { id("mconeworld.shared-module") }

dependencies {
    api(project(":shared:domain"))
    // Internal JSON parsing (ADR 0003). Never exposed in the public API; see sidecar/README.md.
    implementation(libs.jackson.core)
    testImplementation(project(":shared:test-fixtures"))
}
