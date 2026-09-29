# Prompt 07 — Operation Plans and Durable Journals

Implement the platform-neutral model for planned destructive or stateful work. Do not implement Minecraft mutations.

## Objective

Make every future offline-tool or Paper mutation describable, preconditioned, journaled, resumable or rejectable, and auditable.

## Required model

- Stable `OperationId`
- Operation type identifier with namespaced extensibility
- Immutable operation plan
- Target descriptors that are logical and portable
- Preconditions including expected manifest revision/checksums
- Backup requirement/reference
- Ordered step descriptors
- Validation descriptors
- Rollback declaration
- Plan digest
- Append-oriented journal events
- Explicit operation states and legal transitions
- Recovery recommendation derived from durable state

## Requirements

1. Keep executable platform actions out of shared code; shared code represents and validates intent/state.
2. Define exactly which plan fields contribute to its digest.
3. Detect plan changes after approval.
4. Require durable journal acknowledgement before a caller may proceed to the next mutating stage.
5. Make repeated events idempotent or reject duplicates deterministically.
6. Preserve failed attempts and rollback records.
7. Bound journal size and event fields; define rotation/archival as a future concern if not implemented.

## Tests

- Exhaustive table of legal and illegal state transitions.
- Plan digest determinism and sensitivity.
- Changed preconditions between planning and application.
- Duplicate/reordered/missing events.
- Truncated final event versus corruption in the middle.
- Resume decisions at every durable state.
- Rollback success and rollback failure representation.
- Concurrent append attempts through the storage lock.
- Serialization golden files and unknown-field policy.

Use a model-based or property-based state-machine test in addition to example tests.

## Constraints

- No commands that edit worlds or players.
- Do not represent absolute host paths in portable journal records.
- Do not automatically resume a destructive operation; return a recommendation requiring the caller/user to decide.

## Acceptance criteria

The state machine is explicit, tested exhaustively, and documented with recovery semantics. Existing formats remain compatible or are migrated explicitly. Stop after all shared checks pass.

