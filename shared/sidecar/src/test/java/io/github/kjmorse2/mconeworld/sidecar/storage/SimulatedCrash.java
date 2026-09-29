// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.Serial;

/**
 * Thrown by {@link FaultyFileSystem} to model the process dying at a precise point. It extends
 * {@link Error}, so production code (which handles {@link Exception}) cannot roll back: whatever is
 * on disk at that moment is exactly what a real crash would leave.
 */
final class SimulatedCrash extends Error {
  @Serial private static final long serialVersionUID = 1L;

  SimulatedCrash(String where) {
    super("simulated crash at " + where);
  }
}
