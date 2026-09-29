# Prompt 01 — Reproducible Shared Build Scaffold

Implement only the approved shared-project scaffold. Work from the repository root and read both applicable `AGENTS.md` files completely.

## Preconditions

- Prompt 00 documents exist and its required decisions are approved.
- The Java group/package ID and pinned toolchain are recorded.
- The working tree has been inspected and unrelated user changes will be preserved.

If any precondition is false, stop and report it instead of guessing.

## Objective

Create buildable but behaviorally minimal Gradle modules:

```text
shared:domain
shared:sidecar
shared:protocol
shared:test-fixtures
```

## Requirements

1. Integrate the modules into the existing root build without breaking the imported offline tool.
2. Use the approved pinned Java toolchain, test framework, formatting, and static-analysis configuration.
3. Configure dependency locking or verification according to the approved build ADR.
4. Enforce the dependency boundaries from `shared/AGENTS.md` with build configuration or architecture tests where practical.
5. Make `test-fixtures` unavailable to production runtime configurations.
6. Configure reproducible JAR output.
7. Add module descriptors or JPMS only if explicitly approved; do not introduce them incidentally.
8. Create concise module READMEs describing responsibility and forbidden dependencies.
9. Add a CI task or aggregate Gradle task that runs all shared checks.

## Tests and proofs

- Add one meaningful smoke test per module rather than empty placeholder tests.
- Add an architecture/dependency test proving shared production modules do not import Paper, Bukkit, Fabric, Minecraft, JavaFX, or MCA Selector packages.
- Run the shared checks twice and compare relevant generated/JAR checksums to demonstrate reproducibility, accounting for intentionally variable reports.
- Run existing offline-tool tests or its closest build verification to prove the scaffold did not break it.

## Constraints

- Do not implement the domain model yet.
- Do not select the chunk-index implementation.
- Do not add Minecraft platform dependencies.
- Do not suppress compiler warnings globally to make the build pass.
- Do not reformat the imported MCA Selector subtree broadly.

## Completion report

Report changed files, dependency graph, exact pinned versions, test commands/results, reproducibility evidence, and remaining risks. Do not proceed to Prompt 02 until every acceptance criterion passes.

