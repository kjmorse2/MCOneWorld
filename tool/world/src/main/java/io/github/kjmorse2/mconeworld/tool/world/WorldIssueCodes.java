// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes for every finding of the world reader. A public contract: never rename or repurpose
 * one. The UI maps them to explanations and remediation.
 */
public final class WorldIssueCodes {
  /** The directory is missing, not a directory, a symbolic link, or has no {@code level.dat}. */
  public static final IssueCode NOT_A_WORLD = IssueCode.of("tool.world.not_a_world");

  /** {@code level.dat} or another NBT file could not be read as well-formed NBT. */
  public static final IssueCode NBT_MALFORMED = IssueCode.of("tool.world.nbt_malformed");

  /** An NBT file exceeds a size, depth, or length limit. */
  public static final IssueCode NBT_TOO_LARGE = IssueCode.of("tool.world.nbt_too_large");

  /** {@code level.dat} lacks a field the Tool needs, or holds an invalid value. */
  public static final IssueCode LEVEL_DATA_INVALID = IssueCode.of("tool.world.level_data_invalid");

  /** No dimension directory was found. */
  public static final IssueCode NO_DIMENSIONS = IssueCode.of("tool.world.no_dimensions");

  /** Both the 26.1+ and the legacy layout are present. */
  public static final IssueCode LAYOUT_MIXED = IssueCode.of("tool.world.layout_mixed");

  /** A layout the Tool can read but has not verified for writing (for example Paper's folders). */
  public static final IssueCode LAYOUT_UNVERIFIED = IssueCode.of("tool.world.layout_unverified");

  /** A dimension directory name that is not a valid dimension key. */
  public static final IssueCode DIMENSION_NAME_INVALID =
      IssueCode.of("tool.world.dimension_name_invalid");

  /** A symbolic link where the Tool expects a plain file or directory; never followed. */
  public static final IssueCode SYMLINK_REFUSED = IssueCode.of("tool.world.symlink_refused");

  /** A file in a region directory that is not {@code r.<x>.<z>.mca}; ignored. */
  public static final IssueCode UNKNOWN_FILE = IssueCode.of("tool.world.unknown_file");

  /** A region file shorter than its 8 KiB header. */
  public static final IssueCode REGION_HEADER_TRUNCATED =
      IssueCode.of("tool.world.region_header_truncated");

  /** A region-header entry with an impossible offset or sector count, or past the end of file. */
  public static final IssueCode REGION_ENTRY_INVALID =
      IssueCode.of("tool.world.region_entry_invalid");

  /** Two region-header entries claim the same sectors. */
  public static final IssueCode REGION_ENTRIES_OVERLAP =
      IssueCode.of("tool.world.region_entries_overlap");

  /** The data version is not the pinned one, so the world is inspect-only. */
  public static final IssueCode VERSION_NOT_PINNED = IssueCode.of("tool.world.version_not_pinned");

  /** Whether a server holds the world's {@code session.lock} could not be determined. */
  public static final IssueCode LOCK_UNKNOWN = IssueCode.of("tool.world.lock_unknown");

  private WorldIssueCodes() {}
}
