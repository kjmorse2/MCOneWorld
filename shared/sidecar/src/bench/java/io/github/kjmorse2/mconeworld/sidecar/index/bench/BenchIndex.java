// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;

/** An index under measurement: the contract plus the maintenance calls the harness times. */
interface BenchIndex extends ChunkIndex {

  /** Folds logged mutations into blocks. */
  void checkpoint() throws ChunkIndexException;

  /** Runs full verification; throws if it finds any error. */
  void verify() throws ChunkIndexException;
}
