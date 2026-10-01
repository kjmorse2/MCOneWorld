// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes for plan and journal files. Rule violations of the state machine itself use {@code
 * domain.operation.OperationIssueCodes}. These codes are a public contract: never rename or
 * repurpose one.
 */
public final class JournalIssueCodes {
  // --- format ---------------------------------------------------------------------------------

  /** The document's {@code format} member is not the expected format. */
  public static final IssueCode WRONG_FORMAT = IssueCode.of("sidecar.operation.wrong_format");

  /** The schema major version is not supported. */
  public static final IssueCode UNSUPPORTED_VERSION =
      IssueCode.of("sidecar.operation.unsupported_version");

  /** WARNING: written by a newer minor version; readable, but this reader will not append. */
  public static final IssueCode NEWER_MINOR_VERSION =
      IssueCode.of("sidecar.operation.newer_minor_version");

  /** A required member is missing. */
  public static final IssueCode MISSING_MEMBER = IssueCode.of("sidecar.operation.missing_member");

  /** A member has the wrong JSON type. */
  public static final IssueCode WRONG_TYPE = IssueCode.of("sidecar.operation.wrong_type");

  /** A member this schema version does not define. */
  public static final IssueCode UNKNOWN_MEMBER = IssueCode.of("sidecar.operation.unknown_member");

  /** A member's value is outside its domain. */
  public static final IssueCode INVALID_VALUE = IssueCode.of("sidecar.operation.invalid_value");

  // --- integrity ------------------------------------------------------------------------------

  /** A journal entry's checksum does not match its content. */
  public static final IssueCode ENTRY_CHECKSUM_MISMATCH =
      IssueCode.of("sidecar.operation.entry_checksum_mismatch");

  /** A journal entry's {@code previous} is not the checksum of the entry before it. */
  public static final IssueCode CHAIN_BROKEN = IssueCode.of("sidecar.operation.chain_broken");

  /** A journal entry names a different operation than its directory. */
  public static final IssueCode OPERATION_MISMATCH =
      IssueCode.of("sidecar.operation.operation_mismatch");

  /** The journal ends in an unterminated entry: a write that was never acknowledged. */
  public static final IssueCode TORN_TAIL = IssueCode.of("sidecar.operation.torn_tail");

  /** A complete journal entry is invalid: corruption, not an interrupted write. */
  public static final IssueCode CORRUPT_ENTRY = IssueCode.of("sidecar.operation.corrupt_entry");

  /** The plan file's digest differs from the digest the journal recorded. */
  public static final IssueCode PLAN_CHANGED = IssueCode.of("sidecar.operation.plan_changed");

  /** A journal exists without a plan file, or the plan file cannot be read. */
  public static final IssueCode PLAN_UNREADABLE = IssueCode.of("sidecar.operation.plan_unreadable");

  /** A plan file exists but its journal was never started. */
  public static final IssueCode NOT_RECORDED = IssueCode.of("sidecar.operation.not_recorded");

  /** A plan or journal file is larger than its limit. */
  public static final IssueCode TOO_LARGE = IssueCode.of("sidecar.operation.too_large");

  // --- appending ------------------------------------------------------------------------------

  /** The caller's expected sequence number is not the next one. */
  public static final IssueCode SEQUENCE_CONFLICT =
      IssueCode.of("sidecar.operation.sequence_conflict");

  /** The journal changed on disk since this handle read it. */
  public static final IssueCode HANDLE_STALE = IssueCode.of("sidecar.operation.handle_stale");

  /** An earlier append through this handle failed; reopen the journal before appending. */
  public static final IssueCode HANDLE_BROKEN = IssueCode.of("sidecar.operation.handle_broken");

  /** The event breaks a state-machine rule; the details carry the domain issues. */
  public static final IssueCode EVENT_REJECTED = IssueCode.of("sidecar.operation.event_rejected");

  /** The journal cannot be appended to in its current state; see the attached status. */
  public static final IssueCode NOT_WRITABLE = IssueCode.of("sidecar.operation.not_writable");

  /** A different plan is already recorded under this operation id. */
  public static final IssueCode ALREADY_EXISTS = IssueCode.of("sidecar.operation.already_exists");

  /** The operation has no plan and no journal. */
  public static final IssueCode NOT_FOUND = IssueCode.of("sidecar.operation.not_found");

  private JournalIssueCodes() {}
}
