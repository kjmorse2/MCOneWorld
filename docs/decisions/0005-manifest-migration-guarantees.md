# ADR 0005: Manifest schema migration guarantees

- Status: **Accepted**, 2026-09-28. Accepted by the implementing agent under explicit owner delegation.
- Milestone: Prompt 06
- Code: `shared:sidecar`, package `io.github.kjmorse2.mconeworld.sidecar.migration`

## Context

Sidecar history must survive decades of format changes. Root `AGENTS.md` §9 and `shared/AGENTS.md` require migrations that are:

- explicit
- versioned
- testable
- never implicit during a destructive world operation
- failure-safe: the source is unchanged on failure

## Decision

1. **Unit of migration.** A migration is a pure, deterministic `bytes → bytes` step from exactly one schema version to the next (`N.m → N.(m+1)`, or `N.m → (N+1).0`). A step can therefore never skip a version. Working on bytes, rather than on decoded documents, is necessary because an old manifest cannot be decoded by the current codec. It also keeps JSON types out of the public API.
2. **Paths are unique and contiguous.** The registry refuses to choose between alternative chains (`AMBIGUOUS_PATH`), and reports gaps (`NO_PATH`).
3. **Downgrades are explicit.** A downgrade happens only through a step written as a downgrade. Otherwise `DOWNGRADE_UNSUPPORTED`. A file whose major version is newer than the reader's is never "migrated" down implicitly.
4. **Only the current reader's schema is a valid target** (`INCOMPATIBLE_TARGET` otherwise), because the output must be validated. Any minor of the current major is read directly (`ALREADY_CURRENT`), per the manifest compatibility rules.
5. **Planning is side-effect free.** It performs a full dry run in memory and validates the result with the target reader. The plan pins the source checksum and the migrated checksum.
6. **Application is explicit** (`MigrationRunner.apply`), and requires:
   1. **Backup evidence** (`VerifiedBackup`): a byte-identical copy of the source manifest, read and checksummed, located outside the sidecar, and re-verified at apply time. A manifest copy is sufficient because migrations rewrite only `manifest.json`, and data files are immutable (ADR 0004).
   2. **Source unchanged** since planning (`SOURCE_CHANGED`).
   3. **Determinism.** Re-running the steps reproduces the planned report and output exactly (`NONDETERMINISTIC`).
   4. **Commit through the storage transaction protocol**, keyed on the source checksum. The migrated manifest's revision is the source revision + 1. The persisted report (`reports/migrations/<runId>.json`) is referenced by the new manifest, and `manifest.previous.json` keeps the legacy manifest.
7. **Nothing migrates implicitly.**
   - An ordinary read of a legacy manifest fails with `manifest.version.unsupported`.
   - An ordinary write refuses (`RECOVERY_REQUIRED`), because the manifest is unreadable.
   - Server start and unrelated operations never call the runner.
8. **Loss is never silent.** Data a step cannot carry forward is recorded as `unsupported` and fails the step (`UNSUPPORTED_DATA`), carrying a partial report. Undocumented members fail the step's prerequisites. Warnings are for non-lossy observations only.
9. **The production registry is empty.** Manifest 1.0 is the first released schema. The framework is exercised by a synthetic, never-released 0.9 format that lives only in test sources (`docs/formats/manifest-v0.9-synthetic.md`).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Migrate on read | It violates the read-only-open rule, and could run during a destructive operation |
| Multi-version "jump" migrations | They hide skipped versions, and multiply test surface |
| Migrations on decoded domain objects | Legacy formats cannot be decoded by the current codec. Coupling to domain objects would force old code to live forever. |
| Shipping the synthetic 0.9 migration | It would advertise support for a format that never existed |

## Consequences

- Each future schema major version must add exactly one step, a synthetic or real legacy fixture, a golden migrated output, and fault tests. The shared test harness for all of this already exists.
- Migrations must be written as pure functions: no clock, no randomness, no I/O. The runner enforces determinism.

## Testing

- `MigrationPlanningTest` (16 tests):
  - valid plan
  - preserved extensions and moved notes
  - report determinism, including reordered input
  - missing path, a skipped version (an invalid step, and a gap), ambiguous path, duplicate ids
  - incompatible targets, already-current sources, implicit and explicit downgrade
  - unsupported data with a partial report
  - prerequisite failure, target validation failure, unreadable source
  - the single-step rule
  - the caller's bytes are not mutated
- `MigrationApplyTest` (20 tests):
  - end-to-end apply, including the persisted report and the kept previous manifest
  - proof that nothing migrates implicitly
  - planning performs zero mutations
  - four kinds of invalid backup evidence
  - source changed between plan and apply
  - nondeterminism
  - an I/O failure and a crash at each of the 7 commit stages, with the source byte-identical before the commit point; recovery, and a successful retry, after every crash

## Amendment (Tool T4, 2026-10-03)

With manifest 1.1 the current schema is no longer the first of its major. The planner now applies point 4 to every minor of the current major (1.0 is `ALREADY_CURRENT` for the 1.1 reader). A chain from an older major ends at the first version of the target's major, here 1.0, because minors never need a step. The current reader then reads that result directly, and the commit writes it as 1.1. `MigrationPlanningTest.currentMajorNeedsNoMigration` covers the older minor.
