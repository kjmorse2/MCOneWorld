package io.github.kjmorse2.mconeworld.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Proves the rule table rejects every forbidden edge from shared/AGENTS.md and allows the rest. */
class SharedArchitectureRulesTest {
    private val root = SharedArchitectureRules.ROOT_PACKAGE

    @Test
    fun `platform packages are rejected in every module`() {
        for (module in SharedArchitectureRules.allowedSharedDependencies.keys) {
            for (pkg in listOf("net.minecraft.world", "org.bukkit", "io.papermc.paper.event", "net.fabricmc.api", "javafx.scene", "net.querz.nbt", "net.kyori.adventure.text", "com.mojang.serialization")) {
                assertTrue(
                    SharedArchitectureRules.packageEdgeViolations(module, "$root.x", pkg).isNotEmpty(),
                    "$module -> $pkg must be rejected",
                )
            }
        }
    }

    @Test
    fun `lookalike packages are not rejected`() {
        // Prefix matching is on whole package segments: 'javafxish' is not 'javafx'.
        assertEquals(emptyList<String>(), SharedArchitectureRules.packageEdgeViolations("domain", "$root.domain", "javafxish.util"))
        assertEquals(emptyList<String>(), SharedArchitectureRules.packageEdgeViolations("domain", "$root.domain", "java.lang"))
    }

    @Test
    fun `shared dependency direction matches shared AGENTS md`() {
        fun violates(from: String, to: String) =
            SharedArchitectureRules.packageEdgeViolations(from, SharedArchitectureRules.packageOf(from), SharedArchitectureRules.packageOf(to) + ".x").isNotEmpty()

        assertTrue(violates("domain", "sidecar"))
        assertTrue(violates("domain", "protocol"))
        assertTrue(violates("domain", "test-fixtures"))
        assertTrue(violates("sidecar", "protocol"))
        assertTrue(violates("protocol", "sidecar"))
        assertTrue(violates("sidecar", "test-fixtures"))
        assertTrue(violates("protocol", "test-fixtures"))
        assertTrue(!violates("sidecar", "domain"))
        assertTrue(!violates("protocol", "domain"))
        assertTrue(!violates("test-fixtures", "sidecar"))
        assertTrue(!violates("test-fixtures", "protocol"))
    }

    @Test
    fun `domain package prefix does not swallow unrelated packages`() {
        // '...domainx' is not the domain module.
        assertEquals(emptyList<String>(), SharedArchitectureRules.packageEdgeViolations("sidecar", "$root.sidecar", "$root.sidecarx"))
    }

    @Test
    fun `project edges`() {
        assertEquals(emptyList<String>(), SharedArchitectureRules.projectEdgeViolations("sidecar", ":shared:domain"))
        assertEquals(emptyList<String>(), SharedArchitectureRules.projectEdgeViolations("sidecar", ":shared:sidecar"))
        assertTrue(SharedArchitectureRules.projectEdgeViolations("sidecar", ":shared:test-fixtures").isNotEmpty())
        assertTrue(SharedArchitectureRules.projectEdgeViolations("domain", ":shared:sidecar").isNotEmpty())
        assertTrue(SharedArchitectureRules.projectEdgeViolations("protocol", ":offline-tool").isNotEmpty())
    }

    @Test
    fun `banned groups match exact group and subgroups only`() {
        assertTrue(SharedArchitectureRules.isBannedGroup("io.papermc.paper"))
        assertTrue(SharedArchitectureRules.isBannedGroup("org.openjfx"))
        assertTrue(!SharedArchitectureRules.isBannedGroup("org.openjfxish"))
        assertTrue(!SharedArchitectureRules.isBannedGroup("tools.jackson.core"))
    }

    @Test
    fun `parses jdeps package output`() {
        val output =
            """
            classes -> java.base
               io.github.kjmorse2.mconeworld.domain               -> java.lang                                          java.base
               io.github.kjmorse2.mconeworld.domain               -> net.minecraft.world                                not found
            """.trimIndent()
        assertEquals(
            listOf("io.github.kjmorse2.mconeworld.domain" to "java.lang", "io.github.kjmorse2.mconeworld.domain" to "net.minecraft.world"),
            SharedArchitectureRules.parseJdepsPackageEdges(output),
        )
    }
}
