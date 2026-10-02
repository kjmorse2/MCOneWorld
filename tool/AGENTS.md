# MCOneWorld Tool — module instructions

This file inherits the repository-root [`AGENTS.md`](../AGENTS.md) completely. It adds constraints for `tool/`; it overrides nothing. The plan is [`docs/architecture/tool-implementation-plan.md`](../docs/architecture/tool-implementation-plan.md), and its milestones T0–T15 are this module's prompt sequence: do them in order, each gated by its tests.

## 1. What the Tool is

MCOneWorld Tool is the offline desktop application, a JavaFX 25 app on Java 25. With the server stopped, it:

- opens a Minecraft world and its sidecar (`mconeworld-data/`)
- initializes a sidecar, importing existing chunks as legacy provenance
- reconciles the sidecar after external trimming (MCA Selector)
- creates and verifies backups
- plans and creates seasons, including the End archive (ADR 0010) and offline activation (ADR 0011)
- shows a planning map and validation reports

It shows **absolute coordinates only**. Season-local coordinates belong to the plugin and the client.

## 2. Modules and dependency direction

| Module | Contents | May depend on |
|---|---|---|
| `tool/world` | World-file access: `level.dat`, dimension layout, region headers, `session.lock`, the End archive and dragon-fight writers. Vendored MCA Selector code (ADR 0009). | `shared:domain`; the NBT library |
| `tool/core` | Headless services: session, init, reconcile, backup, season planner and transition, map model, CLI | `tool/world`, `shared:domain`, `shared:sidecar` |
| `tool/app` | JavaFX views and view-models | `tool/core` only |

- `shared/*` never depends on `tool/*`.
- `tool/core` and `tool/world` never depend on JavaFX.
- Nothing in `tool/` reads `offline-tool/`; copy from it instead.

## 3. Safety rules specific to the Tool

1. **Read-only by default.** The only world files the Tool may write in v1:
   - the End dimension's files (archived and then removed, ADR 0010)
   - the dragon-fight data (reset, ADR 0010)

   Every other world write is out of scope until a new ADR allows it.
2. **The pinned-version gate.** World writes run only when the world's data version equals the pinned one (26.2, data version 4903). Every other version is inspect-only, and the UI says why.
3. **The server must be stopped.**
   - Any world write needs `session.lock` to be free, plus the sidecar writer lock (`SidecarWriter`).
   - Reads while the server runs are allowed, and are labelled "may be stale".
4. **The full protocol.** Every sidecar or world mutation follows root §10 through a shared `OperationPlan` and an `OperationJournal`:
   - an `mconeworld:` operation type
   - a preview the user confirms
   - a verified backup before any world write
   - validation after the write
   - the manifest transaction as the commit point
   - a rollback path
5. **Nothing runs automatically.** Sidecar recovery, schema migration, discarding a torn tail, and deleting a partial backup each run only on an explicit user action.
6. **Use the shared codecs.** Never write sidecar files directly; go through `SidecarWriter`, `IndexFileWriter`, `OperationFileWriter`, or the backup writer (T4).
7. **Vendored code** keeps its attribution header (MCA Selector, MIT, © Querz, upstream commit `d31bee6a`). It is refactored to take its context explicitly, with no global state, and it meets the same quality gates as everything else.

## 4. Testing

- **Synthetic worlds only** in automated tests: build region headers and `level.dat` in code, with no Mojang assets. Copy fixtures to a temp directory before mutating them.
- **Fault and interruption coverage.** Every journaled operation is tested with a failure and with an interrupt at every step. Reuse the patterns of `shared`'s `ChunkIndexFaultTest`, `CommitFaultTest`, and `ChunkIndexInterruptTest`.
- **View-models** are plain Java and unit-tested. UI smoke tests run headless (Monocle).
- **Real worlds.** Behaviour on a real 26.2 world, such as where the dragon-fight data lives and End regeneration, is verified by a recorded manual procedure on a disposable copy. It is never guessed (root §18).
- **No change to `offline-tool/`.** `git diff --stat offline-tool/` must stay empty.
