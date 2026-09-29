# Architecture Decision Records

Each ADR records context, the selected option, alternatives, consequences, migration impact, and how the decision is tested (root `AGENTS.md` §21).

Status values: `Proposed` → `Accepted` | `Rejected` | `Superseded by NNNN`. Only the project owner moves an ADR out of `Proposed`.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-java-package-and-group-id.md) | Java package prefix and Maven group ID | Accepted |
| [0002](0002-build-and-test-toolchain.md) | Build, test, and quality toolchain for shared modules | Accepted (amended) |
| [0003](0003-sidecar-json-stack.md) | Sidecar JSON library and canonical encoding | Accepted |
| [0004](0004-sidecar-storage-durability-and-locking.md) | Sidecar storage durability, atomicity, and locking | Accepted |
| [0005](0005-manifest-migration-guarantees.md) | Manifest schema migration guarantees | Accepted |

Decisions reserved for later milestones and **not** decided here: the chunk-index encoding (Prompt 08), the offline tool's relationship to MCA Selector (root §22), and the other deferred choices in root §22.
