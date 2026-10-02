// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.lock;

/** Whether a Minecraft process has the world open. */
public enum SessionLockState {
  /** There is no {@code session.lock}: the world has never been opened by a recent version. */
  ABSENT,
  /** The lock is free: no Minecraft process has the world open right now. */
  FREE,
  /** Another process holds the lock: a server or game has the world open. */
  HELD,
  /** The probe could not tell (for example, no write permission). Treated as held. */
  UNKNOWN;

  /** Whether the world may be modified: only when nothing can hold it. */
  public boolean allowsWrites() {
    return this == FREE || this == ABSENT;
  }
}
