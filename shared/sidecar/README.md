# shared:sidecar

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-sidecar` · Package: `io.github.kjmorse2.mconeworld.sidecar`

**Responsibility.** Everything under the `mconeworld-data/` directory next to a world save:

- the deterministic JSON manifest codec (ADR 0003)
- explicit schema migrations
- storage safety: atomic writes, checksums, locking, path confinement
- the `ChunkIndex` abstraction

It operates on a caller-supplied root and never rewrites anything on a read-only open.

**May depend on:** `shared:domain`, and internal-only libraries accepted by ADR. These must never appear in the public API.

**Forbidden:** `shared:protocol`, `shared:test-fixtures` in production code, and every platform dependency.

**Status:** scaffold only (Prompt 01). Content arrives in Prompts 04 to 06, 08, and 09.
