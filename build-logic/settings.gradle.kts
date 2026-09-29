// Included build that provides the mconeworld.* convention plugins to the root build.
// It shares the root version catalog so every version is declared exactly once.

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        gradlePluginPortal() // hosts gradle-errorprone-plugin and kotlin-dsl dependencies
        mavenCentral()
    }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
