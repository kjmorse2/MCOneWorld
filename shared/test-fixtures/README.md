# shared:test-fixtures

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-test-fixtures` · Package: `io.github.kjmorse2.mconeworld.fixtures`

**Responsibility.** Test-only support:

- builders that make invalid cases explicit
- small, documented, legally redistributable canonical fixtures that tests copy before mutating
- golden files carrying format-version and checksum metadata
- contract assertions (`ModuleContract`)

**Use only from `testImplementation`.** Any production configuration that resolves this project fails `verifySharedDependencies`.

**May depend on:** `shared:domain`, `shared:sidecar`, `shared:protocol`.

**Forbidden:** platform dependencies. Never commit real user worlds, player data, server jars, or secrets.

**Current contents:** `ModuleContract`. Every module's smoke test uses it to assert that the module's packages are `@NullMarked` and compiled to Java 21 bytecode.
