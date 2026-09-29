# Prompt 10 — Optional Client Protocol

Implement the platform-neutral protocol used by MCOneWorld Paper and MCOneWorld Client. Neither Paper nor Fabric integration belongs in this milestone.

## Objective

Create a minimal, versioned, deterministic, bounded wire contract for optional season-context synchronization while guaranteeing vanilla clients are not part of this protocol.

## Initial messages

- Client-to-server `mconeworld:hello`
- Server-to-client `mconeworld:context`

Define wire DTOs containing only necessary data:

- Protocol version/range
- Client capability set
- Optional client-mod version for diagnostics
- Active season ID and bounded display name
- Sidecar revision
- Dimension-specific origins
- Server capability set

## Requirements

1. Persistent sidecar DTOs must not be reused directly as wire DTOs.
2. Define byte order, integer encoding, string encoding, collection ordering, and maximum sizes.
3. Decode from bounded inputs and reject trailing or truncated invalid data according to a documented policy.
4. Support capability negotiation without assuming every version implements every feature.
5. Define behavior for older compatible, newer unknown, and incompatible protocol versions.
6. Keep server authority explicit; no client message may set a season, origin, unlock, cost, or teleport destination.
7. Use platform-neutral I/O boundaries. Paper and Fabric adapters will translate their packet buffers later.
8. Ensure immutable decoded data.

## Tests

- Golden byte fixtures for every message/version.
- Encode/decode round trips.
- Determinism across insertion orders.
- Minimum/maximum valid lengths and counts.
- Oversized, malformed UTF, duplicate dimension, invalid identifier, truncated, trailing, and fuzz-generated payloads.
- Compatible capability negotiation matrix.
- Incompatible version result without an unhandled exception.
- Immutability and bounded allocation.

If practical, add a small independent decoder test or specification vector so future Paper and Fabric adapters can prove byte compatibility without depending on this codec’s encoder.

## Constraints

- Do not register networking channels.
- Do not open sockets.
- Do not disconnect hypothetical clients in shared code.
- Do not transmit full archive history or chunk indexes.

## Acceptance criteria

The protocol specification and golden vectors completely determine the initial wire format, malformed inputs fail safely, and no platform dependency enters shared modules.

