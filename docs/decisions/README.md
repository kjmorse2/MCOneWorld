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

Decisions reserved for later milestones and **not** decided here: the offline tool's relationship to MCA Selector (root §22), and the other deferred choices in root §22. The chunk-index encoding was decided by ADR 0007.
