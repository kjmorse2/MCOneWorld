# MCOneWorld shared modules: quick reference

This is a quick reference for people and agents building **other** MCOneWorld components (offline tool, Paper plugin, Fabric client) on top of the shared libraries. The binding rules are in [`AGENTS.md`](AGENTS.md) and the root [`AGENTS.md`](../AGENTS.md). Decisions are in [`docs/decisions/`](../docs/decisions/).

## Modules

| Module | Artifact | Root package | Depends on | Used by |
|---|---|---|---|---|
| [`domain`](domain/README.md) | `mconeworld-shared-domain` | `io.github.kjmorse2.mconeworld.domain` | — | Tool, Paper, Client |
| [`sidecar`](sidecar/README.md) | `mconeworld-shared-sidecar` | `io.github.kjmorse2.mconeworld.sidecar` | `domain` | Tool, Paper (**never** Client) |
| [`protocol`](protocol/README.md) | `mconeworld-shared-protocol` | `io.github.kjmorse2.mconeworld.protocol` | `domain` | Paper, Client |
| [`test-fixtures`](test-fixtures/README.md) | `mconeworld-shared-test-fixtures` | `io.github.kjmorse2.mconeworld.fixtures` | all three | tests only |

Group ID: `io.github.kjmorse2.mconeworld` (ADR 0001).

## Guarantees consumers can rely on

- **Java 21 bytecode** (`--release 21`), built and tested on JDK 25. Consumers on Java 21 and on Java 25 can both load it. Each module's smoke test fails if this changes.
- **No platform types.** Two checks fail the build if any shared artifact references Paper, Bukkit, Fabric, Minecraft, Adventure, JavaFX, or MCA Selector:
  - a resolved-graph check
  - a `jdeps` bytecode check
- **Null-marked APIs.** Every package is JSpecify `@NullMarked`. Parameters and returns are non-null unless annotated `@Nullable`. JSpecify is an `api` dependency.
- **No serialization or database library types in public APIs.** Those stay internal (ADR 0003).
- **Reproducible jars.** Each jar carries `META-INF/LICENSE` (MIT).

## Public API

> Scaffold stage (Prompt 01): no public types yet. Each later prompt adds its API summary to this section.

## Building

```bash
./gradlew sharedCheck      # everything CI runs: tests, format, Error Prone/NullAway, architecture guards
./gradlew spotlessApply    # fix formatting (google-java-format + SPDX header)
./gradlew :shared:domain:test --tests '*Smoke*'   # narrow run
```

Requirements:

- A local **JDK 25**. Toolchains are never auto-downloaded.
- Gradle runs on a full JDK, because the bytecode guard uses `jdeps`.

`offline-tool/` is a separate build (`cd offline-tool && ./gradlew build`, on JDK 21).

## Rules that trip people up

- A new or changed dependency must be:
  - added to `gradle/libs.versions.toml`
  - locked (`./gradlew --no-configuration-cache resolveAndLockAll --write-locks`)
  - verified (`--write-verification-metadata sha256`, run from a clean copy of the tree)

  Strict locking and checksum verification reject anything else. See the root README.
- Production code in `sidecar` or `protocol` may never reference the other, or `test-fixtures`.
- `-Xlint:all -Werror` and Error Prone are on. Fix warnings; do not suppress them globally.
- Tests run with `user.timezone=UTC` and an `en_US` locale. Never read the wall clock or the default random source in code under test. Inject a `Clock` or a seeded random instead.
