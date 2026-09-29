plugins { `kotlin-dsl` }

dependencies {
    // Plugins applied by id from the precompiled convention scripts must be on this classpath.
    implementation(libs.plugin.spotless)
    implementation(libs.plugin.errorprone)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyLocking { lockAllConfigurations() }

tasks.test { useJUnitPlatform() }
