# Operation plans and journals, version 1 (normative)

- Model: `shared:domain`, package `io.github.kjmorse2.mconeworld.domain.operation`
- Persistence: `shared:sidecar`, package `io.github.kjmorse2.mconeworld.sidecar.operation`; durable file primitives in `sidecar.storage` (`OperationFiles`, `OperationFileWriter`)
- Decision: [ADR 0006](../decisions/0006-operation-plans-and-journals.md)
- Examples: [examples/operation-plan-v1-example.json](examples/operation-plan-v1-example.json) and [examples/operation-journal-v1-example.jsonl](examples/operation-journal-v1-example.jsonl). They are byte-identical to the golden fixtures, which a test enforces.

Every world- or player-changing operation, and every multi-step sidecar operation, is described by an immutable **plan** and advanced only by appending to its **journal**. Shared code records and checks; it never performs a step and never resumes anything.

## 1. Layout

```text
<sidecar root>/operations/            the sidecar's only mutable area; never referenced by a manifest
└── <operation-id>/                    canonical lowercase UUID
    ├── plan.json                      immutable once written (§3)
    ├── journal.jsonl                  append-only (§4)
    └── journal.torn-<n>-<hash>.bin    only after an explicit torn-tail discard (§6)
```

- `operations` is a reserved top-level name. Manifests can neither stage nor reference anything under it. (This was added in Prompt 07. No manifest could have referenced it before, since no sidecar existed outside tests.)
- Directories under `operations/` whose names are not canonical operation ids are ignored and never touched.
- The symlink policy of [sidecar-storage-v1.md §1](sidecar-storage-v1.md#1-directory-layout) applies.
- Every write needs the writer lock ([ADR 0004 §6](../decisions/0004-sidecar-storage-durability-and-locking.md)), which is verified before each write.
- Reading writes nothing.

## 2. Encoding

- Encoding profile and canonical form are those of [manifest-v1.md §2](manifest-v1.md#2-encoding-profile).
- `plan.json` uses the canonical pretty form.
- Each journal line uses the **compact canonical form**: the same member order and escaping, no whitespace, then exactly one LF. Strings escape LF, so an entry can never contain one.
- Timestamps are UTC ISO-8601 with the shortest exact fraction, years 0000–9999.
- Checksums are `sha256:<64 lowercase hex>`.
- Enum constant names are persisted verbatim. A new constant, event type, target kind, or backup kind requires a new **major** version.
- No member may hold an absolute host path:
  - Input keys and extension-target keys cannot contain `/`-rooted paths, `\`, `..`, or drive letters (grammar enforced).
  - Free text (`description`, `justification`, `message`) is bounded single-line text. Callers must keep paths and player-sensitive data out of it.

## 3. `plan.json`

Format `mconeworld-operation-plan`, schema `1.0`. Members, all required:

| Member | Type | Meaning |
|---|---|---|
| `format`, `schemaVersion` | string | `"mconeworld-operation-plan"`, `"1.0"` |
| `id` | uuid | Operation id; equals the directory name |
| `type` | namespaced key | For example `mconeworld:chunk_deletion`. Grammar: `[a-z0-9_.-]+:[a-z0-9_.-]+(/[a-z0-9_.-]+)*`, ≤128 chars, no `.`/`..` segment. |
| `createdAt` | timestamp | When the plan was created |
| `createdBy` | `{name, version}` | Program identity, for example `mconeworld-tool` `0.1.0`; no host or user data |
| `archive` | uuid | The sidecar history (manifest `archiveId`) |
| `impact` | `SIDECAR_MUTATING`, `REVERSIBLE`, `WORLD_MUTATING`, `DESTRUCTIVE` | How far it reaches |
| `targets` | array, ≤4096 | Distinct logical targets, by `kind` (see below) |
| `preconditions` | snapshot | The state planned against (see below) |
| `backup` | object | `{"kind":"NEW"}`, `{"kind":"EXISTING","backup":{id,checksum}}`, or `{"kind":"NOT_REQUIRED","justification":text}` |
| `steps` | array, 1–256 | `{action, description, retrySafe, targets}`. `targets` lists strictly increasing indexes into `targets`. |
| `validations` | array, 1–256 | `{check, description}`. Checks are read-only. |
| `rollback` | object | `{strategy, description}`. Strategy is one of `RESTORE_BACKUP`, `INVERSE_STEPS`, `MANUAL`, `NOT_APPLICABLE`. |

**Targets.** Each target has a `kind` and the members for that kind:

| `kind` | Members |
|---|---|
| `chunkArea` | `dimension`, `minX`, `minZ`, `maxX`, `maxZ` (inclusive, 32-bit chunk coordinates) |
| `player` | `player`: the account UUID, never a replacement |
| `season` | `season` |
| `sidecarManifest` | none |
| `extension` | `type` (namespaced key), `key` (token `[A-Za-z0-9._:+-]{1,128}`) |

**Precondition snapshot.**

- `manifest` is one of:
  - `{"state":"ABSENT"}`
  - `{"state":"AT_REVISION","revision":n,"checksum":c}`. Both the revision and the exact checksum are compared.
- `checksums` maps an input key to a checksum. An input key is a logical root plus a relative path, for example `world:region/r.-1.0.mca`. Grammar: `[a-z0-9_.-]+:[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*`, ≤255 chars.
- `facts` maps an input key to a token, for example `minecraft:data_version` → `"4903"`.
- Each map holds at most 1024 entries. The world's identity is expressed here (for example a `world:level.dat` checksum), never as a path.

**Policy rules** (decoding rejects violations with `domain.operation.plan_invalid`):

- At least one step and at least one validation check.
- `NOT_REQUIRED` backup and `NOT_APPLICABLE` rollback are allowed only for `SIDECAR_MUTATING`.
- `RESTORE_BACKUP` requires a backup.

### Plan digest

`digest = SHA-256(canonical pretty encoding of the entire parsed plan document)`.

- **Every member counts.** That includes `format`, `schemaVersion`, every nested member, array order (target order matters: steps refer to targets by index), and members from a newer minor version.
- **Nothing is excluded**, because the plan holds no volatile state. Approval, progress, and execution times live only in the journal.
- **Formatting does not count.** Whitespace and member order cannot change the digest.
- **For a plan this codec wrote**, the digest equals the SHA-256 of the file's bytes.

The digest is recorded in the journal's first entry. The approver approves a digest, and `APPLY_STARTED` carries the approved digest, which must equal the recorded one. A plan file whose digest no longer matches the journal is reported as `PLAN_CHANGED`, and the journal is no longer writable.

## 4. `journal.jsonl`

Format `mconeworld-operation-journal`, schema `1.0`. One entry per line:

| Member | Type | Meaning |
|---|---|---|
| `format`, `schemaVersion` | string | `"mconeworld-operation-journal"`, `"1.0"`, on every line |
| `operation` | uuid | Must equal the directory |
| `sequence` | integer | 0, 1, 2, … with no gaps |
| `previous` | checksum or null | `checksum` of the previous entry; null exactly for sequence 0 |
| `recordedAt` | timestamp | For audit only. Ordering uses `sequence`, never clocks. |
| `event` | object | `type` plus that type's members (below) |
| `state` | state | The operation state after this event. It is redundant with replay and checked against it. |
| `checksum` | checksum | SHA-256 of the line's compact canonical encoding **without** `checksum` |

**Event members, by `type`:**

| `type` | Members |
|---|---|
| `PLAN_RECORDED` | `operationType`, `planDigest`. Only allowed at sequence 0. |
| `BACKUP_CONFIRMED` | `backup`: `{id, checksum}`, or `null` for a `NOT_REQUIRED` plan |
| `CANCELLED` | `reason`: `{code, message}` |
| `APPLY_STARTED` | `approvedPlanDigest`, `observed`: a snapshot (§3) |
| `STEP_STARTED` | `step` (0-based), `attempt` (1-based) |
| `STEP_COMPLETED` | `step` |
| `VALIDATION_STARTED` | none |
| `CHECK_PASSED` | `check` (0-based) |
| `OPERATION_COMPLETED` | none |
| `OPERATION_FAILED` | `reason`, plus `step`: integer or null |
| `ROLLBACK_STARTED` | `attempt` (1-based) |
| `ROLLBACK_COMPLETED` | none |
| `ROLLBACK_FAILED` | `reason` |

### State machine

```text
PLANNED ─▶ BACKED_UP ─▶ APPLYING ─▶ VALIDATING ─▶ COMPLETED
   │           │           │            │
   ▼           ▼           ▼            ▼
CANCELLED  CANCELLED     FAILED ◀───────┘
                           │
                           ▼
                      ROLLING_BACK ─▶ ROLLED_BACK
                        ▲     │
                        │     ▼
                       ROLLBACK_FAILED
```

| From | Legal events → to |
|---|---|
| PLANNED | `BACKUP_CONFIRMED` → BACKED_UP; `CANCELLED` → CANCELLED |
| BACKED_UP | `APPLY_STARTED` → APPLYING; `CANCELLED` → CANCELLED |
| APPLYING | `STEP_STARTED`, `STEP_COMPLETED` → APPLYING; `VALIDATION_STARTED` → VALIDATING; `OPERATION_FAILED` → FAILED |
| VALIDATING | `CHECK_PASSED` → VALIDATING; `OPERATION_COMPLETED` → COMPLETED; `OPERATION_FAILED` → FAILED |
| FAILED | `ROLLBACK_STARTED` → ROLLING_BACK |
| ROLLING_BACK | `ROLLBACK_STARTED` (restart an interrupted attempt) → ROLLING_BACK; `ROLLBACK_COMPLETED` → ROLLED_BACK; `ROLLBACK_FAILED` → ROLLBACK_FAILED |
| ROLLBACK_FAILED | `ROLLBACK_STARTED` → ROLLING_BACK |
| COMPLETED, ROLLED_BACK, CANCELLED | none (terminal) |

This extends the sketch in `shared/AGENTS.md` in three ways:

- **`ROLLING_BACK`** makes the rollback's start durable before it mutates anything.
- **`ROLLBACK_FAILED`** represents a failed rollback without pretending it succeeded.
- **`CANCELLED`** abandons an operation before anything was applied, for example after a precondition change.

**Guards** (enforced by `OperationProgress`, with `operation.*` codes):

- **Sequence.**
  - Entry `n` has sequence `n` (`operation.sequence.mismatch`).
  - The recorded `state` matches replay (`operation.state.mismatch`).
- **Backup.** `BACKUP_CONFIRMED` must satisfy the plan's backup requirement (`operation.backup.mismatch`). That means any backup for `NEW`, exactly the named one for `EXISTING`, and `null` for `NOT_REQUIRED`.
- **Approval.** `APPLY_STARTED.approvedPlanDigest` must equal the recorded digest (`operation.plan.changed`).
- **Preconditions.** `APPLY_STARTED.observed` must equal the plan's preconditions exactly. Every difference is reported (`operation.precondition.changed`), including missing and extra keys.
- **Steps.**
  - Steps start in order and complete before the next one starts (`operation.step.out_of_order`).
  - Attempts are numbered 1, 2, … (`operation.attempt.mismatch`).
  - A step that started but did not complete may be restarted only if it is `retrySafe` (`operation.step.retry_not_allowed`).
  - A step has at most 8 attempts (`operation.attempts.exhausted`).
  - `VALIDATION_STARTED` requires every step completed (`operation.step.incomplete`).
- **Checks.** Checks pass in order. `OPERATION_COMPLETED` requires all of them (`operation.check.out_of_order`).
- **Failure.** `OPERATION_FAILED.step` is null or the step in progress.
- **Rollback.** Attempts are numbered 1, 2, …, at most 8.

**Size.** These bounds mean a legal journal holds at most `3 + 9·steps + 1 + checks + 1 + 16` entries, which is 2 581 for the largest plan. Other limits:

| What | Limit |
|---|---|
| One entry | 1 MiB |
| A journal | 16 MiB |
| A plan | 4 MiB |

Rotation and archival of completed journals are deliberately **not implemented**. A future explicit operation may move finished operations elsewhere. It must not rewrite a journal.

## 5. Appending: durable acknowledgement and idempotence

`OperationJournal.append(expectedSequence, event)`:

1. Verifies the writer lock (`sidecar.storage.lock_lost`).
2. **Idempotent re-send.** If `expectedSequence` is the last entry's sequence and `event` equals the last entry's event, it returns an acknowledgement marked `alreadyRecorded`, without writing. The acknowledgement carries **no `MutationPermit`**, because the first acknowledgement may already have been acted on.
3. **Sequence check.** Any other `expectedSequence` that is not the next one is refused with `sidecar.operation.sequence_conflict`. This covers duplicates, reordering, gaps, and stale callers.
4. **Rule check.** The event must pass every guard. A refused event (`sidecar.operation.event_rejected`, details = the domain issues) changes nothing, and the handle stays usable.
5. **Append and sync.** The line is appended. The file must still have the size this handle last saw; otherwise `sidecar.operation.handle_stale`. Then the file is `fsync`ed.
6. **Acknowledge.** Only now is a `JournalAck` returned. For `STEP_STARTED` and `ROLLBACK_STARTED`, the ack carries a `MutationPermit`. Only the journal can create one.

A caller **must not** begin any mutation without the permit from step 6. If an append throws, the entry may or may not be on disk. The handle is then broken (`sidecar.operation.handle_broken`), and the caller must reopen, which re-reads the durable state.

Creating an operation (`OperationJournals.create`) writes `plan.json` atomically and durably, then appends entry 0. It is idempotent for the same plan, completes a recording whose plan was written but whose journal was not, and refuses a different plan under the same id (`sidecar.operation.already_exists`).

## 6. Reading: integrity and recovery recommendation

`OperationJournals.inspect(id)` is read-only. It classifies the files:

| Integrity | Condition | Recommendation |
|---|---|---|
| `INTACT` | Every line verifies | From the last state (§7) |
| `TORN_TAIL` | Every **LF-terminated** line verifies, but bytes follow the last LF | From the complete lines, plus a note that the torn entry must be discarded |
| `CORRUPT` | An LF-terminated line fails to parse, its checksum, the chain, the operation id, the sequence, the recorded state, or a rule; or the journal exceeds 16 MiB | `MANUAL_INSPECTION`, with `lastDurableState` = the state before the bad line, and partial effects assumed |
| `PLAN_CHANGED` | The plan's digest ≠ the digest in entry 0 | `MANUAL_INSPECTION` |
| `PLAN_UNREADABLE` | The plan is missing, too large, or invalid | `MANUAL_INSPECTION` |
| `NOT_RECORDED` | The plan exists, but the journal is missing or empty | `RECORD_PLAN`: nothing was applied |

**Torn versus corrupt.** Each append writes the line and its terminating LF in one write and syncs before acknowledging. An interrupted append can therefore leave only an *unterminated* fragment, which was never acknowledged and so was never acted on. Ignoring it is safe.

A *terminated* line that does not verify is never treated as torn, even when it is the final line. It might be an acknowledged entry that was later damaged, and dropping it could hide a started step. `OperationStatus.corruptionInMiddle()` tells damage before the final line apart from damage to the final line.

`OperationStatus.writable` is true only for an `INTACT` journal whose plan and entries are all at a schema version this reader implements.

**Discarding a torn tail.** `OperationJournals.discardTornTail` is explicit and needs the lock. It never runs automatically.

1. It first preserves the torn bytes in `journal.torn-<n>-<first 16 hex of their SHA-256>.bin`, where `n` is the number of trusted entries.
2. It then atomically replaces the journal with its complete lines, after checking the journal's exact current checksum.

It refuses anything that is not `TORN_TAIL`.

## 7. Recovery recommendations (from the last durable state)

| Durable state | Action | Alternatives | Partial effects possible |
|---|---|---|---|
| PLANNED | `CONFIRM_BACKUP` | `CANCEL` | no |
| BACKED_UP | `START_APPLY` (re-check preconditions and approval first) | `CANCEL` | no |
| APPLYING, no step in progress, steps remain | `RESUME_APPLY` (`nextStep`) | `FAIL_AND_ROLLBACK` | if any step ever started |
| APPLYING, step in progress, retry-safe, attempts < 8 | `RETRY_STEP` (`nextStep`) | `FAIL_AND_ROLLBACK` | yes |
| APPLYING, step in progress, not retry-safe or attempts exhausted | `FAIL_AND_ROLLBACK` | `MANUAL_INSPECTION` | yes |
| APPLYING, every step completed | `START_VALIDATION` | `FAIL_AND_ROLLBACK` | no |
| VALIDATING, checks remain | `RESUME_VALIDATION` (`nextCheck`) | `FAIL_AND_ROLLBACK` | no |
| VALIDATING, every check passed | `RECORD_COMPLETION` | `FAIL_AND_ROLLBACK` | no |
| FAILED | `ROLLBACK` | `MANUAL_INSPECTION` | if any step ever started |
| ROLLING_BACK, ROLLBACK_FAILED, attempts < 8 | `RETRY_ROLLBACK` | `MANUAL_INSPECTION` | yes |
| ROLLING_BACK, ROLLBACK_FAILED, attempts exhausted | `MANUAL_INSPECTION` | — | yes |
| COMPLETED, ROLLED_BACK, CANCELLED | `NONE` | — | no |

- Every action except `NONE` has `requiresConfirmation = true`. Nothing is ever resumed automatically.
- Every offered action names journal events that the state machine accepts. Property tests check this.

## 8. Compatibility

| Writer → reader | Plan | Journal |
|---|---|---|
| Same major, same or older minor | Unknown members are rejected (`sidecar.operation.unknown_member`) | Same |
| Same major, newer minor | Readable. Unknown members are recorded and included in the digest. **Not executable** by this reader (`DecodedPlan.executable() == false`). | Readable. Entry checksums cover the unknown members. **Not writable** by this reader. |
| Other major, or other `format` | Rejected (`unsupported_version`, `wrong_format`) | Rejected; the line is treated as corrupt |

Plans and journals are never rewritten, so no read-modify-write cycle can drop unknown members.

## 9. Error codes

`sidecar.operation.*`:

| Group | Codes |
|---|---|
| Format | `wrong_format`, `unsupported_version`, `newer_minor_version` (warning), `missing_member`, `wrong_type`, `unknown_member`, `invalid_value` |
| Integrity | `entry_checksum_mismatch`, `chain_broken`, `operation_mismatch`, `torn_tail` (warning), `corrupt_entry`, `plan_changed`, `plan_unreadable`, `not_recorded` (warning), `too_large` |
| Appending | `sequence_conflict`, `handle_stale`, `handle_broken`, `event_rejected`, `not_writable`, `already_exists`, `not_found` |

`operation.*` (state-machine rules):

- `transition.illegal`
- `sequence.mismatch`
- `state.mismatch`
- `plan.mismatch`
- `plan.changed`
- `precondition.changed`
- `backup.mismatch`
- `step.out_of_order`
- `step.retry_not_allowed`
- `attempt.mismatch`
- `attempts.exhausted`
- `step.incomplete`
- `check.out_of_order`
