// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable manifest-specific issue codes. Together with {@code sidecar.json.*}, {@code
 * sidecar.path.unsafe}, {@code domain.*}, {@code archive.*}, and {@code season.*}, these are every
 * code a manifest decode can report. See {@code docs/formats/manifest-v1.md} §"Error codes".
 */
public final class ManifestIssueCodes {
  /** The document is not an MCOneWorld manifest: the {@code format} member is missing or wrong. */
  public static final IssueCode NOT_A_MANIFEST = IssueCode.of("manifest.schema.not_a_manifest");

  /** A required member is absent. */
  public static final IssueCode MISSING_MEMBER = IssueCode.of("manifest.schema.missing_member");

  /** A member this schema version does not define (strict mode). */
  public static final IssueCode UNKNOWN_MEMBER = IssueCode.of("manifest.schema.unknown_member");

  /** A member has the wrong JSON type. */
  public static final IssueCode WRONG_TYPE = IssueCode.of("manifest.schema.wrong_type");

  /** A member's value violates the schema (bad timestamp, enum, version text, layout rule, …). */
  public static final IssueCode INVALID_VALUE = IssueCode.of("manifest.schema.invalid_value");

  /** The schema major version is not supported by this reader. Fail closed. */
  public static final IssueCode UNSUPPORTED_VERSION = IssueCode.of("manifest.version.unsupported");

  /** WARNING: written by a newer minor version; unknown members were preserved, not interpreted. */
  public static final IssueCode NEWER_MINOR_VERSION = IssueCode.of("manifest.version.newer_minor");

  /** Two referenced files, or a file and a backup, share a path. */
  public static final IssueCode DUPLICATE_PATH = IssueCode.of("manifest.reference.duplicate_path");

  /** A backup record has no stored location. */
  public static final IssueCode BACKUP_PATH_MISSING =
      IssueCode.of("manifest.reference.backup_path_missing");

  /** A backup location refers to no backup record. */
  public static final IssueCode BACKUP_PATH_UNKNOWN =
      IssueCode.of("manifest.reference.backup_path_unknown");

  private ManifestIssueCodes() {}
}
