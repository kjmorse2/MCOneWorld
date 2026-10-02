// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes carried by {@link ChunkIndexException} and by the findings of {@link
 * ChunkIndexStore#inspect()} and verification. These are a public contract: never rename or
 * repurpose one. The format they refer to is {@code docs/formats/chunk-index-v1.md}.
 */
public final class ChunkIndexIssueCodes {
  // --- mutations -------------------------------------------------------------------------------

  /**
   * The mutation needs an existing record, and the chunk has none. Activity and protection are
   * never recorded for a chunk without a first observation, because inventing one would be a guess.
   */
  public static final IssueCode RECORD_ABSENT = IssueCode.of("sidecar.index.record_absent");

  /** A replacement record does not describe the chunk it is filed under. */
  public static final IssueCode RECORD_MISMATCH = IssueCode.of("sidecar.index.record_mismatch");

  // --- lifecycle -------------------------------------------------------------------------------

  /** {@code chunk-index/} holds no index; create one explicitly. */
  public static final IssueCode INDEX_MISSING = IssueCode.of("sidecar.index.index_missing");

  /** An index (or unexplained content) already exists where one was to be created. */
  public static final IssueCode INDEX_EXISTS = IssueCode.of("sidecar.index.index_exists");

  /** The index was written by a newer minor version: readable, but this reader will not write. */
  public static final IssueCode READ_ONLY = IssueCode.of("sidecar.index.read_only");

  /**
   * A write failed after it may have reached the disk, so this handle no longer knows the durable
   * state. Close it and reopen; reopening reads exactly what is on disk.
   */
  public static final IssueCode HANDLE_BROKEN = IssueCode.of("sidecar.index.handle_broken");

  /** A file in {@code chunk-index/} that the format cannot explain; never removed automatically. */
  public static final IssueCode UNEXPLAINED_FILE = IssueCode.of("sidecar.index.unexplained_file");

  // --- versions --------------------------------------------------------------------------------

  /** A file's format major version is not supported. Fails closed. */
  public static final IssueCode UNSUPPORTED_VERSION =
      IssueCode.of("sidecar.index.unsupported_version");

  /** WARNING: a file was written by a newer minor version; the index is read-only here. */
  public static final IssueCode NEWER_MINOR_VERSION =
      IssueCode.of("sidecar.index.newer_minor_version");

  // --- corruption (each names its scope) ---------------------------------------------------------

  /** A file header is damaged: bad magic, bad checksum, or impossible values. */
  public static final IssueCode HEADER_CORRUPT = IssueCode.of("sidecar.index.header_corrupt");

  /** A file belongs to another index (its index id differs from the catalog's). */
  public static final IssueCode INDEX_ID_MISMATCH = IssueCode.of("sidecar.index.index_id_mismatch");

  /** The catalog fails its checksum or structure. The whole index is unusable until restored. */
  public static final IssueCode CATALOG_CORRUPT = IssueCode.of("sidecar.index.catalog_corrupt");

  /** A region block (one 32 × 32 region) fails its checksum or structure. */
  public static final IssueCode BLOCK_CORRUPT = IssueCode.of("sidecar.index.block_corrupt");

  /** A complete log frame fails its checksum or structure (not a torn tail). */
  public static final IssueCode LOG_CORRUPT = IssueCode.of("sidecar.index.log_corrupt");

  /** A segment the catalog references is missing or too short. */
  public static final IssueCode SEGMENT_MISSING = IssueCode.of("sidecar.index.segment_missing");

  /** A catalog summary disagrees with its block. Summaries are derived and never trusted. */
  public static final IssueCode SUMMARY_MISMATCH = IssueCode.of("sidecar.index.summary_mismatch");

  /** A logged mutation no longer applies to its checkpoint record. */
  public static final IssueCode MUTATION_INAPPLICABLE =
      IssueCode.of("sidecar.index.mutation_inapplicable");

  /** A size or count exceeds a hard limit; refused before allocating. */
  public static final IssueCode TOO_LARGE = IssueCode.of("sidecar.index.too_large");

  // --- findings (not failures) -------------------------------------------------------------------

  /**
   * WARNING: the log ends in an incomplete frame: a commit that was never acknowledged. Readers
   * ignore it; the next commit checkpoints first so nothing is ever appended after it.
   */
  public static final IssueCode TORN_TAIL = IssueCode.of("sidecar.index.torn_tail");

  /** INFO: a segment no catalog references, for example from an interrupted checkpoint. */
  public static final IssueCode ORPHAN_SEGMENT = IssueCode.of("sidecar.index.orphan_segment");

  /** INFO: a log for a generation older than the catalog; its checkpoint completed. */
  public static final IssueCode STALE_LOG = IssueCode.of("sidecar.index.stale_log");

  /** INFO: a temporary catalog from an interrupted checkpoint; the next checkpoint replaces it. */
  public static final IssueCode INTERRUPTED_CHECKPOINT =
      IssueCode.of("sidecar.index.interrupted_checkpoint");

  // --- I/O -------------------------------------------------------------------------------------

  /** An I/O failure that is not known to be corruption. */
  public static final IssueCode IO_FAILURE = IssueCode.of("sidecar.index.io_failure");

  private ChunkIndexIssueCodes() {}
}
