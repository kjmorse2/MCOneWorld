# Persistent and wire format specifications

Each MCOneWorld format is versioned independently of the application release (`shared/AGENTS.md`, "Format evolution"). Every format change updates its specification here, together with golden fixtures and either a migration or an explicit incompatibility error.

| Format | Version | Spec | Introduced |
|---|---|---|---|
| Sidecar manifest (JSON) | 1.0 | [manifest-v1.md](manifest-v1.md), example [examples/manifest-v1-example.json](examples/manifest-v1-example.json) | Prompt 04 |
| Sidecar storage layout, `writer.owner.json`, `transaction.json` | 1 | [sidecar-storage-v1.md](sidecar-storage-v1.md) | Prompt 05 |
| Migration report (`reports/migrations/<runId>.json`) | 1 | [ADR 0005](../decisions/0005-manifest-migration-guarantees.md) | Prompt 06 |
| Manifest 0.9 (**synthetic test fixture, never released**) | 0.9 | [manifest-v0.9-synthetic.md](manifest-v0.9-synthetic.md) | Prompt 06 |
| Operation plan (`operations/<id>/plan.json`) and journal (`journal.jsonl`) | 1.0 | [operation-journal-v1.md](operation-journal-v1.md), examples [plan](examples/operation-plan-v1-example.json), [journal](examples/operation-journal-v1-example.jsonl) | Prompt 07 |
| Chunk index | 1.0 **draft** | [chunk-index-v1-draft.md](chunk-index-v1-draft.md) (not normative until ADR 0007 is accepted) | Prompt 08 (proposal), Prompt 09 (normative) |
| Client/server protocol | — | *pending* | Prompt 10 |

## Schema questions raised in Prompt 03, resolved in Prompt 04

- **Backup location.** *Resolved:* each manifest backup entry has a required `path` under `backups/`. The domain `BackupRecord` stays location-free.
- **Season envelopes.** *Resolved:* they are not stored in manifest 1.0. They are derived from the chunk index, and a later minor version may add a cache.
- **Provenance storage.** *Resolved:* provenance lives in chunk indexes, which are referenced through `files` (role `chunk-index`). **Superseded by ADR 0007 (Accepted, owner decision D2):** the index is a mutable, reserved `chunk-index/` area with its own commit point. The manifest references it by format and index id, not by checksum, and operations pin its generation instead.
- **Timestamp precision.** *Resolved:* shortest exact ISO-8601 UTC (`ISO_INSTANT`), so nothing is silently truncated.
- **Nullable vs absent.** *Resolved:* always present, with JSON `null` meaning none. Absent is an error.
- **Enum persistence.** *Resolved:* constant names are used verbatim, and new constants require a major version.
