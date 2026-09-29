# MCOneWorld Shared-Project Prompt Sequence

Run these prompts in numeric order. Give the coding agent the repository root as its working directory and ensure the repository-root `AGENTS.md` and `shared/AGENTS.md` are present first.

Do not submit all prompts in one agent turn. Each prompt is a gated milestone. Review its report and require its acceptance criteria to pass before supplying the next prompt.

| Prompt | Milestone | Primary result |
|---|---|---|
| `00` | Discovery and decisions | Verified repository inventory and proposed ADRs |
| `01` | Shared build scaffold | Reproducible Gradle modules and quality gates |
| `02` | Coordinate domain | Checked identifiers and coordinate mathematics |
| `03` | Season domain | Season, provenance, validation, and lifecycle contracts |
| `04` | Manifest v1 | Deterministic JSON manifest and validation |
| `05` | Storage safety | Checksums, safe paths, locking, and atomic writes |
| `06` | Migration framework | Explicit, failure-safe schema migration pipeline |
| `07` | Operations | Immutable plans and durable journal state machine |
| `08` | Chunk-index decision | Benchmarked ADR and reference model |
| `09` | Chunk-index implementation | Streaming production index with differential tests |
| `10` | Client protocol | Versioned, bounded, deterministic wire messages |
| `11` | Hardening | Corruption, compatibility, documentation, and release audit |

Each prompt instructs the agent to stop if its prerequisites are absent or if a choice requires user approval. Do not let an agent silently skip a failed test to continue the sequence.

