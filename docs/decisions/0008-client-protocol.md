# ADR 0008: Client/server protocol, version 1

- Status: **Accepted**, 2026-10-01. Decided under the owner's standing delegation for design questions consistent with good practice. It settles no deferred choice of root `AGENTS.md` §22.
- Milestone: Prompt 10
- Format (normative): [`docs/formats/protocol-v1.md`](../formats/protocol-v1.md)

## 1. Context

MCOneWorld Client is optional. It needs the active season's id, name, sidecar revision, and per-dimension origins to show season-local coordinates (root §14). Root §15 requires:

- a namespaced, versioned protocol
- bounded strings, collections, and payloads
- validated identifiers and ranges
- safe handling of unknown optional fields
- graceful rejection of incompatible versions without disconnecting anyone
- no custom payloads until a client advertises support
- golden byte fixtures

The codec lives in a shared module, so it must use no Paper, Fabric, or Netty types. Prompt 10 forbids registering channels or opening sockets.

## 2. Decision

1. **Two messages.** Client hello, then a server context reply. The server never speaks first, so vanilla clients never see a payload. Later contexts replace the earlier one completely; there are no deltas.
2. **A hand-written binary codec** over `byte[]` and `ByteBuffer`:
   - big-endian fixed-width integers
   - canonical unsigned LEB128 varints, with per-field byte limits
   - length-prefixed strict UTF-8
   - sets and maps sorted strictly by byte order
   - a leading layout byte
3. **Strictly canonical decoding.** Only the canonical encoding of a valid value is accepted, so `encode(decode(b)) == b`. Every failure is a `ProtocolException` with a stable code; nothing else is thrown.
4. **Negotiation** by version range (choose the highest common version) and capabilities (both sides must list one and the agreed version must define it). An incompatible peer is an ordinary result that produces an `Incompatible` reply.
5. **Forward-compatible extensions**: keyed, length-prefixed, opaque values that version 1 keeps but never interprets.
6. **Wire DTOs separate from sidecar records.** `SeasonContext` and the messages are protocol types. They reuse only the domain's validated value types (`SeasonId`, `DisplayName`, `DimensionKey`, `DimensionOrigin`), not sidecar records. The sidecar and wire schemas therefore evolve independently.
7. **Limits sized so that every valid value fits.** Hello ≤ 8,192 bytes; context ≤ 32,768 bytes, under Minecraft's serverbound limit of 32,767. The largest valid messages are 7,323 and 25,293 bytes, so encoding never fails.

## 3. Alternatives

| Alternative | Why not |
|---|---|
| JSON over the channel | Larger, non-canonical by default (whitespace, key order, number forms), and the client would need a JSON parser. A canonical JSON subset is possible but gives no benefit for two small messages. |
| Minecraft's `FriendlyByteBuf` conventions directly | Platform-specific, and its signed VarInt and lenient strings don't give a canonical form. Our LEB128 matches its VarInt bytes for non-negative values, so adapters can still use their buffers to move bytes. |
| Protocol Buffers or CBOR libraries | A new dependency for the client and Paper, and neither is canonical by default (field order, unknown fields, map order). The format is small enough to specify completely. |
| A single version number (no ranges) | Prevents older and newer peers from finding a common version |
| Lenient decoding (accept overlong varints, unsorted sets) | Two byte strings would mean one value, which weakens golden fixtures and any future signing or caching. |
| Disconnecting on a malformed payload | Forbidden by root §15. Ignoring is safe because the client only displays the data. |

## 4. Consequences

- A context change is a full resend, at most 25 KB and usually under 200 bytes. That's fine for an event as rare as season activation.
- Adapters must copy payload bytes into a `byte[]` or `ByteBuffer`. The copy is bounded by the payload limits.
- A future layout change needs a new layout byte. Old servers answer nothing to it, so clients keep sending layout 1 while they still support version 1 servers.
- `ProtocolSupport.sinceVersion` must gain an entry for every new capability.

## 5. Migration impact

There is none: this is the first version. The protocol is independent of the sidecar schema (manifest 1.0, chunk index 1.0); a sidecar migration changes no wire format.

## 6. How it is tested

`shared/protocol/src/test`:

- **`ProtocolGoldenTest`**: the seven golden vectors encode byte-exactly, decode to their values, and match their `.meta` checksums.
- **`SpecificationVectorTest`**: an independent `ByteBuffer` reader written from the spec decodes every vector to the values the spec states.
- **`ProtocolCodecTest`**:
  - minimum and maximum sizes, each exact
  - determinism across insertion orders
  - every proper prefix of every vector is truncated, and any extra byte is trailing
  - each rejection code, with a hand-built payload
  - bounded allocation, measured per decode
  - immutability
  - `ByteBuffer` input
- **`ProtocolFuzzProperties`**: random noise and mutated vectors either fail with `ProtocolException` or re-encode to the same bytes; random valid messages round-trip.
- **`NegotiationTest`**:
  - the full version matrix over 1..6 ranges, with an `Incompatible` result and no exception
  - unknown capabilities
  - capabilities a version doesn't define
  - the client's reply check
  - a pin on the hello's fields (server authority)
