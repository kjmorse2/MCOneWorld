# Shared-project release audit (Prompt 11)

- Date: 2026-10-01
- Audited commit: `3da8d179`. This document is added on top of it.
- Scope: `shared:domain`, `shared:sidecar`, `shared:protocol`, `shared:test-fixtures`, and `build-logic`.
- Verdict: **ready for platform work.**
  - Every required check passes.
  - Reproducibility is demonstrated.
  - The persistent and wire contracts are documented and pinned.
  - No critical or high data-integrity issue remains open.
  - Three defects were found and fixed, each with a regression test (§2).
- Candidate designation: **`shared 0.1.0-rc.1`** (§8). No Minecraft, Paper, or Fabric runtime support is claimed.

## 1. Validation performed

| Requirement | What was run | Result |
|---|---|---|
| Formatting and static analysis | `spotlessCheck`; `javac -Xlint:all -Werror`; Error Prone 2.50.0 and NullAway (JSpecify mode); `verifySharedDependencies` and `verifySharedBytecode` (`jdeps`), all inside `sharedCheck` | Pass |
| Complete suite from a clean state | Fresh `git clone`, then `./gradlew --no-daemon sharedCheck assemble sharedTestSummary` | **1,234 tests, 0 failures, 0 skipped** (domain 778, sidecar 398 plus the 128 MiB-heap memory test, protocol 52, test-fixtures 5) |
| Property and model tests with recorded seeds | Every jqwik property class (22 properties), 10 runs with fresh random seeds | All pass. 311,000 tries in total. Seeds per run in [`audit-evidence/property-seeds-prompt11.tsv`](audit-evidence/property-seeds-prompt11.tsv). |
| Repeated fault injection, to find flakes | Commit, chunk-index fault, corruption, and lifecycle tests; journal store, operation writer, migration apply, lock, and storage behaviour tests: 172 tests × 20 runs | 3,440 executions, 0 failures ([`audit-evidence/fault-runs-prompt11.txt`](audit-evidence/fault-runs-prompt11.txt)) |
| Benchmarks against the accepted budgets | `benchChunkIndex --candidates prod-rows4 --datasets all --probes` on the real disk | All gates and targets pass, with no regression against Prompt 09 ([`results-prompt11.md`](../benchmarks/chunk-index/results-prompt11.md)). A first run on tmpfs was discarded, because a free `fsync` invalidates B8 and B10. |
| Two clean builds, artifacts compared | Two independent clones at different paths, then a `clean assemble` in one of them | All four jars are byte-identical (SHA-256 below) |
| Dependency and vulnerability reports | `scripts/dependency-report.py --osv`, run 2026-10-01 | 104 locked artifacts. Production needs only `jspecify` 1.0.1 and `jackson-core` 3.2.3 (plus its BOM), all Apache-2.0. **0 OSV advisories.** |
| Golden fixtures through current readers | Each format's golden test (manifest, operation plan and journal, chunk index and export, protocol vectors), plus the new `GoldenInventoryTest` | All 14 golden files decode with today's readers and match their `.meta` checksums |
| Temporary consumer examples | `scripts/check-consumer-examples.sh`: an offline-tool example (`--release 21`) and Paper and Fabric adapter examples (`--release 25`), compiled with `-Xlint:all -Werror` against the shared jars only, then run | Pass. No platform dependency is needed or present. |

Jar SHA-256 values (version `0.1.0-SNAPSHOT`, commit `3da8d179`):

```text
b6c523d7cdaecda99ca57ad3763fdb8b62c2ba76702b1ed17b144d84977df878  mconeworld-shared-domain-0.1.0-SNAPSHOT.jar
b8c0c379d4bd37e31568010b33d5ebdce363d666b930ed133d5db07b956a4c94  mconeworld-shared-sidecar-0.1.0-SNAPSHOT.jar
2e4157783b9000708a256799ddadc6204b76a0cf2903d3b6cd13b8bf53bbe854  mconeworld-shared-protocol-0.1.0-SNAPSHOT.jar
bb6f3349b2f46d34c7d9a5da463e4c6884e011e31eb3b2a9e3ad94503bcc962a  mconeworld-shared-test-fixtures-0.1.0-SNAPSHOT.jar
```

