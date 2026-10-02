// Root project: aggregation only. All Java conventions live in build-logic/ (ADR 0002).

val sharedModules = listOf(":shared:domain", ":shared:sidecar", ":shared:protocol", ":shared:test-fixtures")
val toolModules = listOf(":tool:world", ":tool:core", ":tool:app")

tasks.register("sharedCheck") {
    group = "verification"
    description =
        "Runs every shared-module check (tests, formatting, Error Prone/NullAway, architecture guards) " +
            "plus build-logic's own tests. This is the CI entry point."
    dependsOn(sharedModules.map { "$it:check" })
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}

tasks.register("toolCheck") {
    group = "verification"
    description =
        "Runs every MCOneWorld Tool check (tests including headless UI smoke tests, formatting, Error " +
            "Prone/NullAway, tool architecture guards). See tool/AGENTS.md."
    dependsOn(toolModules.map { "$it:check" })
}

// Machine-readable summary of the last shared test run and the built jars (Prompt 11 audit): per
// module and test task, the counts from the JUnit XML reports, plus each jar's SHA-256. Run it after
// the checks: ./gradlew sharedCheck assemble sharedTestSummary
tasks.register("sharedTestSummary") {
    group = "verification"
    description = "Writes build/reports/shared-test-summary.json from the shared modules' test reports and jars."
    mustRunAfter("sharedCheck")
    val moduleDirs = sharedModules.associateWith { file(it.removePrefix(":").replace(':', '/')) }
    val output = layout.buildDirectory.file("reports/shared-test-summary.json")
    val root = rootDir
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val modules = mutableListOf<String>()
        val total = longArrayOf(0, 0, 0, 0)
        for ((name, dir) in moduleDirs.toSortedMap()) {
            val tasks = mutableListOf<String>()
            val results = dir.resolve("build/test-results")
            for (taskDir in (results.listFiles() ?: emptyArray()).filter { it.isDirectory }.sortedBy { it.name }) {
                val counts = longArrayOf(0, 0, 0, 0)
                for (xml in (taskDir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".xml") }) {
                    val suite = factory.newDocumentBuilder().parse(xml).documentElement
                    listOf("tests", "skipped", "failures", "errors").forEachIndexed { i, a ->
                        counts[i] += suite.getAttribute(a).ifEmpty { "0" }.toLong()
                    }
                }
                counts.forEachIndexed { i, c -> total[i] += c }
                tasks += "{\"task\":${quote(taskDir.name)},\"tests\":${counts[0]},\"skipped\":${counts[1]}," +
                    "\"failures\":${counts[2]},\"errors\":${counts[3]}}"
            }
            val jars = (dir.resolve("build/libs").listFiles() ?: emptyArray()).filter { it.name.endsWith(".jar") }
                .sortedBy { it.name }
                .map { jar ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
                    "{\"file\":${quote(jar.name)},\"sha256\":${quote(digest.joinToString("") { "%02x".format(it) })}}"
                }
            modules += "{\"module\":${quote(name)},\"testTasks\":[${tasks.joinToString(",")}],\"jars\":[${jars.joinToString(",")}]}"
        }
        val text = "{\"format\":\"mconeworld-shared-test-summary\",\"version\":1," +
            "\"totals\":{\"tests\":${total[0]},\"skipped\":${total[1]},\"failures\":${total[2]},\"errors\":${total[3]}}," +
            "\"modules\":[${modules.joinToString(",")}]}\n"
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(text)
        println("Wrote ${file.relativeTo(root)}: ${total[0]} tests, ${total[2]} failures, ${total[3]} errors")
    }
}
