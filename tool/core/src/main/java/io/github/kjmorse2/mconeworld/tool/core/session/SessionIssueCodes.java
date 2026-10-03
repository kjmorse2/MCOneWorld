// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/** Stable codes for session-level findings and action blockers. Never rename or repurpose one. */
public final class SessionIssueCodes {
  /** The world and sidecar directories are the same, or one is inside the other. */
  public static final IssueCode PATHS_OVERLAP = IssueCode.of("tool.session.paths_overlap");

  /** The sidecar directory exists and holds files but no manifest. */
  public static final IssueCode SIDECAR_INCOMPLETE =
      IssueCode.of("tool.session.sidecar_incomplete");

  /** The manifest cannot be read by this version of the Tool. */
  public static final IssueCode SIDECAR_UNREADABLE =
      IssueCode.of("tool.session.sidecar_unreadable");

  /** The manifest is from an older schema and needs an explicit migration. */
  public static final IssueCode MIGRATION_NEEDED = IssueCode.of("tool.session.migration_needed");

  /** Minecraft has the world open, or whether it does is unknown. */
  public static final IssueCode SERVER_RUNNING = IssueCode.of("tool.session.server_running");

  /** Another process (normally the Paper plugin) holds the sidecar writer lock. */
  public static final IssueCode SIDECAR_LOCKED = IssueCode.of("tool.session.sidecar_locked");

  /** An interrupted sidecar commit left something behind. */
  public static final IssueCode RECOVERY_NEEDED = IssueCode.of("tool.session.recovery_needed");

  /** An operation journal is not finished, or is damaged. */
  public static final IssueCode OPERATION_UNFINISHED =
      IssueCode.of("tool.session.operation_unfinished");

  /**
   * The manifest was written by a newer MCOneWorld (a newer minor schema). The Tool reads it but
   * never writes it (manifest-v1.md §5.1).
   */
  public static final IssueCode SIDECAR_NEWER = IssueCode.of("tool.session.sidecar_newer");

  /** The sidecar is bound to a world with a different seed: it belongs to another world. */
  public static final IssueCode WORLD_MISMATCH = IssueCode.of("tool.session.world_mismatch");

  /** The sidecar has no world binding yet (a schema 1.0 sidecar). Reconcile records one. */
  public static final IssueCode WORLD_UNBOUND = IssueCode.of("tool.session.world_unbound");

  /** The active season's offline activation has not been applied by the server yet (ADR 0011). */
  public static final IssueCode ACTIVATION_PENDING =
      IssueCode.of("tool.session.activation_pending");

  /** The chunk index is missing or not usable. */
  public static final IssueCode INDEX_UNUSABLE = IssueCode.of("tool.session.index_unusable");

  private SessionIssueCodes() {}
}
