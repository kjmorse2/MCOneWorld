// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;

/** A candidate chunk index under measurement. */
public interface SpikeIndex extends ChunkIndex {

  /**
   * Runs the candidate's strongest integrity check over everything it stores, as an administrator
   * would before trusting the index. Throws if any problem is found.
   */
  void verify() throws ChunkIndexException;
}
