# MCOneWorld Version Matrix

Status: build toolchain and shared-library versions are **PINNED** in source control as of Prompt 01 (`gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, lockfiles, `gradle/verification-metadata.xml`). Minecraft platform rows are **targets**. Nothing in this file is a support claim. Per root `AGENTS.md` §5, a version becomes *supported* only after it passes the recorded validation suite. Until then every row is a *target*.

Observed on 2026-09-27 from primary sources (see "Evidence" below). Re-verify before each pin lands in a build file.

## 1. Minecraft platform targets

| Component | Candidate A: 26.2 (recommended for the first release) | Candidate B: 26.3 | Notes |
|---|---|---|---|
| Minecraft Java | `26.2` | `26.3` (released 2026-09-15) | Mojang manifest `latest.release` = `26.3` |
| World data version | `4903` | `5023` | From `version.json` inside Mojang's server jar |
| Network protocol version | `776` | `777` | Same source |
| Data pack format | `107.1` | `121.0` | Same source (`pack_version.data_major/minor`); the stronghold datapack must declare this exactly |
| Resource pack format | `88.0` | `97.1` | Same source |
| Java required by Minecraft | 25 | 25 | `javaVersion.majorVersion` in the Mojang version JSON |
| Paper server | `26.2` build `129`, channel **STABLE** | `26.3` build `49`, channel **ALPHA** | PaperMC Fill v3 API |
| Paper server SHA-256 | `b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083` | n/a (alpha) | Fill v3 `downloads.server:default.checksums` |
| Paper API artifact | `io.papermc.paper:paper-api:26.2.build.129-stable` | `26.3.build.49-alpha` | repo.papermc.io `maven-public` |
| Paper minimum Java | 25 | 25 | Fill v3 `java.version.minimum` |
| Fabric Loader | `0.19.5` (stable) | `0.19.5` (stable) | meta.fabricmc.net |
| Fabric API | `0.161.0+26.2` | `0.161.0+26.3` | Modrinth `fabric-api` release channel |
| Fabric Loom (Gradle plugin) | `1.18.2` | `1.18.2` | maven.fabricmc.net `<release>`; compatibility with 26.x not yet validated |
| MCA Selector (imported) | 2.9 at upstream `d31bee6a` | same | README claims data versions 4764–5023+ (26.1–26.3) |

**Why candidate A:** Paper is the runtime authority (root `AGENTS.md` §13), and root §5 forbids unpinned or unstable builds in a release. Paper 26.3 is still ALPHA. Vanilla and Fabric are both stable on 26.3, so moving to 26.3 once Paper marks a 26.3 build STABLE is a normal compatibility event: review fixtures and migrations for data version 4903 → 5023.

**Shared modules are independent of this choice.** They must not depend on Minecraft (see `shared/AGENTS.md`). The Minecraft pin constrains only Java 25 and the later Paper and Fabric projects. It does not block Prompt 01.

## 2. Build toolchain targets

| Component | Proposed pin | Observed latest | Notes |
|---|---|---|---|
| Gradle (root wrapper) | `9.8.0`, **pinned**, `distributionSha256Sum=bafd5ce9…58e6c` | `9.8.0` (2026-09-24) | services.gradle.org `versions/current` |
| Gradle (offline-tool wrapper, existing) | `9.1.0` | — | Unchanged in this milestone |
| JDK toolchain (build) | Java **25**, pinned by `languageVersion`. Any JDK ≥ 25.0.4 works; CI uses Temurin `25.0.4`. | 25.0.4 | Auto-download disabled. Local: OpenJDK 25.0.4.1. |
| Shared-module bytecode (`--release`) | **21**, pinned (ADR 0002) | — | Keeps the shared modules consumable by the offline tool, which targets 21 today |
| Offline tool bytecode (existing) | 21 | — | `java.sourceCompatibility = VERSION_21`; JavaFX `21` |
| Paper plugin bytecode (future) | 25 | — | Required by Paper 26.x |
| Fabric client bytecode (future) | 25 | — | Required by Minecraft 26.x |
| Gradle toolchain resolver | not used | 1.0.0 | Auto-provisioning declined (Q10) |

## 3. Proposed shared-module library pins

Pinned in `gradle/libs.versions.toml`.

| Purpose | Artifact | Version | License | Scope |
|---|---|---|---|---|
| JSON | `tools.jackson.core:jackson-core` (**no databind**) | `3.2.3`, pinned (Prompt 04) | Apache-2.0 | `sidecar` `implementation` only, never in the public API |
| JSON (not used) | `com.google.code.gson:gson` | `2.14.0` | Apache-2.0 | rejected alternative (ADR 0003) |
| Nullness annotations | `org.jspecify:jspecify` | `1.0.1` | Apache-2.0 | `api` dependency of every shared module (runtime-retained `@NullMarked`; ADR 0002 implementation notes) |
| Unit tests | `org.junit:junit-bom` | **`5.14.4`**, pinned (not 6.1.3; see ADR 0002 amendment) | EPL-2.0 | test |
| Assertions | `org.assertj:assertj-core` | `3.27.7` | Apache-2.0 | test (not `4.0.0-M1`, which is a milestone) |
| Property tests | `net.jqwik:jqwik` | `1.10.1` | EPL-2.0 | test |
| Formatter | Spotless Gradle plugin `com.diffplug.spotless` | `8.10.3` | Apache-2.0 | build |
| Formatter engine | `com.google.googlejavaformat:google-java-format` | `1.36.1` | Apache-2.0 | build |
| Static analysis | `com.google.errorprone:error_prone_core` | `2.50.0` | Apache-2.0 | build |
| Error Prone Gradle plugin | `net.ltgt.errorprone` | `5.1.1` | Apache-2.0 | build |
| Nullness checker | `com.uber.nullaway:nullaway` | `0.14.2` | MIT | build |
| Benchmarks (not used) | `org.openjdk.jmh:jmh-core` | `1.37` | GPL-2.0 WITH Classpath-exception-2.0 | **not adopted** in Prompt 08. The chunk-index measurements are whole operations on disk-backed state, each case in its own fixed-heap JVM, so a small custom harness fits better than a microbenchmark framework (see [`docs/benchmarks/chunk-index/README.md`](../benchmarks/chunk-index/README.md)). |
| Coverage (diagnostic) | JaCoCo (bundled with Gradle) | Gradle default for 9.8.0 | EPL-2.0 | build |
| Tool UI | `org.openjfx:javafx-{base,graphics,controls}` | `25.0.4`, pinned (Tool T1) | GPL-2.0 WITH Classpath-exception-2.0 | `tool:app` only. One jar per platform (`linux`, `linux-aarch64`, `mac`, `mac-aarch64`, `win`); all five are locked and verified. |
| Tool UI tests | `org.testfx:openjfx-monocle` | `21.0.2`, pinned (Tool T1) | GPL-2.0 WITH Classpath-exception-2.0 | `tool:app` test runtime only: headless JavaFX, verified to run JavaFX 25 with no display. JavaFX 25 has no built-in headless platform (26+ does). |

**Chunk-index spike dependencies (Prompt 08), removed in Prompt 09.** `org.xerial:sqlite-jdbc` 3.53.4.0, `org.roaringbitmap:RoaringBitmap` 1.6.23, and `com.h2database:h2-mvstore` 2.5.252 were on the `bench` classpath only, while ADR 0007 was undecided. The accepted encoding uses only the JDK, so they were removed (owner decision D4), along with their lockfile and verification-metadata entries. Paper's own server classpath carries `sqlite-jdbc` 3.49.1.0 (`paper-server/build.gradle.kts`, checked 2026-09-30); MCOneWorld does not use it.

## 4. Evidence

Endpoints queried on 2026-09-27 (read-only):

- `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` and the per-version JSONs it links to
- Mojang server jars for 26.2 and 26.3: downloaded to a temporary scratch directory, `version.json` extracted, jars deleted, nothing committed
- `https://fill.papermc.io/v3/projects/paper` and `/versions/{26.2,26.3}` with `/builds`
- `https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml`
- `https://meta.fabricmc.net/v2/versions/game` and `/loader`
- `https://api.modrinth.com/v2/project/fabric-api/version`
- `https://maven.fabricmc.net/net/fabricmc/fabric-loom/maven-metadata.xml`
- `https://services.gradle.org/versions/current`
- `https://api.adoptium.net/v3/info/available_releases` and `/v3/assets/latest/{21,25}/hotspot`
- `https://repo1.maven.org/maven2/**/maven-metadata.xml` for every library above
- `https://plugins.gradle.org/m2/**/maven-metadata.xml` for Gradle plugins

`search.maven.org` returned a stale index (for example, it reported JUnit 5.13.0-M3 as newest). It was discarded in favor of `repo1.maven.org` metadata.
