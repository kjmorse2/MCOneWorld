# shared:protocol

Artifact: `io.github.kjmorse2.mconeworld:mconeworld-shared-protocol` · Packages: `io.github.kjmorse2.mconeworld.protocol`, `….protocol.codec`

**Responsibility.** The optional client/server protocol, version 1. Spec: [`docs/formats/protocol-v1.md`](../../docs/formats/protocol-v1.md); decision: ADR 0008.

- `mconeworld:hello` (client → server) and `mconeworld:context` (server → client)
- version and capability negotiation
- minimal wire DTOs, separate from sidecar records
- a bounded, deterministic codec over `byte[]` and `ByteBuffer`

It opens no sockets, registers no platform channels, disconnects nobody, and never puts sidecar records on the wire.

**May depend on:** `shared:domain`.

**Forbidden:** `shared:sidecar`, `shared:test-fixtures` in production code, and every platform dependency. The Fabric client consumes this module, so it must stay small.

## API

| Type | Use |
|---|---|
| `HelloMessage` | Versions, capabilities, optional client version (diagnostics), extensions. `v1(clientVersion)` is the standard hello. It has no server-authoritative fields, and a test pins that. |
| `ContextMessage` | Sealed: `Accepted(version, enabled, season?, extensions)` or `Incompatible(serverRange, extensions)` |
| `SeasonContext` | Season id, `DisplayName`, sidecar revision, and a sorted, unmodifiable map of `DimensionOrigin`s (≤ 64) |
| `VersionRange`, `Capability`, `CapabilitySet`, `Extension`, `Extensions` | Validated, immutable, sorted. `Capability.SEASON_CONTEXT` is the only version 1 capability. |
| `ProtocolSupport` | What one side implements; `CURRENT` is version 1 with the season context. `sinceVersion(capability)` maps each capability to the version that defines it. |
| `Negotiation` | `negotiate(server, hello)` returns `Agreed` or `Incompatible` (never an exception); `reply(result, activeSeason)` builds the context; `checkReply(sentHello, reply)` is the client's check. |
| `codec.ProtocolCodec` | `encodeHello`/`encodeContext` (deterministic; never fail on a valid value) and `decodeHello`/`decodeContext` (strictly canonical; throw only `ProtocolException`) |
| `ProtocolException`, `ProtocolIssueCodes`, `ProtocolLimits` | Stable codes and the version 1 limits |

## Adapter checklist (Paper, Fabric)

1. Copy the custom-payload body into a `byte[]` and call the codec. Don't parse with platform buffers.
2. On `ProtocolException`, log `code()` and drop the message. Never disconnect.
3. The server sends a context only in reply to a valid hello: once per connection, then full replacements when the season context changes.
4. The client runs `Negotiation.checkReply` on every context, and shows absolute coordinates whenever it has no valid one.
5. Prove byte compatibility by replaying the golden vectors in `shared:test-fixtures` (`fixtures/protocol/v1/`); `SpecificationVectorTest` is a reader you can port.

**Status:** complete for version 1 (Prompt 10).
