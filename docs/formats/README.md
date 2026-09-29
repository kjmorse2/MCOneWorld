# Persistent and wire format specifications

Each MCOneWorld format is versioned independently of the application release (`shared/AGENTS.md`, "Format evolution"). Every format change updates its specification here, together with golden fixtures and either a migration or an explicit incompatibility error.

| Format | Version | Spec | Introduced |
|---|---|---|---|
| Sidecar manifest (JSON) | 1.0 | [manifest-v1.md](manifest-v1.md), example [examples/manifest-v1-example.json](examples/manifest-v1-example.json) | Prompt 04 |
| Chunk index | — | *pending* | Prompts 08–09 |
| Operation plan / journal | — | *pending* | Prompt 07 |
| Client/server protocol | — | *pending* | Prompt 10 |

## Schema questions raised in Prompt 03, resolved in Prompt 04

- **Backup location.** *Resolved:* each manifest backup entry has a required `path` under `backups/`. The domain `BackupRecord` stays location-free.
- **Season envelopes.** *Resolved:* they are not stored in manifest 1.0. They are derived from the chunk index, and a later minor version may add a cache.
- **Provenance storage.** *Resolved:* provenance lives in chunk indexes, which are referenced through `files` (role `chunk-index`).
- **Timestamp precision.** *Resolved:* shortest exact ISO-8601 UTC (`ISO_INSTANT`), so nothing is silently truncated.
- **Nullable vs absent.** *Resolved:* always present, with JSON `null` meaning none. Absent is an error.
- **Enum persistence.** *Resolved:* constant names are used verbatim, and new constants require a major version.