**Tooling note.** Don't run `./gradlew --rerun-tasks` across the whole build. It recompiles the included `build-logic` build while its plugin classes are loaded, and `validatePlugins` and the build-logic tests then fail with `NoClassDefFoundError`. This is an artifact of the invocation, not a defect. Use a clean clone, `clean`, or `--rerun` on specific test tasks.

## 2. Defects found and fixed

| Id | Severity | Defect | Fix and regression test |
|---|---|---|---|
| D1 | Medium (robustness and diagnostics; no data loss) | `ManifestDecoder` reported an unknown member under the fallback name `_unknown_` whenever the member's name was not identifier-like (for example `a-b`, `1x`, or empty). That fallback breaks `IssueLocation`'s own field grammar, so decoding a corrupted or hand-edited manifest threw `IllegalArgumentException` instead of returning a coded `ManifestException`. A Paper plugin would have failed on enable instead of disabling mutating features with a diagnostic (root §13). Found by the new manifest fuzzer. | Such names are reported at `$[name]`, as the operation decoder already did. `ManifestRejectionTest.unknownMembersWithAnyNameAreReportedNotThrown`; `ManifestFuzzProperties` |
| D2 | Low (diagnostic format) | `ChunkIndexExport.Reader` claims to be strict, but it accepted non-canonical index ids (`UUID.fromString` is lenient), non-canonical times (offsets, trailing zeros), and duplicated or out-of-order records. The writer never emits any of these, and the planned migration check compares exports. | It now enforces the spec's canonical forms and strictly ascending `ChunkOrder` with no duplicates. `ChunkIndexLifecycleTest.theExportReaderAcceptsOnlyWhatTheWriterWrites` |
| D3 | Medium (durability) | `JdkStorageFileSystem.syncDirectory` turned *any* failure to open a directory into "unsupported". On Linux, a permission or I/O error then made the chunk-index and journal writers skip the directory sync silently, contrary to fail-closed (ADR 0004 allows this only on platforms that cannot sync directories). | Only Windows is unsupported now; elsewhere the error propagates. ADR 0004 is amended. `StorageBehaviourTest.aDirectoryThatCannotBeOpenedFailsTheSyncOutsideWindows` |

None of the three changes an accepted format. D2 makes the reader accept only what the format spec already required.

## 3. Audit areas

