# Prompt 00 — Repository Discovery and Shared-Architecture Decisions

You are preparing the MCOneWorld shared project for implementation. Work from the repository root.

Read the complete repository-root `AGENTS.md`, `shared/AGENTS.md`, and every existing architecture decision or compatibility document relevant to shared code. Inspect the actual repository, including the imported MCA Selector subtree and all Gradle files. Do not assume the intended directory structure already exists.

## Objective

Produce an evidence-based implementation plan for the shared modules without implementing production code. Establish the decisions needed for a reproducible scaffold while preserving all existing user work.

## Required investigation

1. Inventory the current Git and Gradle structure, Java source levels, plugins, dependency repositories, tests, formatting, static analysis, and CI.
2. Confirm whether MCA Selector is present as an imported subtree, ordinary directory, submodule, or nested repository. Report unexpected nested `.git` directories; do not remove them.
3. Identify licenses and attribution obligations inherited from MCA Selector and proposed dependencies.
4. Determine the exact current stable Minecraft, Paper, Fabric Loader, Fabric API, Gradle, and Java versions that future platform projects intend to pin. Use primary project documentation when verification is needed. Do not use floating versions.
5. Check whether a reverse-DNS Java package/group ID has already been chosen. If absent, ask the user to choose it rather than inventing a personal or organizational domain.
6. Evaluate maintained candidates for:
   - JSON serialization with deterministic output and unknown-field preservation.
   - Testing and parameterized testing.
   - Property-based testing.
   - Static analysis and formatting.
   - Benchmarking.
7. Propose, but do not yet select, the chunk-index technology. That final decision belongs to Prompt 08 after benchmarks.
8. Identify any conflict between the imported MCA Selector build and the proposed monorepo build.

## Deliverables

Create or update planning documents only:

- `docs/architecture/shared-project-plan.md`
- `docs/compatibility/version-matrix.md`
- Proposed ADR drafts for the Java package ID, build/test toolchain, and JSON stack
- A dependency graph for the four shared modules
- A list of unresolved questions requiring user approval
- A milestone-by-milestone risk register

Do not mark a proposed ADR accepted without user approval.

## Verification

Run only read-only or existing build commands. Record:

- Commands executed
- Current results
- Existing failures, distinguished from failures introduced by this task
- Any network sources consulted

## Stop conditions

Stop and request direction if:

- Existing uncommitted changes overlap files the plan would alter.
- The package/group ID is missing.
- Licensing appears incompatible.
- The repository layout differs materially from the documented monorepo.
- A decision would constrain persistent data before alternatives have been evaluated.

End with a concise recommendation and an explicit approval checklist for Prompt 01. Do not implement the scaffold in this milestone.

