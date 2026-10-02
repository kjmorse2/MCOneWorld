// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

/**
 * One committed state: a checkpoint catalog plus the mutations logged after it. Immutable, so a
 * reader holding it never sees later commits.
 *
 * @param logEnd the length of the log's complete frames (where the next commit appends)
 * @param tornTail whether the log file continues past {@code logEnd} with an incomplete frame
 * @param logExists whether the log file for this generation exists
 */
record IndexSnapshot(
    Catalog catalog,
    Overlay overlay,
    long logEnd,
    boolean tornTail,
    boolean logExists,
    boolean readOnly,
    int catalogCrc,
    int logTailCrc) {

  ChunkIndexState state() {
    return new ChunkIndexState(
        catalog.indexId,
        IndexFormat.MAJOR + "." + IndexFormat.MINOR,
        catalog.generation,
        logEnd,
        overlay.mutations(),
        readOnly,
        catalogCrc,
        logTailCrc);
  }
}
