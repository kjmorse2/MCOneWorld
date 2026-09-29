// Root project: aggregation only. All Java conventions live in build-logic/ (ADR 0002).

val sharedModules = listOf(":shared:domain", ":shared:sidecar", ":shared:protocol", ":shared:test-fixtures")

tasks.register("sharedCheck") {
    group = "verification"
    description =
        "Runs every shared-module check (tests, formatting, Error Prone/NullAway, architecture guards) " +
            "plus build-logic's own tests. This is the CI entry point."
    dependsOn(sharedModules.map { "$it:check" })
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
