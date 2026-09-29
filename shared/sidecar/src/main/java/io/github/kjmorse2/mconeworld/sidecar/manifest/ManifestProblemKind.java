// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * The category of a manifest problem, which callers can branch on. Declaration order is precedence:
 * {@link ManifestException#primaryKind()} is the earliest kind present. The mapping from issue-code
 * prefix is fixed by {@link #of(IssueCode)}.
 */
public enum ManifestProblemKind {
  /** Not well-formed JSON in the MCOneWorld profile ({@code sidecar.json.*}, except limits). */
  SYNTAX,
  /** A resource limit was exceeded ({@code sidecar.json.limit.*}). */
  LIMIT,
  /** The schema version is unsupported or newer ({@code manifest.version.*}). */
  UNSUPPORTED_VERSION,
  /** Structurally invalid for the schema ({@code manifest.schema.*}, {@code sidecar.path.*}). */
  SCHEMA,
  /** Cross-record references are broken ({@code manifest.reference.*}, {@code archive.*}). */
  REFERENTIAL,
  /** A domain value or domain rule is violated ({@code domain.*}, {@code season.*}). */
  DOMAIN;

  /** Classifies an issue code by its prefix. */
  public static ManifestProblemKind of(IssueCode code) {
    String v = code.value();
    if (v.startsWith("sidecar.json.limit.")) {
      return LIMIT;
    }
    if (v.startsWith("sidecar.json.")) {
      return SYNTAX;
    }
    if (v.startsWith("manifest.version.")) {
      return UNSUPPORTED_VERSION;
    }
    if (v.startsWith("manifest.schema.") || v.startsWith("sidecar.path.")) {
      return SCHEMA;
    }
    if (v.startsWith("manifest.reference.") || v.startsWith("archive.")) {
      return REFERENTIAL;
    }
    return DOMAIN;
  }
}