| # | Area | Findings | Evidence and changes |
|---|---|---|---|
| 1 | Public API surface and dependency direction | The public types per package are intended. `sidecar.internal.json` is public only because sibling packages need it, and its package doc says "Internal, not API". `TextRules` is public because `domain.operation` uses it. Module direction is enforced by the build. | `verifySharedDependencies`, `verifySharedBytecode`, `SharedArchitectureRulesTest`, and every module's `ModuleContract` smoke test (now also covering `protocol.codec`) |
| 2 | Determinism | Persisted ordering comes from sorted structures (`TreeMap` catalogs, canonical JSON with code-point key order). The `HashMap`s and `HashSet`s found serve only lookup and membership. | Golden byte tests for every format; the protocol insertion-order test; reproducible jars (§1) |
| 3 | Format and protocol version separation | Every persistent format and the protocol carry their own version. The library version is never used as a format version. | [`docs/compatibility/shared-contracts.json`](../compatibility/shared-contracts.json), pinned to the code by `SharedContractsTest` |
| 4 | Unknown fields and unsupported versions | A newer minor version is preserved (manifest) or recorded (plans and journals). The chunk index opens read-only at a newer minor. Every major is refused. The protocol keeps unknown extensions opaque. | Existing compatibility tests; fuzzers (§4) |
| 5 | Coordinates, overflow, allocation, identifiers | Every file read is bounded before allocation (manifest, plan, journal, transaction and lock records, index files, export lines). The protocol checks lengths against both the limit and the remaining bytes. | Coordinate properties; `ProtocolCodecTest.hugeClaimedLengthsFailWithoutAllocatingThem`; `ChunkIndexMemoryTest` |
| 6 | Paths, symlinks, locks, interruption | `SidecarPath` is a strict, lowercase, portable grammar. Symlinks are refused at use. In-JVM lock contention (`OverlappingFileLockException`) maps to `LOCK_CONTENDED`, and a lost OS lock is detected by `verifyHeld`. Interruption had no tests before the audit. | **New:** `CommitFaultTest.anInterruptAtAnyStageLeavesTheOldOrTheNewRevision`, `ChunkIndexInterruptTest` (every step of commit and checkpoint, plus an interrupted reader), `OperationJournalStoreTest…anInterruptedAppendFailsCleanlyAndTheJournalContinues`. D3 fixed. |
| 7 | Migration source preservation | A migration writes a new manifest beside a verified backup and never runs implicitly. | `MigrationApplyTest` (part of the 20-run repetition) |
| 8 | Operation recovery | Nothing resumes automatically. A torn tail is discarded only by an explicit call. Corruption in the middle of a journal is reported, never repaired. | Journal store tests; **new** `OperationFuzzProperties` (plan, re-sealed journal lines, whole journals on disk) |
| 9 | Chunk index correctness and limits | Differential and property tests against the reference pass. Resource limits hold. | Existing suites; 10 property runs; benchmark gates B1–B3 |
| 10 | Protocol fuzzing | Decoding throws only `ProtocolException`, and `encode(decode(b)) == b` for every accepted input. | `ProtocolFuzzProperties` (10 × 4 properties) |
| 11 | Licenses and attribution | Every source file carries the SPDX MIT header, and every jar embeds `META-INF/LICENSE`. No shared code references MCA Selector, and `offline-tool/` has not changed since its import. All production dependencies are Apache-2.0. GPL-2.0 with Classpath Exception (`dataflow-*`) and EPL-2.0 (JUnit, jqwik, JaCoCo) appear only in build and test tooling and are never shipped. | `scripts/dependency-report.py`; `NOTICE.md` now covers shading `jackson-core` |
| 12 | Documentation against code | No stale markers. Three docs were corrected: the version matrix called jspecify compile-only (it is `api`), the Prompt 00 plan still said "planning only", and the README called the shared project "in progress". | This commit |

## 4. Hardening added

- **Decoder fuzzing over the sidecar.** It covers manifests, plans, journal lines (re-sealed with a valid checksum so the fuzzing reaches the field checks), whole journals on disk, chunk-index exports, and lock and transaction records. It uses `fixtures.Mutations`, a deterministic mutator: one seed gives one mutant, so jqwik's recorded seed reproduces any failure.
- **`GoldenInventoryTest`.** Every golden file needs a `.meta` with its format, version, and matching SHA-256. The protocol metas were renamed `*.bin.meta` for uniformity.
- **`shared-contracts.json` and `SharedContractsTest`.** The machine-readable list of format and protocol versions; the test keeps it equal to the code.
- **`./gradlew sharedTestSummary`.** Writes `build/reports/shared-test-summary.json` (format `mconeworld-shared-test-summary` 1): test counts per module and task, and jar SHA-256 values. CI now runs it.
- **`scripts/check-consumer-examples.sh`.** Runs the three consumer examples in `scripts/consumer-examples/`. CI now runs it too.
- **`scripts/dependency-report.py`.** Lists the license of every locked artifact and, with `--osv`, checks advisories.

## 5. Deferred risks

None of these is critical or high. Each has an owner or trigger.

