# MCOneWorld

Repeatable Minecraft Java "season" resets that keep every earlier season in the same world. Each new season starts in distant, unused terrain, and old bases stay at their original absolute coordinates. See [`AGENTS.md`](AGENTS.md) for the product principles and invariants.

MIT-licensed. See [`LICENSE`](LICENSE) and [`NOTICE.md`](NOTICE.md), which covers the attribution for the imported MCA Selector code.

## Repository layout

| Path | What it is | Status |
|---|---|---|
| [`shared/`](shared/README.md) | Platform-neutral Java libraries: `domain`, `sidecar`, `protocol`, `test-fixtures` | In progress (prompt sequence in `shared/prompts/`) |
| `offline-tool/` | MCA Selector 2.9, imported with `git subtree` from `Querz/mcaselector`. It will become MCOneWorld Tool. | Separate Gradle build. Untouched upstream code. |
| `paper-plugin/`, `fabric-client/` | MCOneWorld Paper and MCOneWorld Client | Not started |
| `build-logic/` | Gradle convention plugins (`mconeworld.java-conventions`, `mconeworld.shared-module`) and architecture guards | |
| [`docs/`](docs/) | [Architecture](docs/architecture/), [decisions (ADRs)](docs/decisions/), [formats](docs/formats/), [compatibility matrix](docs/compatibility/version-matrix.md) | |

## Building

Requirements:

- **JDK 25** installed locally. It is not auto-downloaded.
- For `offline-tool/`, JDK 21.

```bash
./gradlew sharedCheck                                   # all shared checks (what CI runs)
./gradlew assemble                                      # reproducible jars in shared/*/build/libs
(cd offline-tool && ./gradlew build)                    # the imported tool, its own wrapper
```

## Updating dependencies

Versions live only in `gradle/libs.versions.toml`. Builds use strict dependency locking and SHA-256 dependency verification. After changing a version:

```bash
./gradlew --no-configuration-cache resolveAndLockAll --write-locks
./gradlew --write-verification-metadata sha256 --no-configuration-cache resolveAndLockAll sharedCheck
```

Run the second command from a clean tree, with no `build/` or `.gradle/` directories in the repository or in `build-logic/`. Otherwise up-to-date tasks skip resolution and the metadata comes out incomplete. Review the diff of `gradle/verification-metadata.xml` before committing.

## For agents

Read these first:

1. [`AGENTS.md`](AGENTS.md)
2. the nearest module `AGENTS.md`
3. [`docs/decisions/`](docs/decisions/)
4. [`docs/compatibility/version-matrix.md`](docs/compatibility/version-matrix.md)

The shared-library API reference is in [`shared/README.md`](shared/README.md).
