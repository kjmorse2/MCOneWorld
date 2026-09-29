// SPDX-License-Identifier: MIT
/**
 * Sidecar manifest v1: {@code mconeworld-data/manifest.json}. The normative specification is {@code
 * docs/formats/manifest-v1.md}.
 *
 * <p>Public boundary:
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec}: bytes ⇄ {@link
 *       io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument}
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestFiles}: read-only file access
 * </ul>
 *
 * <p>JSON types never cross this boundary. Decoding validates fully (syntax, limits, version,
 * schema, references, domain rules) before returning anything. It never modifies its input.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import org.jspecify.annotations.NullMarked;
