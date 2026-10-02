# MCOneWorld client/server protocol, version 1

- Status: **normative** since Prompt 10 (ADR [0008](../decisions/0008-client-protocol.md)).
- Implementation: `shared:protocol`: messages and `Negotiation` in `io.github.kjmorse2.mconeworld.protocol`, bytes in `ProtocolCodec` (`…protocol.codec`).
- Golden vectors: `shared:test-fixtures`, `fixtures/protocol/v1/*.bin`, each with a `.meta` file (channel, length, SHA-256). They are annotated in §9.

This document, together with the golden vectors, fully determines the wire format. An adapter that follows it byte for byte interoperates with the reference codec. `SpecificationVectorTest` proves this by reading the vectors from this document's rules alone, without the codec.

## 1. Scope

The protocol carries the **active season context** from MCOneWorld Paper to the optional MCOneWorld Client so the client can show season-local coordinates (root `AGENTS.md` §14–§15). It carries nothing else: no archive history, chunk indexes, sidecar records, player data, or travel state.

The protocol is optional on both sides:

- A vanilla client never sends a hello, so it never receives a context. It plays normally.
- A client on a server without the plugin gets no reply. It shows absolute coordinates.
- No outcome of this protocol disconnects a player. A malformed or incompatible message is logged by its code and ignored.

The shared module defines bytes and decisions only. It registers no channel, opens no socket, and disconnects nobody. Paper and Fabric adapters move payload bytes in and out of their packet buffers and call `ProtocolCodec`.

## 2. Channels and sequence

| Channel | Direction | Payload | Size limit |
|---|---|---|---|
| `mconeworld:hello` | client → server | §4 | 8,192 bytes |
| `mconeworld:context` | server → client | §5 | 32,768 bytes |

A payload is the custom-payload body, without Minecraft's channel identifier or packet framing.

1. **The client speaks first.** Once it can send custom payloads, it sends one hello. It never sends anything else on these channels.
2. **The server replies once.** It decodes the hello, negotiates (§7), and sends one context: `Accepted` or `Incompatible`. It never sends a context to a connection that has not sent a valid hello, because the client has not advertised support (root §15).
3. **Updates.** After an `Accepted` reply, the server may send further `Accepted` contexts on the same connection whenever the active season, its name, the sidecar revision, or the origins change. They use the same version and enabled capabilities. Each context replaces the previous one completely; there are no deltas.
4. **Repeated hellos.** A server answers only the first valid hello per connection and ignores later ones, so a client cannot make the server send replies on demand.
5. **Unanswerable hellos.** A hello that fails to decode, including one with an unknown layout byte, gets no reply. The client times out and keeps showing absolute coordinates.

## 3. Primitives

All multi-byte integers are big-endian. A payload is decoded from exactly its bytes: everything must be consumed (§6).

