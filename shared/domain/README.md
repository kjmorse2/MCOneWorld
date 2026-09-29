# shared:domain

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-domain` · Package: `io.github.kjmorse2.mconeworld.domain`

**Responsibility.** Platform-neutral, validated domain values shared by at least two products:

- identifiers
- dimensions
- block, chunk, region, and season-local coordinates
- seasons and chunk provenance
- operation state
- structured validation results

**May depend on:** JDK and JSpecify annotations only.

**Forbidden:**

- other shared modules
- serialization, compression, or database libraries
- Paper, Bukkit, Fabric, Minecraft, Adventure, JavaFX, MCA Selector (`net.querz`), and mappings

`verifySharedDependencies` and `verifySharedBytecode` enforce these rules.

**Status:** scaffold only (Prompt 01). The domain model arrives in Prompts 02, 03, and 07.
