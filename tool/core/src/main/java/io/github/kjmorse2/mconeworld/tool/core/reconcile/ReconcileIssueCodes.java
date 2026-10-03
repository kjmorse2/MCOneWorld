// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.reconcile;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes of the reconcile workflow. Never rename or repurpose one. */
public final class ReconcileIssueCodes {
  /** The session does not allow reconciling (see the findings for every reason). */
  public static final IssueCode NOT_ALLOWED = IssueCode.of("tool.reconcile.not_allowed");

  /** Protected chunks are gone from the world; their removal must be acknowledged explicitly. */
  public static final IssueCode PROTECTED_REMOVAL =
      IssueCode.of("tool.reconcile.protected_removal");

  /** The index already matches the world and the sidecar is bound: nothing to do. */
  public static final IssueCode NOTHING_TO_DO = IssueCode.of("tool.reconcile.nothing_to_do");

  /** The world or the sidecar changed since planning. */
  public static final IssueCode CHANGED = IssueCode.of("tool.reconcile.changed");

  /** A post-apply check failed; the operation is marked failed. */
  public static final IssueCode VALIDATION_FAILED =
      IssueCode.of("tool.reconcile.validation_failed");

  /** The journal is not a reconcile, or not in a state this Tool can continue. */
  public static final IssueCode NOT_RESUMABLE = IssueCode.of("tool.reconcile.not_resumable");

  private ReconcileIssueCodes() {}
}
