// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import org.jspecify.annotations.Nullable;

/**
 * Result of {@link SidecarLock#inspect(SidecarRoot)}: a read-only view of {@code writer.lock}.
 *
 * <ul>
 *   <li>{@code held}: a live writer holds the OS lock.
 *   <li>{@code !held && info != null}: <b>provably stale</b>. The owner wrote complete metadata
 *       while holding the OS lock, and the OS has since released it, which happens only when the
 *       owner exits.
 *   <li>{@code !held && info == null}: an incomplete lock file. Either a writer is in the
 *       microseconds between creating and locking it, or one crashed in that window. This is never
 *       taken over automatically. If no MCOneWorld process is running, an operator may delete it.
 * </ul>
 *
 * @param info the recorded owner, or {@code null} if the metadata is incomplete
 * @param held whether a live writer holds the OS lock
 */
public record LockStatus(@Nullable LockInfo info, boolean held) {
  /** Returns whether the lock is provably stale (see the class documentation). */
  public boolean provablyStale() {
    return !held && info != null;
  }
}
