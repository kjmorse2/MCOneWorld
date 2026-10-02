# Architecture Decision Records

Each ADR records context, the selected option, alternatives, consequences, migration impact, and how the decision is tested (root `AGENTS.md` §21).

Status values: `Proposed` → `Accepted` | `Rejected` | `Superseded by NNNN`. Only the project owner moves an ADR out of `Proposed`.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-java-package-and-group-id.md) | Java package prefix and Maven group ID | Accepted |
| [0002](0002-build-and-test-toolchain.md) | Build, test, and quality toolchain for shared modules | Accepted (amended) |
| [0003](0003-sidecar-json-stack.md) | Sidecar JSON library and canonical encoding | Accepted |
| [0004](0004-sidecar-storage-durability-and-locking.md) | Sidecar storage durability, atomicity, and locking | Accepted (amended 2026-10-01, Prompt 11) |
| [0005](0005-manifest-migration-guarantees.md) | Manifest schema migration guarantees | Accepted |
| [0006](0006-operation-plans-and-journals.md) | Operation plans and durable journals | Accepted |
| [0007](0007-chunk-index-encoding.md) | Chunk-index encoding | Accepted (2026-10-01) |
| [0008](0008-client-protocol.md) | Client/server protocol, version 1 | Accepted (2026-10-01, delegated) |
| [0009](0009-tool-and-mca-selector.md) | MCOneWorld Tool and MCA Selector | Accepted (2026-10-02) |
| [0010](0010-end-reset-by-archive.md) | Resetting the End by archiving it | Accepted (2026-10-02) |
| [0011](0011-offline-season-activation.md) | Offline season activation with pending application | Accepted (2026-10-02) |
| [0012](0012-backup-format.md) | Backups in the sidecar | Accepted (2026-10-02); byte format in T4 |

Decided since the shared prompts: the chunk-index encoding (ADR 0007), the Tool's relationship to MCA Selector (ADR 0009), and the End strategy (ADR 0010). The other deferred choices in root §22 remain open.
