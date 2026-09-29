# Prompt 11 — Shared-Project Hardening and Release Audit

Audit the completed shared project as a dependency that will soon be consumed by the offline tool, Paper plugin, and Fabric client. Do not add unrelated features.

## Objective

Find and fix contract, corruption, compatibility, determinism, dependency, and test gaps before platform integration begins.

## Audit areas

1. Public API surface and module dependency direction.
2. Deterministic builds, JSON, journals, reports, protocol bytes, and index behavior where promised.
3. Persistent-format and protocol version separation.
4. Unknown-field and unsupported-version behavior.
5. Negative coordinates, overflow, allocation bounds, and malformed identifiers.
6. Path traversal, symlink escape, lock contention, and interruption safety.
7. Migration source preservation.
8. Operation recovery semantics.
9. Chunk-index differential correctness and resource limits.
10. Protocol fuzz/malformed-input behavior.
11. License notices, dependency licenses, and MCA Selector attribution boundaries.
12. Documentation agreement with actual code.

## Required validation

- Run formatting and static analysis.
- Run the complete shared test suite from a clean state.
- Run property/model tests with recorded seeds and enough iterations to be meaningful.
- Run filesystem fault-injection tests repeatedly to detect flakes.
- Run benchmarks and compare with accepted budgets.
- Build twice from clean outputs and compare reproducible artifacts.
- Generate dependency and vulnerability reports using the approved tooling.
- Test all committed golden fixtures through the current readers.
- Compile minimal temporary consumer examples for offline-tool, Paper-adapter, and Fabric-adapter use without adding their platform dependencies to shared code.

## Deliverables

- `docs/architecture/shared-release-audit.md`
- Updated compatibility and format documents
- A machine-readable test/compatibility summary if the build supports it
- Fixed defects with regression tests
- Explicit list of deferred risks and platform-integration assumptions
- Candidate shared API/version designation

## Constraints

- Do not weaken validation or delete fixtures to make the audit pass.
- Do not change an accepted format silently; use a migration or return to its ADR.
- Do not claim Paper/Fabric runtime compatibility yet; this milestone proves only shared-contract readiness.
- Do not perform broad refactors without a demonstrated defect or measurable maintenance benefit.

## Completion gate

The shared project is ready for platform work only if all required checks pass, reproducibility is demonstrated, persistent contracts are documented, and no critical/high unresolved data-integrity issue remains. Otherwise, stop and report the blocking issues with a remediation plan.

