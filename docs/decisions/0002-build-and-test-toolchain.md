# ADR 0002 — Build, test, and quality toolchain for shared modules

- Status: **Accepted**, with the amendments below
- Approval: accepted 2026-09-28 by the implementing agent under explicit owner delegation
- Date: 2026-09-27
- Milestone: Prompt 00; implemented by Prompt 01 after acceptance

## Context

- The repository has no root build. The only Gradle build is `offline-tool/`, the imported MCA Selector 2.9 subtree:
  - Groovy DSL, standalone wrapper on Gradle 9.1.0, `sourceCompatibility = 21`
  - Repositories: `mavenCentral()`, `jitpack.io`, and `plugins.gradle.org/m2` as a regular repository
  - No dependency locking, no dependency verification, no formatter, no static analysis
  - No tests: upstream removed them in `2bc0d929 "remove tests for now"`
- The root `AGENTS.md` requires:
  - Gradle multi-project builds with Kotlin DSL
  - Exact pins
  - Lockfiles
  - Reproducible archives
  - Clean-checkout builds
- Minecraft 26.x and Paper 26.x require Java 25. The offline tool targets Java 21 today.
- There is no CI that would run for this repository:
  - `offline-tool/.github/workflows/release.yml` sits inside the subtree, so GitHub never runs it.
  - It targets `master` and depends on upstream secrets.

## Proposed decision

### Build structure

- Add a new root Gradle build using Kotlin DSL with Gradle wrapper `9.8.0`, pinned with `distributionSha256Sum`.
- Include only the four shared subprojects: `:shared:domain`, `:shared:sidecar`, `:shared:protocol`, `:shared:test-fixtures`.
- **Do not include `offline-tool/` in Prompt 01.** It remains an independent build with its own wrapper, for two reasons:
  - Converting it to Kotlin DSL or editing its build file would conflict with every future `git subtree pull` from upstream.
  - Integrating it later, through `includeBuild("offline-tool")` or a thin adapter module, is a separate decision (see root §22, "MCA Selector relationship").
- Use a version catalog at `gradle/libs.versions.toml` with exact versions only.
- Enable dependency locking (`dependencyLocking { lockAllConfigurations() }`) and commit the lockfiles.
- Enable dependency verification (`gradle/verification-metadata.xml`, SHA-256), generated once and reviewed.
- Allow only `mavenCentral()` and `gradlePluginPortal()` (for plugins), declared centrally through `dependencyResolutionManagement` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.
- Shared build logic lives in an included `build-logic/` build using precompiled convention plugins, not `allprojects {}`.
- Make archives reproducible: `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true`.

### Java

- Toolchain JDK 25 (Temurin 25.0.4+1 or the local OpenJDK 25.0.4).
- Compile shared modules with `options.release = 21`, so that the Java 21 offline tool can consume them unchanged. Paper and Fabric (Java 25) can also consume Java 21 bytecode. Java 21 already provides records, sealed types, and pattern-matching `switch`, which covers what the shared domain needs.
- Run the tests on JDK 25. A matrix job on JDK 21 is optional and can be added with CI.

### Testing

- JUnit Jupiter via `junit-bom` **`5.14.4`**, including `junit-jupiter-params` for boundary tables.
  - *Amendment (2026-09-28):* the proposal said 6.1.3. However, jqwik 1.10.1's published metadata requires JUnit Platform `1.14.4`, which is the JUnit 5 line, and running jqwik's engine on Platform 6 is untested. JUnit 5.14.4 (released 2026-04-26) aligns both engines on one platform version. Revisit when jqwik publishes a Platform-6 build.
- AssertJ `3.27.7`.
- jqwik `1.10.1` for property tests. It is maintained: the last release was 2026-05-29, the repository was pushed 2026-09-23, and it is not archived. Its engine runs on the JUnit Platform next to Jupiter.
- Tests must be deterministic:
  - Fixed `user.timezone=UTC` and `user.language=en`
  - Injected `Clock` and random sources
  - `@TempDir` for filesystem tests
  - Recorded jqwik seeds on failure (`jqwik.database` kept under `build/`)

