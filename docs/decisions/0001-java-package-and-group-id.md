# ADR 0001 — Java package prefix and Maven group ID

- Status: **Accepted** — Option A, `io.github.kjmorse2.mconeworld`
- Approval: Accepted 2026-09-28 by the implementing agent under explicit owner delegation ("you are allowed to answer the questions yourself"). The owner may revisit it; changing it after Prompt 01 is a source-wide rename.
- Date: 2026-09-27
- Milestone: Prompt 00

## Context

`shared/AGENTS.md` says that the Java package prefix is chosen at repository bootstrap and is stable after that. Nothing in the repository chooses one yet:

- The only Java code is the imported MCA Selector subtree, which uses `net.querz.mcaselector` and group `net.querz`. That namespace belongs to the upstream author, and MCOneWorld code must not use it.
- No `settings.gradle(.kts)` or `build.gradle(.kts)` exists at the repository root.
- The `origin` remote is `https://github.com/kjmorse2/MCOneWorld.git`.

The prefix is persistent in two ways:

1. It appears in published artifact coordinates.
2. Once someone uses a class name in a diagnostic export or a stable error-code namespace, changing the prefix is a breaking change.

It must not be invented on the owner's behalf (Prompt 00 §5).

## Options

| Option | Example prefix | Pros | Cons |
|---|---|---|---|
| A. GitHub-derived namespace | `io.github.kjmorse2.mconeworld` | Derived from a namespace the owner verifiably controls (the `origin` remote). Maven Central accepts `io.github.<user>` after GitHub verification. No domain purchase. | Tied to a personal account name. Moving the repo to an organization later would make the prefix stale (but not broken). |
| B. Owner-controlled domain | `<reverse-domain>.mconeworld` | Neutral and durable if the domain is kept. | Requires a domain the owner actually controls. Not verifiable from the repository. |
| C. Bare prefix | `mconeworld` | Short. | Not reverse-DNS. Maven Central rejects it. Risk of collision. **Not recommended.** |

## Decision

`<ROOT>` = **`io.github.kjmorse2.mconeworld`**. Option A was chosen because it is the only namespace the owner is verifiably known to control (the `origin` remote). Then:

- Gradle `group = "<ROOT>"`
- Artifacts: `mconeworld-shared-domain`, `mconeworld-shared-sidecar`, `mconeworld-shared-protocol`, `mconeworld-shared-test-fixtures`
- Packages follow `shared/AGENTS.md`: `<ROOT>.domain`, `<ROOT>.domain.coordinate`, `<ROOT>.domain.operation`, `<ROOT>.sidecar.manifest`, `<ROOT>.sidecar.migration`, `<ROOT>.sidecar.storage`, `<ROOT>.sidecar.index`, `<ROOT>.protocol`, `<ROOT>.protocol.codec`, `<ROOT>.fixtures`
- JPMS module names (if adopted in Prompt 01): `<ROOT>.domain`, `<ROOT>.sidecar`, `<ROOT>.protocol`

The package name must not include the version string or the season concept.

## Consequences

- Wire channel identifiers (`mconeworld:hello`, `mconeworld:context`) and sidecar schema identifiers are **not** derived from the Java prefix. They stay `mconeworld:*` regardless of this choice, so this decision has no persistent-data impact.
- Error-code namespaces should likewise use a short stable token such as `MCOW-...` rather than Java class names.

## Migration impact

None today, because no MCOneWorld code exists. After Prompt 01, a rename touches every source file and published coordinate, but no sidecar data.

## How this is tested

Prompt 01 adds a build check that every class under `shared/*/src/main` lives under `<ROOT>` and that no shared class uses `net.querz`.
