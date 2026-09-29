// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.Objects;

/**
 * The result of a successful decode.
 *
 * @param document the validated document
 * @param warnings non-error findings (for example misaligned origins, or a newer minor version)
 * @param canonical whether the input bytes were exactly the canonical encoding. If false, the file
 *     was hand-edited or written by another tool. It is still valid, and the next write normalizes
 *     it; reading never rewrites it.
 */
public record DecodedManifest(
    ManifestDocument document, ValidationResult warnings, boolean canonical) {
  public DecodedManifest {
    Objects.requireNonNull(document, "document");
    Objects.requireNonNull(warnings, "warnings");
  }
}
