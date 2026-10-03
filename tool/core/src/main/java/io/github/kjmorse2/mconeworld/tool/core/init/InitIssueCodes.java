// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.init;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes of the initialization workflow. Never rename or repurpose one. */
public final class InitIssueCodes {
  /** The session does not allow initialization (see the findings for every reason). */
  public static final IssueCode NOT_ALLOWED = IssueCode.of("tool.init.not_allowed");

  /** The world's seed is unknown, so the sidecar cannot be bound to it. */
  public static final IssueCode SEED_UNKNOWN = IssueCode.of("tool.init.seed_unknown");

  /** The world changed between planning and applying (its region headers differ). */
  public static final IssueCode WORLD_CHANGED = IssueCode.of("tool.init.world_changed");

  /** The sidecar holds a manifest or index that this operation did not create. */
  public static final IssueCode SIDECAR_CONFLICT = IssueCode.of("tool.init.sidecar_conflict");

  /** A post-apply check failed; the operation is marked failed. */
  public static final IssueCode VALIDATION_FAILED = IssueCode.of("tool.init.validation_failed");

  /** The journal is not an initialization, or not in a state this Tool can continue. */
  public static final IssueCode NOT_RESUMABLE = IssueCode.of("tool.init.not_resumable");

  private InitIssueCodes() {}
}
