# ADR 0006: Operation plans and durable journals

- Status: **Accepted**, 2026-09-30. Accepted by the implementing agent under explicit owner delegation.
- Milestone: Prompt 07
- Normative format: [`docs/formats/operation-journal-v1.md`](../formats/operation-journal-v1.md)

## Context

Every future mutation of a world, a player, or the sidecar must be:

- describable before it happens
- checked against the state it was planned for
- journaled so that an interruption is detectable
- auditable afterwards

Root `AGENTS.md` §10 and `shared/AGENTS.md` ("Operation plan and journal") require:

- an immutable plan with a change-detecting checksum
- an explicit state machine
- append-only history that keeps failed attempts
- recovery derived from durable state, never automatic resumption
- no absolute paths in portable records

Shared code must not perform any platform action.

## Decision

### 1. Split: rules in `domain`, storage in `sidecar`

- `domain.operation` holds the plan model, the transition table (`OperationTransitions`), the guarded replay (`OperationProgress`), and the recommendations (`RecoveryRecommendation`).
- `sidecar.operation` stores plans and journals and classifies their integrity.
- `sidecar.storage` gains exactly three lock-checked durable primitives for the new `operations/` area: write-once, append, and replace-if-unchanged.

Steps and checks are namespaced *descriptors* (`ActionKey`). Shared code never executes them.

### 2. Journals live outside the manifest

Plans and journals live under `operations/<id>/`. They are neither referenced by the manifest nor committed through sidecar transactions, and `operations` became a reserved top-level name.

**Why.**

- A journal is append-only, while manifest-referenced files are immutable.
- A manifest commit per event would change the manifest revision that the operation's own preconditions pin.
- Recovery must work even when the manifest is the thing being changed.

### 3. Plan digest = SHA-256 of the canonical encoding of the whole document

- Nothing is excluded, because the plan holds no volatile state. Approval and progress are journal events.
- Unknown members from a newer minor version count too, so every reader computes the writer's digest.
- The first journal entry records the digest. `APPLY_STARTED` carries the digest the approver saw.
- A mismatch at either point refuses to proceed.

### 4. State machine

The `shared/AGENTS.md` sketch, extended with:

- `ROLLING_BACK`, so a rollback's start is durable before it mutates
- `ROLLBACK_FAILED`, so a failed rollback is represented, not hidden
- `CANCELLED`, to abandon before anything was applied, for example after a changed precondition

Within `APPLYING`, `STEP_STARTED` and `STEP_COMPLETED` give per-step durability. A step may be restarted only if the plan declares it `retrySafe`.

Attempts are bounded: 8 per step and 8 rollbacks. That bounds every legal journal to `maxEntries(plan)`, at most 2 581 entries, without a separate cap.

### 5. Durable acknowledgement, enforced by type

- `append` returns a `JournalAck` only after the line is written and `fsync`ed.
- Only an ack for `STEP_STARTED` or `ROLLBACK_STARTED` carries a `MutationPermit`, which only the journal can construct.
- Platform mutation code will require a permit, so "journal first, then mutate" is checked by the compiler, not by convention.

### 6. Duplicates: deterministic rejection, with one idempotent case

The caller passes the sequence number it expects:

- **Exact re-send.** Re-sending the last event with its own sequence number is acknowledged as `alreadyRecorded`, with no write and **no permit**. This is the safe retry after an ambiguous failure: the first acknowledgement may have been acted on, so it must not grant the mutation twice.
- **Everything else is rejected.** Any other sequence mismatch is `sequence_conflict`. A rule violation is `event_rejected`, which writes nothing.

### 7. One append-only JSON Lines file per operation, with per-line checksums and a hash chain

- **Self-verifying entries.** Each line is compact canonical JSON with its own checksum and the previous entry's checksum.
- **Torn versus corrupt.** A torn write can only leave an unterminated fragment, because the LF is the last byte of each single write. So a fragment is `TORN_TAIL`: it was never acknowledged and is safe to ignore. Any invalid terminated line is `CORRUPT`, even the final one. Trust stops there, and a person must decide.
- **Explicit repair.** Discarding a torn tail is an explicit call. It first preserves the fragment in its own file.

