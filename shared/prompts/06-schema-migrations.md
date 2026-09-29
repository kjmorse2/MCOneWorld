# Prompt 06 — Explicit Schema Migration Framework

Implement a migration framework for sidecar schemas. The goal is to prove safe evolution before real incompatible formats accumulate.

## Objective

Support explicit, inspectable, one-step-at-a-time migrations while guaranteeing that a failed migration leaves the source sidecar unchanged.

## Requirements

1. Define migration identity, source version, target version, prerequisites, and result report.
2. Build a registry that finds only unambiguous contiguous migration paths.
3. Separate migration planning from application.
4. Require a verified backup reference before applying a migration to persistent state.
5. Apply into staged output, validate it with the target reader, then commit through the storage-safety layer.
6. Record warnings, transformed fields, preserved extensions, and intentionally unsupported data.
7. Reject downgrade unless a separately implemented reversible migration explicitly supports it.
8. Do not migrate automatically during ordinary read, server startup, or unrelated writes.

## Test migration

Create a deliberately small synthetic v0 fixture and a v0-to-v1 migration used only to exercise the framework. Clearly document that v0 is a test/legacy fixture, not a supported public release if that is the case.

Test:

- Valid plan and apply.
- Missing path, ambiguous path, skipped version, incompatible target, and attempted implicit downgrade.
- Preservation of documented unknown fields.
- Failure during every storage stage.
- Target validation failure.
- Source checksum change between plan and apply.
- Source bytes unchanged after every failure.
- Deterministic migration report.

## Constraints

- Do not invent a v2 feature merely to test migration.
- Do not mutate in-memory domain objects supplied by the caller.
- Do not hide lossy behavior in warnings; loss requires explicit incompatibility or approved policy.

## Acceptance criteria

Migration planning is side-effect free, application requires explicit invocation and backup evidence, and fault-injection tests prove source preservation. Run all shared tests before stopping.