| Id | Risk | Why deferred | Trigger or remedy |
|---|---|---|---|
| R1 | The chunk-index export has no trailer or record count. A truncation exactly at a line boundary reads as a shorter export. | The export is diagnostic and never imported as the index. Adding a trailer changes an accepted format. | Export 1.1 through ADR 0007, before the first index migration relies on comparing exports |
| R2 | `SidecarPath` doesn't reserve `com0`/`lpt0`, which newer Windows documentation lists. | Sidecar paths are generated by code, never by users, and tightening the grammar of an accepted format needs its own revision. | The next storage-format revision, or the first Windows support work |
| R3 | Windows and macOS are untested. Directory fsync is unsupported on Windows (and reported), and lock semantics differ. | No support is claimed (`sidecar-storage-v1.md`). | Before any desktop release of the Tool: a CI matrix job per OS |
| R4 | The chunk-index migration tests M3–M5 and M7 are not executed. | No index format 2.0 exists to migrate to (`chunk-index-test-plan.md` §5). | The first index major version |
| R5 | Vulnerability checking is a manual script that queries osv.dev. It is not a CI gate (it needs the network), and it does not scan the Gradle distribution itself. | No scanner was approved in ADR 0002. The OSV check is the least invasive option that works. | The owner may adopt OWASP dependency-check or osv-scanner in CI. Rerun the script whenever a pin changes. |
| R6 | The writer-lock and staging-transaction decoders accept lenient UUID and time forms. | Only MCOneWorld writes these files, and anything they cannot decode is treated as unreadable, which refuses or blocks. | Fold in with the next storage-format revision |
| R7 | Performance evidence comes from one machine (Ryzen 7800X3D, ext4, 16 ms `fsync`). | Budgets are machine-relative. | Rerun the benchmarks on the Paper host class before claiming server performance |

## 6. Platform integration assumptions

The Tool, Paper, and Fabric projects must uphold these. They are not enforced by the shared code.

1. **One writer per sidecar.** The sidecar sits on a local filesystem with atomic rename and POSIX advisory locks, never a network share. Paper holds one `SidecarWriter` for its lifetime. The offline tool gets `LOCK_CONTENDED` while the server runs, and must refuse world writes then.
2. **No history over an existing world without consent.** `ChunkIndexStore.create` doesn't require a manifest. Platforms establish the sidecar explicitly first, and never create history over an existing world automatically (root §13).
3. **Threads.** Heavy I/O runs off the Paper main thread. An interrupted or failed write leaves its handle broken (`handle_broken`, or a journal or lock code). Close it and reopen; never retry on the same handle.
4. **Protocol adapters.**
   - Copy custom-payload bytes into a `byte[]` and call `ProtocolCodec`.
   - On `ProtocolException`, log the code and drop the message. Never disconnect.
   - Answer at most one hello per connection, and send contexts only to clients that sent one.
   - The client runs `Negotiation.checkReply` on every context.
5. **Shading.** If a platform jar shades `jackson-core`, it relocates the package and carries jackson's `LICENSE` and `NOTICE`.
6. **Java.** The shared jars are Java 21 bytecode. Paper and Fabric compile at 25, and the offline tool at 21.
7. **Time.** All time comes from an injected `Clock`. Platforms pass the server or system clock; tests pass fixed clocks.
8. **Support claims.** `shared-contracts.json` lists no Minecraft support. The first claim needs the recorded validation suite of root §5 and §17.

## 7. Persistent and wire contracts

| Contract | Version | Specification |
|---|---|---|
| Sidecar manifest | 1.0 | [`manifest-v1.md`](../formats/manifest-v1.md) |
| Writer lock and staging transaction | 1 | [`sidecar-storage-v1.md`](../formats/sidecar-storage-v1.md) |
| Migration report | 1 | ADR 0005 |
| Operation plan and journal | 1.0 | [`operation-journal-v1.md`](../formats/operation-journal-v1.md) |
| Chunk index and its export | 1.0 | [`chunk-index-v1.md`](../formats/chunk-index-v1.md) |
| Client protocol | layout 1, version 1 | [`protocol-v1.md`](../formats/protocol-v1.md) |

The same list is in machine-readable form in [`shared-contracts.json`](../compatibility/shared-contracts.json).

## 8. Candidate designation

`shared 0.1.0-rc.1`: the four modules at the commit that adds this document, exposing the contracts in §7.

- **Status.** The build keeps `version=0.1.0-SNAPSHOT` until the owner tags a release. Releasing means setting `version=0.1.0` in `gradle.properties`, rerunning §1 from a clean clone, and tagging. Nothing is pushed or published by the agent.
- **API stability within 0.1.x:**
  - Persistent formats and the protocol follow their own version rules.
  - Public Java types may still change before 1.0, but every such change is recorded in `shared/README.md`. Platform projects pin an exact shared version.