### 8. Unknown fields

- At the reader's own version or older, unknown members are rejected.
- From a newer minor version, files are readable but **not executable or writable** by the older reader. Unknown semantics could otherwise be silently ignored.
- Plans and journals are never rewritten, so nothing can be dropped in a read-modify-write cycle.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Journal events as manifest transactions | It churns the revision that preconditions pin, costs one manifest commit per event, and leaves no recovery if the manifest itself is damaged |
| One file per event (atomic rename each) | Torn writes become impossible, but it creates thousands of files and directory syncs per operation. Corruption detection is no simpler, and JSONL keeps history in one auditable file. |
| Embedded database (SQLite) | Not human-reviewable, adds a native dependency, and prejudges ADR-level storage choices |
| Digest over selected fields | Every exclusion is a way to change a plan unnoticed; the plan has no volatile fields to exclude |
| Treat any bad final line as torn | It could silently drop an acknowledged `STEP_STARTED` that was damaged later, and then re-run a non-idempotent step |
| Idempotent re-send also grants a permit | It could run a mutation twice after a crash between acknowledgement and use |
| Automatic resume or automatic torn-tail repair on open | It violates "never auto-resume" and "read-only open never repairs" |
| Cap entries at a fixed number | Unreachable by legal histories, so it would be dead, untestable code. Attempt bounds give an exact, testable bound instead. |

## Consequences

- One `fsync` per event. That is acceptable: operations have at most a few thousand events.
- Platform modules must design steps to be retry-safe where possible. Otherwise an interruption mid-step always means fail-and-rollback.
- `StorageFileSystem` gained `openAppend` and `size`, so every implementation must provide them. Only the in-repository test wrappers existed.
- `operations/` is reserved. No released manifest could have used it, so there is no migration impact.
- Rotation and archival of finished journals are deferred. Disk use grows with operation count until an explicit archival operation exists.
- A precondition snapshot holds at most 1 024 checksums and 1 024 facts. Operations over more inputs need a checksummed input list file, which is future work.

## Migration impact

None. These are new formats at version 1.0, and existing manifests and storage files are unchanged.

## Testing

**`domain.operation` tests:**

| Test | Covers |
|---|---|
| `OperationTransitionTableTest` | All 130 (state, event-type) pairs against an independently written table, both in the table and through `OperationProgress` |
| `OperationProgressTest` | Every guard; duplicate, reordered, and missing entries; changed preconditions; plan changed after approval; rollback success and failure; the exact worst-case size bound |
| `RecoveryRecommendationTest` | The decision at every durable state and sub-state; that every offered action is recordable |
| `OperationStateMachineProperties` (jqwik) | A model-based test: random plans and event sequences checked against an independent reference model, with recommendation invariants after every step. It reports which final states were reached, so coverage is visible. |
| `OperationPlanTest` | Plan rules, key grammars (no host paths), and text bounds |

**`sidecar.operation` and storage tests:**

| Test | Covers |
|---|---|
| `OperationCodecTest` | The golden plan and journal (byte-exact, with `.meta` checksums, and equal to the published examples); every event type round-tripped; digest determinism and sensitivity to every plan component; the unknown-field and version policy |
| `OperationJournalStoreTest` | Creation and idempotence; permits; duplicate, reordered, and missing appends; changed preconditions; the lock; stale and broken handles; a failed sync; a torn tail from an injected crash; torn-tail discard with evidence preserved; final-line versus middle corruption; removed, swapped, and duplicated lines; changed or missing plan; newer-minor read-only; read-only inspection (zero mutations); the resume decision read from disk after every durable prefix; 8 concurrent appenders serialized by the writer lock; the store reproducing the golden journal byte for byte |
| `OperationFileWriterTest` | The write-once, append, and replace primitives: idempotence, conflicts, leftover temporary files never deleted, failure cleanup, atomic-move refusal, lock, symlinks, and bounds |
