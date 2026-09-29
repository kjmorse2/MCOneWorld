// SPDX-License-Identifier: MIT
/**
 * The optional MCOneWorld client/server protocol ({@code mconeworld:hello}, {@code
 * mconeworld:context}) exchanged between MCOneWorld Paper and MCOneWorld Client.
 *
 * <p>Defines versioned wire DTOs and bounded, deterministic codecs over standard Java byte
 * abstractions. It never opens sockets, registers platform channels, or serializes sidecar records
 * directly; the wire schema evolves independently of the sidecar schema (root {@code AGENTS.md}
 * §15).
 */
@NullMarked
package io.github.kjmorse2.mconeworld.protocol;

import org.jspecify.annotations.NullMarked;
