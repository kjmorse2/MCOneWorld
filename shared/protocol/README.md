# shared:protocol

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-protocol` · Package: `io.github.kjmorse2.mconeworld.protocol`

**Responsibility.** The optional client/server protocol (`mconeworld:hello`, `mconeworld:context`):

- version negotiation
- capability advertisement
- minimal wire DTOs
- bounded, deterministic codecs over standard Java byte abstractions

It opens no sockets, registers no platform channels, and never puts sidecar records on the wire.

**May depend on:** `shared:domain`.

**Forbidden:** `shared:sidecar`, `shared:test-fixtures` in production code, and every platform dependency. The Fabric client consumes this module, so it must stay small.

**Status:** scaffold only (Prompt 01). Content arrives in Prompt 10.
