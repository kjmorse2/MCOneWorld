// Root build for the MCOneWorld monorepo.
//
// Only the shared modules are part of this build today. `offline-tool/` (the imported MCA Selector
// subtree) intentionally remains a separate Gradle build with its own wrapper until the deferred
// "MCA Selector relationship" decision is made (ADR 0002, root AGENTS.md §22). Do not include it
// here without that decision.

pluginManagement {
    // Convention plugins (mconeworld.*) live in build-logic/.
    includeBuild("build-logic")
    repositories { gradlePluginPortal() }
}

dependencyResolutionManagement {
    // Modules may not declare their own repositories (ADR 0002).
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories { mavenCentral() }
}

rootProject.name = "mconeworld"

include(
    ":shared:domain",
    ":shared:sidecar",
    ":shared:protocol",
    ":shared:test-fixtures",
)