| Primitive | Encoding |
|---|---|
| `u8` | One unsigned byte |
| `bool` | `u8`: `0x00` false, `0x01` true; any other value is rejected |
| `varint(max)` | Unsigned LEB128: 7 bits per byte, least significant group first, high bit set on every byte except the last. It must be the **shortest** form (no trailing `0x00` group after the first byte), use at most ⌈bits(max)/7⌉ bytes, and be ≤ `max`. Field maxima: protocol versions 65,535 (≤ 3 bytes), counts and lengths 2^31−1 (≤ 5 bytes, then the field's own limit), sidecar revision 2^63−1 (≤ 9 bytes). |
| `i32`, `i64` | Two's complement, big-endian |
| `uuid` | 16 bytes: the most significant 64 bits as `i64`, then the least significant 64 bits as `i64` |
| `string(maxBytes)` | `varint` byte length (≤ `maxBytes`), then that many bytes of **well-formed UTF-8**. Overlong forms, encoded surrogates, code points above U+10FFFF, and truncated sequences are rejected, never replaced. No normalization. |
| `identifier` | `string(64)` matching `namespace ":" path`. Both parts use `a-z 0-9 _ - .`; the path may contain `/` between non-empty segments; no segment, nor the namespace, may be `.` or `..` |
| `capabilities` | `varint` count (≤ 32), then that many `identifier`s in **strictly ascending** byte order (which rules out duplicates) |
| `extensions` | `varint` count (≤ 16), then for each one an `identifier` key and a `varint` length (≤ 256) followed by that many opaque bytes. Keys are in strictly ascending byte order. |

Identifiers and dimension keys are ASCII, so byte order, `String.compareTo` order, and the order of `DimensionKey` all agree.

## 4. `mconeworld:hello`

| Field | Encoding | Rules |
|---|---|---|
| layout | `u8` | `1`. Any other value: `unsupported_layout`. |
| minVersion | `varint(65535)` | ≥ 1 |
| maxVersion | `varint(65535)` | ≥ minVersion |
| hasClientVersion | `bool` | |
| clientVersion | `string(64)` | Present only if hasClientVersion. 1–64 printable ASCII characters (`0x21`–`0x7E`). Diagnostics only; it is never used for decisions. |
| capabilities | `capabilities` | What the client asks to enable. Unknown ones are allowed and ignored. |
| extensions | `extensions` | Version 1 defines none. A version 1 server keeps unknown ones opaque and never interprets them. |

The hello deliberately has **no** field for a season, origin, unlock, cost, or teleport destination. The server is the only authority for those (root §15), and a test pins the hello's fields.

Smallest hello: 6 bytes (§9, `hello-minimal`). Largest valid hello: 7,323 bytes.

## 5. `mconeworld:context`

| Field | Encoding | Rules |
|---|---|---|
| layout | `u8` | `1` |
| kind | `u8` | `0` Incompatible, `1` Accepted; anything else is `out_of_range` |

**Accepted** (kind 1) continues:

| Field | Encoding | Rules |
|---|---|---|
| version | `varint(65535)` | ≥ 1: the negotiated version |
| enabled | `capabilities` | The capabilities in effect for this connection |
| hasSeason | `bool` | `1` requires `mconeworld:season_context` in enabled. `0` means no active season, or that the season context is not enabled. |
| season | §5.1 | Present only if hasSeason |
| extensions | `extensions` | |

**Incompatible** (kind 0) continues:

| Field | Encoding | Rules |
|---|---|---|
| serverMin | `varint(65535)` | ≥ 1 |
| serverMax | `varint(65535)` | ≥ serverMin |
| extensions | `extensions` | |

Smallest context: 5 bytes (`context-incompatible`). Largest valid context: 25,293 bytes.

### 5.1 Season

| Field | Encoding | Rules |
|---|---|---|
| seasonId | `uuid` | Not the nil UUID |
| name | `string(256)` | A valid `DisplayName`: 1–64 code points, no leading or trailing whitespace, no control or bidirectional-override characters. Presentation only, and not unique. |
| sidecarRevision | `varint(2^63−1)` | The manifest revision the context was read from. A client compares it to tell a cached context is stale (root §14). |
| originCount | `varint` | ≤ 64 |
| origins | originCount × origin | Strictly ascending by dimension key, so there are no duplicates |

An origin is:

| Field | Encoding | Rules |
|---|---|---|
| dimension | `string(256)` | A valid `DimensionKey` (`namespace:path`, the grammar of §3 identifiers, up to 256 characters) |
| x | `i64` | Absolute block X, within ±2^35 (`CoordinateLimits`) |
| y | `i32` | Reference Y. It does not participate in the transform. |
| z | `i64` | Absolute block Z, within ±2^35 |

The client shows `localX = absoluteX − x` and `localZ = absoluteZ − z` in that dimension only. A dimension without an origin, the End included unless it is listed, shows absolute coordinates. Origins change presentation only: commands and everything else stay absolute (root §8).

## 6. Decoding policy

A decoder accepts **only the canonical encoding of a valid message**. Every other input fails with a `ProtocolException` whose stable code is below, and with no other exception. Consequently `encode(decode(b)) == b` for every accepted `b`, which the fuzz tests check.

| Code | Cause |
|---|---|
| `protocol.too_large` | Payload over its limit (checked before parsing), or a count, string, or extension over its limit |
| `protocol.truncated` | The payload ends inside a field |
| `protocol.trailing_bytes` | Bytes remain after the last field |
| `protocol.malformed_varint` | A varint longer than its field allows |
| `protocol.not_canonical` | A varint not in shortest form; a set or origin list out of order or repeated |
| `protocol.malformed_text` | Malformed UTF-8, an invalid client version, or an invalid season name |
| `protocol.invalid_identifier` | A capability, extension key, or dimension that breaks its grammar |
| `protocol.out_of_range` | A flag other than 0/1, an unknown context kind, a version of 0 or an inverted range, a nil season id, a coordinate outside ±2^35, or a season without the season-context capability |
| `protocol.unsupported_layout` | A layout byte other than 1 |
| `protocol.unexpected_reply` | (client check, §7) A reply naming a version the client did not offer, or enabling a capability it did not ask for |

Every length and count is checked against its limit **and** against the remaining bytes before anything is allocated. Decoding allocates a bounded multiple of the payload size.

**Reaction.** The receiver logs the code (never the raw payload) and ignores the message. A server sends no reply. A client keeps showing absolute coordinates. Nobody is disconnected.

Decoded values are immutable: sets and maps are unmodifiable, and extension bytes are copied in and out.

## 7. Negotiation and version behaviour

The server computes:

- **version** = the highest version in both the client's and the server's range. If the ranges do not overlap, the reply is `Incompatible` with the server's range.
- **enabled** = the capabilities that the client asked for, that the server offers, **and** that the agreed version defines.

| Capability | Defined since | Meaning |
|---|---|---|
| `mconeworld:season_context` | 1 | The context may carry the season (§5.1) |

Cases:

| Case | Example | Result |
|---|---|---|
| Same | client 1..1, server 1..1 | Accepted, version 1 |
| Older compatible client | client 1..1, server 1..3 | Accepted, version 1. The server speaks version 1 to it. |
| Newer client | client 1..7, server 1..1 | Accepted, version 1. Unknown capabilities and extensions are ignored. |
| Incompatible | client 2..5, server 1..1 | Incompatible(1..1). The client shows absolute coordinates; nobody is disconnected. |
| New layout | hello layout 2 | No reply (§2). A client that wants to talk to version 1 servers must send layout 1. |

**Client check.** The client accepts an `Accepted` reply only if its version is in the client's offered range and `enabled` ⊆ the capabilities it asked for. Otherwise it ignores the reply (`unexpected_reply`). An `Incompatible` reply is always accepted, as information.

**Server authority.** Nothing a client sends selects a season, origin, destination, unlock, or cost. The hello can only narrow what the server shares.

## 8. Evolution

- **Additive changes** (new optional data) use a new protocol version plus extensions or new capabilities. Layout 1 stays decodable by every version 1 peer.
- **A capability** is defined by the first version that introduces it and is enabled only when both sides list it and the agreed version defines it.
- **Breaking changes** need a new layout byte, a new protocol version range, and new golden vectors. Old vectors are kept and still tested.
- **The limits** in §2–§5 are part of version 1. Raising one needs a new version.

## 9. Golden vectors (annotated)

All seven vectors are in `fixtures/protocol/v1/`. Three are annotated here; the rest follow the same rules.

`hello-minimal` (6 bytes): `01 01 01 00 00 00`. Layout 1, versions 1..1, no client version, no capabilities, no extensions.

`hello-v1` (45 bytes), `HelloMessage.v1("1.0.0+fabric")`:

```text
01                                   layout 1
01 01                                versions 1..1
01                                   hasClientVersion
0c 31 2e 30 2e 30 2b 66 61 62 72 69 63
                                     "1.0.0+fabric" (12 bytes)
01                                   1 capability
19 6d 63 6f 6e 65 77 6f 72 6c 64 3a 73 65 61 73 6f 6e 5f 63 6f 6e 74 65 78 74
                                     "mconeworld:season_context" (25 bytes)
00                                   0 extensions
```

`context-season` (159 bytes):

```text
01 01                                layout 1, Accepted
01                                   version 1
01 19 6d 63 … 78 74                  1 capability: mconeworld:season_context
01                                   hasSeason
5e a5 00 03 00 00 40 00 80 00 00 00 00 00 00 03
                                     season 5ea50003-0000-4000-8000-000000000003
1a 53 65 61 73 6f 6e 20 33 20 e2 80 94 20 46 72 c3 bc 68 6c 69 6e 67 20 e2 9c a6
                                     "Season 3 — Frühling ✦" (26 UTF-8 bytes)
ac 02                                sidecar revision 300 (0x2c | 0x80, 300 >> 7 = 2)
02                                   2 origins, ascending
13 6d 69 6e 65 63 72 61 66 74 3a 6f 76 65 72 77 6f 72 6c 64
                                     "minecraft:overworld"
00 00 00 00 00 10 00 00              x = 1,048,576
00 00 00 40                          y = 64
ff ff ff ff ff e0 00 00              z = -2,097,152
14 6d 69 6e 65 63 72 61 66 74 3a 74 68 65 5f 6e 65 74 68 65 72
                                     "minecraft:the_nether"
00 00 00 00 00 02 00 00              x = 131,072
00 00 00 40                          y = 64
ff ff ff ff ff fc 00 00              z = -262,144
00                                   0 extensions
```

`context-incompatible` (5 bytes): `01 00 01 01 00`. Layout 1, Incompatible, server 1..1, no extensions.

The others:

| Vector | Shows |
|---|---|
| `hello-future-client` | A version 1..3 client with an unknown capability (`example:future_feature`, sorted first) and an extension `example:hint = 01 ff` |
| `context-season-extremes` | Revision 2^63−1 as a 9-byte varint (`ff`×8 `7f`); origins at x = −2^35, z = 2^35−1, and y = `Integer.MIN_VALUE`/`MAX_VALUE`; a custom dimension `custom:a/b` sorted before `minecraft:the_end` |
| `context-no-season` | Accepted with the season context enabled but no active season |

To regenerate the vectors (only for a new protocol version), run `ProtocolFixtures.main <scratch-dir>`, review every byte against this document, then copy the files in.
