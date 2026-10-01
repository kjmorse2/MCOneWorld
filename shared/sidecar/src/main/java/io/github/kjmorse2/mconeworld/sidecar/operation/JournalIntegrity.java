// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

/** How far an operation's files can be trusted (operation-journal-v1.md §6). */
public enum JournalIntegrity {
  /** Plan and journal are valid; every entry is trusted. */
  INTACT,
  /**
   * Every complete entry is valid, but the file ends in an unterminated entry: an append that was
   * interrupted and therefore never acknowledged. The recommendation is derived from the complete
   * entries. Appending requires an explicit {@link OperationJournals#discardTornTail}.
   */
  TORN_TAIL,
  /**
   * A complete entry is invalid (checksum, chain, sequence, state, or rule). That is corruption,
   * not an interrupted write: entries after it cannot be trusted, and a person must inspect.
   */
  CORRUPT,
  /** The plan file no longer has the digest the journal recorded. */
  PLAN_CHANGED,
  /** The plan file is missing, too large, or invalid. */
  PLAN_UNREADABLE,
  /** The plan file exists, but its journal was never started: nothing was applied. */
  NOT_RECORDED
}
