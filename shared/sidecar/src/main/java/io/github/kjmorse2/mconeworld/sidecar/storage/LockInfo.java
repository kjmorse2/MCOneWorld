// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Metadata stored in {@code writer.lock}, so an operator can see who holds the sidecar.
 *
 * <p>It deliberately contains no secrets: no command line, no environment, no user name, and no
 * paths. The host name and process id are there so an operator can find the owner.
 *
 * @param lockId unique id of this acquisition
 * @param tool the owning program, for example {@code mconeworld-tool}
 * @param toolVersion the owning program's version
 * @param host host name of the owner (bounded)
 * @param pid operating-system process id of the owner
 * @param acquiredAt when the lock was acquired
 */
public record LockInfo(
    UUID lockId, String tool, String toolVersion, String host, long pid, Instant acquiredAt) {
  /** Maximum length of each text field. */
  public static final int MAX_TEXT = 64;

  public LockInfo {
    Objects.requireNonNull(lockId, "lockId");
    tool = bounded(tool);
    toolVersion = bounded(toolVersion);
    host = bounded(host);
    Objects.requireNonNull(acquiredAt, "acquiredAt");
  }

  private static String bounded(String text) {
    Objects.requireNonNull(text, "text");
    StringBuilder out = new StringBuilder();
    text.codePoints()
        .filter(c -> c >= 0x20 && c != 0x7F)
        .limit(MAX_TEXT)
        .forEach(out::appendCodePoint);
    return out.toString();
  }
}
