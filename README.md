# MCOneWorld

Repeatable Minecraft Java "season" resets that keep every earlier season in the same world. Each new season starts in distant, unused terrain, and old bases stay at their original absolute coordinates. See [`AGENTS.md`](AGENTS.md) for the product principles and invariants.

MIT-licensed. See [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md), which covers the attribution for the imported MCA Selector code.

## Repository layout

| Path | What it is | Status |
|---|---|---|
| [`shared/`](shared/README.md) | Platform-neutral Java libraries: `domain`, `sidecar`, `protocol`, `test-fixtures` | Release candidate `shared 0.1.0-rc.1`: prompts 00–11 complete. See the [release audit](docs/architecture/shared-release-audit.md). |
| [`tool/`](tool/README.md) | MCOneWorld Tool: the offline desktop application (JavaFX) | In progress. See the [implementation plan](docs/architecture/tool-implementation-plan.md); T0–T4 done: decisions, build scaffold, read-only world access, session status, shared format additions (manifest 1.1, backups). |
| `offline-tool/` | MCA Selector 2.9, imported with `git subtree` from `Querz/mcaselector`. It is used unmodified for chunk trimming; the Tool vendors selected pieces of it (ADR 0009). | Separate Gradle build. Untouched upstream code. |
| `paper-plugin/`, `fabric-client/` | MCOneWorld Paper and MCOneWorld Client | Not started |
| `build-logic/` | Gradle convention plugins (`mconeworld.java-conventions`, `mconeworld.shared-module`) and architecture guards | |
| [`docs/`](docs/) | [Architecture](docs/architecture/), [decisions (ADRs)](docs/decisions/), [formats](docs/formats/), [compatibility matrix](docs/compatibility/version-matrix.md) | |

## Building

Requirements:

- **JDK 25** installed locally. It is not auto-downloaded.
- For `offline-tool/` (MCA Selector, used unmodified for trimming), JDK 21.

```bash
./gradlew sharedCheck toolCheck                         # all shared and Tool checks (what CI runs)
./gradlew assemble                                      # reproducible jars in shared/*/build/libs
./gradlew :tool:app:run                                 # start MCOneWorld Tool
(cd offline-tool && ./gradlew build)                    # MCA Selector, its own wrapper
```

## Updating dependencies

Versions live only in `gradle/libs.versions.toml`. Builds use **strict** dependency locking and SHA-256 dependency verification, so an unlocked or unverified artifact fails the build. After changing a version, or adding a dependency (including project-to-project dependencies, which change transitive classpaths), run:

```bash
./scripts/refresh-dependency-state.sh
```

The script regenerates every lockfile and `gradle/verification-metadata.xml` from a clean copy of the tree. A copy is required because up-to-date tasks skip resolution, which would silently produce incomplete metadata. Review the diff of the verification metadata before committing: each new checksum is a newly trusted artifact.

## For agents

Read these first:

1. [`AGENTS.md`](AGENTS.md)
2. the nearest module `AGENTS.md`
3. [`docs/decisions/`](docs/decisions/)
4. [`docs/compatibility/version-matrix.md`](docs/compatibility/version-matrix.md)

The shared-library API reference is in [`shared/README.md`](shared/README.md).
