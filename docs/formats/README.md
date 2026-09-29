# Persistent and wire format specifications

Each MCOneWorld format is versioned independently of the application release (`shared/AGENTS.md`, "Format evolution"). Every format change updates its specification here, together with golden fixtures and either a migration or an explicit incompatibility error.

| Format | Version | Spec | Introduced |
|---|---|---|---|
| Sidecar manifest (JSON) | — | *pending* | Prompt 04 |
| Chunk index | — | *pending* | Prompts 08–09 |
| Operation plan / journal | — | *pending* | Prompt 07 |
| Client/server protocol | — | *pending* | Prompt 10 |

## Open schema questions for Prompt 04 (from Prompt 03)

- **Backup location.** `BackupRecord` omits where the backup bytes live. The manifest must choose between a sidecar-relative path (`backups/<id>/…`) and an external locator, subject to the no-absolute-path rule.
- **Season envelopes.** Envelopes are derived. Decide whether the manifest caches them per season and dimension, with a derivation revision, or recomputes them from the chunk index.
- **Provenance storage.** `ChunkProvenance` is per chunk and belongs to the chunk index (Prompts 08–09), not the manifest. The manifest only references index files by path and checksum.
- **Timestamp precision.** `Instant` values need one canonical serialized precision. The proposal is milliseconds, UTC, with a `Z` suffix (ADR 0003).
- **Nullable vs absent.** `activeSeasonId`, `PortalMarker.destinationSeason`, `BackupRecord.operation`, and `BackupRecord.activeSeason` are nullable. The schema must specify whether "none" is encoded as JSON `null` or as an absent member, and use one form consistently.
- **Enum persistence.** `SeasonStatus`, `EvidenceSource`, `Confidence`, `ChunkProtection`, and `ArchiveCategory` constant names are already documented as persisted. The schema should use them verbatim, or define an explicit mapping.
