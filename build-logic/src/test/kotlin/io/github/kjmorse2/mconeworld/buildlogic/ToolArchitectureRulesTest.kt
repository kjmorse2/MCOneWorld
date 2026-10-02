package io.github.kjmorse2.mconeworld.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Proves the tool rule table enforces tool/AGENTS.md §2 and the shared rules keep the Tool out. */
class ToolArchitectureRulesTest {
    private val root = SharedArchitectureRules.ROOT_PACKAGE
    private val rules = ToolArchitectureRules

    private fun violates(module: String, to: String) = rules.packageEdgeViolations(module, rules.packageOf(module), to).isNotEmpty()

    @Test
    fun `only the app may use JavaFX`() {
        assertTrue(violates("world", "javafx.scene"))
        assertTrue(violates("core", "javafx.application"))
        assertTrue(!violates("app", "javafx.scene.control"))
        assertTrue(rules.artifactViolations("core", "org.openjfx", "org.openjfx:javafx-base:25").isNotEmpty())
        assertEquals(emptyList<String>(), rules.artifactViolations("app", "org.openjfx", "org.openjfx:javafx-base:25"))
    }

    @Test
    fun `platform and MCA Selector packages are rejected everywhere`() {
        for (module in rules.modules) {
            for (pkg in listOf("net.minecraft.world", "org.bukkit", "net.fabricmc.api", "net.querz.mcaselector.io", "com.mojang.serialization")) {
                assertTrue(violates(module, pkg), "$module -> $pkg must be rejected")
            }
            assertTrue(rules.artifactViolations(module, "net.querz", "net.querz:nbt:6.1").isNotEmpty())
        }
    }

    @Test
    fun `dependency direction is app to core to world`() {
        assertTrue(violates("world", rules.packageOf("core")))
        assertTrue(violates("world", rules.packageOf("app") + ".view"))
        assertTrue(violates("core", rules.packageOf("app")))
        assertTrue(violates("app", rules.packageOf("world")))
        assertTrue(!violates("app", rules.packageOf("core") + ".session"))
        assertTrue(!violates("core", rules.packageOf("world")))
    }

    @Test
    fun `shared packages each module may and may not use`() {
        assertTrue(!violates("world", "$root.domain.coordinate"))
        assertTrue(violates("world", "$root.sidecar.storage"))
        assertTrue(!violates("core", "$root.sidecar.index"))
        for (module in rules.modules) {
            assertTrue(violates(module, "$root.protocol"))
            assertTrue(violates(module, "$root.fixtures.index"))
            assertTrue(violates(module, "$root.sidecar.internal.json"))
        }
    }

    @Test
    fun `project edges`() {
        assertEquals(emptyList<String>(), rules.projectEdgeViolations("core", ":tool:world"))
        assertEquals(emptyList<String>(), rules.projectEdgeViolations("app", ":shared:sidecar"))
        assertTrue(rules.projectEdgeViolations("world", ":shared:sidecar").isNotEmpty())
        assertTrue(rules.projectEdgeViolations("core", ":tool:app").isNotEmpty())
        assertTrue(rules.projectEdgeViolations("core", ":shared:protocol").isNotEmpty())
        assertTrue(rules.projectEdgeViolations("app", ":shared:test-fixtures").isNotEmpty())
    }

    @Test
    fun `shared modules may never reference the Tool`() {
        for (module in SharedArchitectureRules.allowedSharedDependencies.keys) {
            assertTrue(SharedArchitectureRules.packageEdgeViolations(module, "$root.x", "$root.tool.core").isNotEmpty())
            assertTrue(SharedArchitectureRules.projectEdgeViolations(module, ":tool:core").isNotEmpty())
        }
        // '...toolkit' is not the Tool.
        assertEquals(emptyList<String>(), SharedArchitectureRules.packageEdgeViolations("domain", "$root.domain", "$root.toolkit"))
    }

    @Test
    fun `rule families are found by name`() {
        assertEquals(SharedArchitectureRules, ArchitectureRules.named("shared"))
        assertEquals(ToolArchitectureRules, ArchitectureRules.named("tool"))
    }
}
