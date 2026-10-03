// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core;

import io.github.kjmorse2.mconeworld.domain.operation.ToolIdentity;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;

/** Who this program is, as recorded in plans and in the sidecar writer lock. */
public final class ToolInfo {
  /** The program name. */
  public static final String NAME = "mconeworld-tool";

  /** The version recorded in plans; the release process sets the real one (T14). */
  public static final String VERSION = "0.1.0";

  /** As recorded in operation plans. */
  public static final ToolIdentity IDENTITY = ToolIdentity.of(NAME, VERSION);

  /** As recorded in {@code writer.owner.json}. */
  public static final LockOwner LOCK_OWNER = new LockOwner(NAME, VERSION);

  private ToolInfo() {}
}
