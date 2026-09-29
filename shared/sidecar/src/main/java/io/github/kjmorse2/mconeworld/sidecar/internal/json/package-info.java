// SPDX-License-Identifier: MIT
/**
 * <b>Internal, not API.</b> This package may change without notice, and other modules must not use
 * it.
 *
 * <p>It implements MCOneWorld's strict JSON profile (ADR 0003):
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader} parses UTF-8 input
 *       with jackson-core's streaming parser into an immutable {@link
 *       io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue} tree, under hard limits.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter} writes the
 *       single canonical byte form. It is written in-house, so a library upgrade cannot change
 *       output bytes (and therefore checksums).
 * </ul>
 *
 * <p>The profile is stricter than RFC 8259:
 *
 * <ul>
 *   <li>no byte-order mark
 *   <li>strictly valid UTF-8 and no unpaired surrogates, including in {@code \\u} escapes
 *   <li>no duplicate object keys
 *   <li>integers only, within the signed 64-bit range
 *   <li>exactly one top-level value
 * </ul>
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

import org.jspecify.annotations.NullMarked;
