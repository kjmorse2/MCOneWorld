// Root build for the MCOneWorld monorepo.
//
// The shared modules and MCOneWorld Tool (tool/) are part of this build. `offline-tool/` (the
// imported MCA Selector subtree) stays a separate Gradle build with its own wrapper: the Tool
// vendors the pieces it needs instead of building it (ADR 0009). Never include it here.

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
    // MCOneWorld Tool (ADR 0009, tool/AGENTS.md)
    ":tool:world",
    ":tool:core",
    ":tool:app",
)
