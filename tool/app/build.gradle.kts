// The JavaFX UI of MCOneWorld Tool (tool/AGENTS.md). Depends on :tool:core only; the only module
// allowed to use JavaFX. Run it with ./gradlew :tool:app:run
plugins {
    id("mconeworld.tool-module")
    application
}

val libs = the<VersionCatalogsExtension>().named("libs")
val javafxModules = listOf("javafx-base", "javafx-graphics", "javafx-controls")

/**
 * JavaFX publishes one jar per platform, selected by classifier. Maven profiles pick it by OS,
 * which Gradle does not evaluate, so the classifier is chosen here and the artifacts are added
 * non-transitively.
 */
fun javafxClassifier(): String {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    return when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("linux") -> if (arm) "linux-aarch64" else "linux"
        else -> throw GradleException("No JavaFX build for OS '$os'")
    }
}

/** Every platform the installers target; resolved so verification metadata covers all of them. */
val javafxPlatforms = listOf("linux", "linux-aarch64", "mac", "mac-aarch64", "win")

configurations.create("javafxAllPlatforms") {
    isCanBeConsumed = false
    description = "JavaFX for every platform: keeps locks and verification metadata OS-independent."
}

dependencies {
    implementation(project(":tool:core"))
    for (module in javafxModules) {
        val coordinates = libs.findLibrary(module).get().get()
        implementation("${coordinates.module}:${coordinates.version}:${javafxClassifier()}") { isTransitive = false }
        for (platform in javafxPlatforms) {
            "javafxAllPlatforms"("${coordinates.module}:${coordinates.version}:$platform") { isTransitive = false }
        }
    }
    testImplementation(project(":shared:test-fixtures"))
    // Monocle runs JavaFX headless in tests: no display needed, locally or in CI.
    testRuntimeOnly(libs.findLibrary("monocle").get()) { isTransitive = false }
}

application {
    mainClass = "io.github.kjmorse2.mconeworld.tool.app.Main"
    applicationName = "mconeworld-tool"
    // JavaFX loads its native libraries from the class path (an unnamed module).
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test>().configureEach {
    // UI tests run without a display: Monocle's headless glass platform and software rendering.
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("prism.order", "sw")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
