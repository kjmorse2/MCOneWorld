// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.util.Objects;

/**
 * Identifies the program that acquires the writer lock, for example {@code new
 * LockOwner("mconeworld-paper", "0.1.0")}. The host name and process id are added automatically.
 *
 * @param tool program name
 * @param toolVersion program version
 */
public record LockOwner(String tool, String toolVersion) {
  public LockOwner {
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(toolVersion, "toolVersion");
  }
}
