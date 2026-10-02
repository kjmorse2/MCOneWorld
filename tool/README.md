# MCOneWorld Tool

The offline desktop application of MCOneWorld (JavaFX 25, Java 25). With the server stopped, it opens a world and its sidecar, initializes or reconciles the sidecar, creates verified backups, and creates new seasons: a distant origin and spawn, a fresh End, and offline activation.

- **Plan and status:** [`docs/architecture/tool-implementation-plan.md`](../docs/architecture/tool-implementation-plan.md)
- **Rules for agents:** [`AGENTS.md`](AGENTS.md)
- **Decisions:**
  - [ADR 0009](../docs/decisions/0009-tool-and-mca-selector.md): MCA Selector relationship
  - [ADR 0010](../docs/decisions/0010-end-reset-by-archive.md): End reset
  - [ADR 0011](../docs/decisions/0011-offline-season-activation.md): offline activation
  - [ADR 0012](../docs/decisions/0012-backup-format.md): backups

**Status:** T0, decisions and charter. The modules arrive in T1.

**Chunk trimming** (deleting unwanted chunks) is not part of the Tool. Use MCA Selector, kept unmodified in [`offline-tool/`](../offline-tool/), with the server stopped. Then open the world in the Tool and run **Reconcile**.
