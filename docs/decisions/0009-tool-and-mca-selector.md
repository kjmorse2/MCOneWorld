# ADR 0009: MCOneWorld Tool and MCA Selector

- Status: **Accepted**, 2026-10-02, by the project owner (tool-planning session). It resolves root `AGENTS.md` §22, "MCA Selector fork or independent application".
- Plan: [`docs/architecture/tool-implementation-plan.md`](../architecture/tool-implementation-plan.md)

## 1. Context

`offline-tool/` is an unmodified `git subtree` of MCA Selector 2.9 (MIT, © Querz), a JavaFX region editor. It has what the Tool needs to read worlds:

- MCA region headers
- dimension-directory detection for both the legacy and the 26.1+ layouts
- per-version chunk handlers and a terrain renderer

It also conflicts with the monorepo rules in ways that matter for a tool that touches irreplaceable worlds:

- **Global mutable state:** `ConfigProvider.WORLD`, `JobHandler`, and the caches allow one world per JVM.
- **Non-atomic writes:** temp files in the system temp directory, then a cross-filesystem `Files.move`.
- **No safety net:** no `session.lock` check, no backups, no dry runs, and no tests.
- **Outside the repository rules:** a Groovy build on JitPack, outside the root build's locking, verification, and quality gates.

The owner's workflow uses MCA Selector *as an application* to trim a world (delete unwanted chunks), then MCOneWorld Tool to set up seasons.

## 2. Decision

**A new application with vendored pieces.**

- MCOneWorld Tool is a new set of root-build modules: `tool/world`, `tool/core`, and `tool/app`. They are under the same locking, verification, Spotless, Error Prone, NullAway, and test gates as `shared/`.
- Only the MCA Selector code the Tool needs is **copied** into `tool/world`:
  - region-header reading
  - dimension detection
  - world and region directory helpers
  - later, optionally, terrain rendering
- The copied code is refactored to take its world context explicitly (no global state) and brought up to the quality gates.
- Each vendored file keeps an attribution comment naming MCA Selector, its MIT licence, and the upstream commit `d31bee6a`. `NOTICE.md` lists the vendored paths.
- **`offline-tool/` stays untouched.** It remains the user's trimming application and the upstream reference. The Tool never calls it, as a library or as a subprocess.

## 3. Alternatives

| Alternative | Why not |
|---|---|
| Fork in place: evolve `offline-tool/` into the Tool | Inherits the global state, unsafe writes, and missing tests. The Groovy and JitPack build stays outside the repository rules, and upstream pulls become impractical. |
| MCA Selector as a library dependency | It isn't published as a library. Its API reads global configuration, so a world would have to be "selected" globally before every call. |
| MCA Selector as a subprocess (CLI mode) | Gives no structured results, previews, or journaling, and its writes would bypass the safe-operation protocol (root §10). |
| An independent rewrite with no vendored code | Re-derives the region format and dimension layouts, which MCA Selector already handles across versions. |

## 4. Consequences

- The Tool reads worlds read-only. In v1 its only world writes are the End archive and the dragon-fight reset (ADR 0010). Chunk deletion stays in MCA Selector; vendoring its region writer is optional and lowest priority.
- Fixes to vendored files are ours to maintain. Upstream changes are reviewed and ported by hand; the recorded commit makes the diff reviewable.
- The JitPack NBT dependency is not used. The Tool uses a Maven Central NBT library or a vendored minimal reader and writer, whichever T1 can lock and verify.

## 5. Migration impact

None on any persistent format. `offline-tool/` is unchanged.

## 6. How it is tested

- Vendored code is tested in `tool/world` against a synthetic world builder (region headers, both layouts, negative coordinates, corrupt headers).
- A build guard keeps `shared/*` from depending on tool modules and keeps the dependency direction `tool/app` → `tool/core` → `tool/world`.
- `git diff --stat offline-tool/` stays empty; the tool milestones check it.
