// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

/** A reader of a stored index, which also reports the committed state it reads. */
public interface ChunkIndexSnapshot extends ChunkIndexReader {

  /** The committed state this snapshot reads; it never changes for this snapshot. */
  ChunkIndexState state();
}
