# MCOneWorld Tool — module instructions

This file inherits the repository-root [`AGENTS.md`](../AGENTS.md) completely. It adds constraints for `tool/`; it overrides nothing. The plan is [`docs/architecture/tool-implementation-plan.md`](../docs/architecture/tool-implementation-plan.md), and its milestones T0–T15 are this module's prompt sequence: do them in order, each gated by its tests.

## 1. What the Tool is

MCOneWorld Tool is the offline desktop application, a JavaFX 25 app on Java 25. It works on a world folder it is given: normally a copy downloaded from the server, which is uploaded back, with its sidecar, afterwards. It:

- opens a Minecraft world and its sidecar (`mconeworld-data/`)
- initializes a sidecar, importing existing chunks as legacy provenance
- reconciles the sidecar after external trimming (MCA Selector)
- creates and verifies backups
- plans and creates seasons, including the End archive (ADR 0010) and offline activation (ADR 0011)
- shows a planning map and validation reports

It shows **absolute coordinates only**. Season-local coordinates belong to the plugin and the client.

It is **completely offline**: it never talks to a server or the network. It operates on Minecraft world files; the Paper plugin is kept in mind (it applies a pending activation, and Paper's per-world files are respected), but nothing the Tool does requires Paper.

## 2. Modules and dependency direction

| Module | Contents | May depend on |
|---|---|---|
| `tool/world` | World-file access: a bounded read-only NBT reader for `level.dat`, the dimension layout (vanilla and Paper), region headers, `session.lock`, and the End archive. Vendored MCA Selector code (ADR 0009). | `shared:domain` |
| `tool/core` | Headless services: session, init, reconcile, backup, season planner and transition, map model, CLI | `tool/world`, `shared:domain`, `shared:sidecar` |
| `tool/app` | JavaFX views and view-models | `tool/core` only |

- `shared/*` never depends on `tool/*`.
- `tool/core` and `tool/world` never depend on JavaFX.
- Nothing in `tool/` reads `offline-tool/`; copy from it instead.

## 3. Safety rules specific to the Tool

1. **Read-only by default.** The only world files the Tool may write in v1 are the End dimension directory's files: archived, then removed (ADR 0010). In 26.1+ that directory includes the dragon-fight state. The Tool never writes NBT, `level.dat`, player data, or any other dimension. Every other world write is out of scope until a new ADR allows it.
2. **The pinned-version gate.** World writes run only when the world's data version equals the pinned one (26.2, data version 4903). Every other version is inspect-only, and the UI says why.
3. **The world must not be open in Minecraft.**
   - Any world write needs `session.lock` to be free, plus the sidecar writer lock (`SidecarWriter`). This is a cheap local guard: a game or server that still had the folder open would overwrite the Tool's changes.
   - Reads of an open world are allowed, and are labelled "may be stale".
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
- **Real worlds.** Behaviour on a real 26.2 world, such as where the dragon-fight data lives and how Minecraft treats a missing End, is established from real save files (read-only, or on temporary copies through `localWorldTest`) and from Mojang's and Paper's documentation. It is never guessed (root §18).
- **No server or network tests.** The Tool is offline: no test starts Minecraft or Paper, or uses the network.
- **No change to `offline-tool/`.** `git diff --stat offline-tool/` must stay empty.