### Quality gates (all fail the build)

- Spotless `8.10.3` with google-java-format `1.36.1`, applied to `shared/**` and `build-logic/**` only. Never apply it to `offline-tool/**`: that code uses upstream tab style, and reformatting it would destroy subtree merges.
- Error Prone `2.50.0` via `net.ltgt.errorprone` `5.1.1`, with NullAway `0.14.2` in JSpecify mode.
- JSpecify `1.0.1` `@NullMarked` package annotations.
- `javac -Xlint:all -Werror`.
- JaCoCo reports as a diagnostic only, with no coverage threshold gate (`shared/AGENTS.md`: "coverage as a diagnostic").
- Module-dependency guard: a build check that fails if a shared module resolves any artifact from these groups:
  - `io.papermc`
  - `org.bukkit`
  - `net.fabricmc`
  - `com.mojang`
  - `net.minecraft`
  - `org.openjfx`
  - `net.querz`
  - `net.kyori` (Adventure)
  - any `paperweight` or mappings artifact

  The same guard enforces `shared/AGENTS.md`'s intra-shared dependency direction.

### Benchmarks

- JMH `1.37` via `me.champeau.jmh` `0.7.3`, in a separate `jmh` source set, used first in Prompt 08. JMH is GPL-2.0 with the Classpath Exception. It is build-time only, is never shipped, and is never on a production classpath.

### CI

- Proposed: a root `.github/workflows/shared.yml` that runs `./gradlew check` on JDK 25 on Linux. It uses no secrets and has read-only permissions. This needs owner approval, because it publishes to GitHub Actions.

## Alternatives considered

| Alternative | Reason not proposed |
|---|---|
| Put the offline tool into the root multi-project build now | Requires editing the upstream Groovy build: merge conflicts, and it prejudges the deferred MCA Selector decision |
| Shared modules on `--release 25` | Would force a Java 25/JavaFX 25 upgrade of the offline tool before any tool integration work |
| Palantir Java Format | Also viable. google-java-format has the larger user base and a stable Spotless integration. This is a stylistic choice the owner may override. |
| Checkstyle `14.3.0` in addition | Mostly redundant with the formatter plus Error Prone. LGPL-2.1. Can be added later for specific rules. |
| SpotBugs | Overlaps with Error Prone and NullAway, and adds build time. Reconsider in Prompt 11. |
| Maven | Root `AGENTS.md` mandates Gradle with Kotlin DSL |

## Consequences

- Two wrappers coexist until the offline-tool integration decision: root on 9.8.0 and `offline-tool/` on 9.1.0. This is documented rather than hidden.
- Error Prone needs `--add-exports`/`--add-opens` JVM arguments for javac on JDK 25. The `net.ltgt.errorprone` plugin configures these, and Prompt 01 verifies it on the pinned JDK.
- Lockfiles and verification metadata must be regenerated whenever a pin changes, which is intentional friction.

## Resolved open points (2026-09-28)

- Q3: shared bytecode is `--release 21`.
- Q5: root CI workflow **added**. The workflow file lives in the repository; nothing is pushed by the agent.
- Q7: formatter is google-java-format.
- Q9: the `shared/{domain,sidecar,protocol,test-fixtures}` layout is confirmed. It overrides the illustrative `shared/schema` in root `AGENTS.md` §4, which that section allows ("exact directory names may evolve").
- Q10: a local JDK 25 is required. Auto-download is disabled with `org.gradle.java.installations.auto-download=false`, so a missing JDK fails fast with Gradle's toolchain error.
- Q11: dependency verification is accepted (SHA-256).
- JPMS `module-info.java` is **not** approved and not introduced (Prompt 01 §7).

## Migration impact

None on persistent data. This ADR affects only build output.

## How this is tested

Prompt 01's acceptance criteria:

- `./gradlew check` passes from a clean clone with `--offline` after one warm-up.
- Two consecutive `./gradlew clean assemble` runs produce byte-identical jars (compared by SHA-256).
- A deliberately misplaced dependency (for example, `domain` → `sidecar`) fails the dependency guard.
- A deliberately unformatted file fails `spotlessCheck`.
