// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

/**
 * Points in a {@link SidecarTransaction} where {@link StorageFileSystem#checkpoint} is called.
 * Production file systems do nothing here. Tests inject failures or simulated crashes to prove that
 * every stage is recoverable. Stages are listed in execution order.
 */
public enum CommitStage {
  /** After the staging directory exists, before any content is written. */
  BEFORE_STAGING,
  /** While a data file's content is being written (called per file). */
  WRITING_CONTENT,
  /** Before a staged data file is flushed to stable storage (called per file). */
  BEFORE_SYNC,
  /** Before staged files are re-read and checked against their recorded checksums. */
  BEFORE_CHECKSUM_VERIFICATION,
  /** Staged files and the intent are durable; the manifest has not been replaced yet. */
  BEFORE_MANIFEST_REPLACEMENT,
  /** The new manifest has just replaced the old one: the commit point has passed. */
  AFTER_MANIFEST_REPLACEMENT,
  /** While removing the staging directory after a commit or abort. */
  DURING_CLEANUP
}
