// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core;

/**
 * Receives progress from long-running workflows, on the worker thread. Implementations must be
 * quick and must not throw. Cancelling is done by interrupting the worker thread, never through
 * this interface.
 */
@FunctionalInterface
public interface ProgressListener {
  /** Ignores progress. */
  ProgressListener NONE = (phase, done, total) -> {};

  /**
   * Reports progress.
   *
   * @param phase what is being done, for display
   * @param done units finished so far
   * @param total units in all, or -1 if unknown
   */
  void progress(String phase, long done, long total);
}
