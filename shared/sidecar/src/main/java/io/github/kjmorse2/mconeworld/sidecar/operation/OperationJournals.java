// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEntry;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationProgress;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTransitions;
import io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction;
import io.github.kjmorse2.mconeworld.domain.operation.RecoveryRecommendation;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.OperationFileWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.OperationFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageIssueCodes;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The operation plans and journals of one sidecar, in {@code operations/<operation-id>/} (see
 * operation-journal-v1.md).
 *
 * <ul>
 *   <li>{@link #list()} and {@link #inspect} are read-only: they never write, repair, or resume.
 *   <li>{@link #create}, {@link #open}, and {@link #discardTornTail} need a {@link SidecarWriter}
 *       for the same sidecar, so every write happens under the writer lock.
 * </ul>
 *
 * <p>Thread-safe; the returned {@link OperationJournal} handles are not.
 */
public final class OperationJournals {
  /** The plan file name inside an operation directory. */
  public static final String PLAN_FILE = "plan.json";

  /** The journal file name inside an operation directory. */
  public static final String JOURNAL_FILE = "journal.jsonl";

  /** Prefix of files that preserve a discarded torn tail. */
  public static final String TORN_PREFIX = "journal.torn-";

  private final SidecarStore store;
  private final Clock clock;
  private final OperationPlanCodec planCodec = new OperationPlanCodec();
  private final JournalEntryCodec entryCodec = new JournalEntryCodec();

  private OperationJournals(SidecarStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Returns the journals of {@code store}.
   *
   * @param store the sidecar
   * @param clock supplies {@code recordedAt} for new entries (audit only: order is by sequence)
   */
  public static OperationJournals of(SidecarStore store, Clock clock) {
    return new OperationJournals(store, clock);
  }

  /**
   * The ids of every operation directory, sorted. Directory names that are not canonical operation
   * ids are not MCOneWorld operations and are ignored, never touched.
   */
  public List<OperationId> list() throws IOException {
    List<OperationId> ids = new ArrayList<>();
    for (String name : files().list(null)) {
      try {
        ids.add(OperationId.parse(name));
      } catch (InvalidDomainValueException e) {
        // Not an operation directory.
      }
    }
    ids.sort(null);
    return List.copyOf(ids);
  }

  /**
   * Assesses one operation. Read-only.
   *
   * @throws JournalException {@code NOT_FOUND} if it has neither plan nor journal
   */
  public OperationStatus inspect(OperationId id) throws IOException {
    return scan(id).status();
  }

  /**
   * Records a new operation: writes its plan, then the first journal entry ({@code PLAN_RECORDED}),
   * each durably. Idempotent: if exactly this plan is already recorded, the existing journal is
   * opened; if its plan file was written but the journal never started, recording completes.
   *
   * @throws JournalException {@code ALREADY_EXISTS} if a different plan has this id, or {@code
   *     NOT_WRITABLE} if the existing journal cannot be appended to
   */
  public OperationJournal create(SidecarWriter writer, OperationPlan plan) throws IOException {
    OperationFileWriter out = writerFor(writer);
    byte[] bytes = planCodec.encode(plan);
    try {
      out.writeOnce(path(plan.id(), PLAN_FILE), bytes);
    } catch (StorageException e) {
      if (e.code().equals(StorageIssueCodes.DATA_FILE_CONFLICT)) {
        throw new JournalException(
            JournalIssueCodes.ALREADY_EXISTS,
            "A different plan is already recorded as operation " + plan.id(),
            ValidationResult.ok(),
            null,
            e);
      }
      throw e;
    }
    Scan scan = scan(plan.id());
    if (scan.status().integrity() == JournalIntegrity.NOT_RECORDED) {
      Checksum digest = OperationPlanCodec.sha256(bytes);
      JournalEntry first =
          new JournalEntry(
              0,
              clock.instant(),
              OperationProgress.firstEvent(plan, digest),
              OperationTransitions.initialState());
      JournalEntryCodec.Encoded encoded = entryCodec.encode(first, plan.id(), null);
      out.append(path(plan.id(), JOURNAL_FILE), scan.size(), encoded.line());
      scan = scan(plan.id());
    }
    return handle(writer, scan);
  }

  /**
   * Opens an existing journal for appending.
   *
   * @throws JournalException {@code NOT_WRITABLE}, with the status attached, unless the journal is
   *     intact and fully understood by this reader
   */
  public OperationJournal open(SidecarWriter writer, OperationId id) throws IOException {
    writerFor(writer);
    return handle(writer, scan(id));
  }

  /**
   * Explicitly discards a torn final entry, after a person or policy decided to continue. The torn
   * bytes are first preserved in {@code journal.torn-<sequence>-<hash>.bin}; then the journal is
   * atomically replaced by its complete entries. Nothing that was acknowledged is removed: a torn
   * entry was never acknowledged.
   *
   * @return the status afterwards
   * @throws JournalException {@code NOT_WRITABLE} unless the journal's integrity is {@code
   *     TORN_TAIL}
   */
  public OperationStatus discardTornTail(SidecarWriter writer, OperationId id) throws IOException {
    OperationFileWriter out = writerFor(writer);
    Scan scan = scan(id);
    if (scan.status().integrity() != JournalIntegrity.TORN_TAIL) {
      throw notWritable(scan.status(), "There is no torn tail to discard");
    }
    byte[] journal = files().read(path(id, JOURNAL_FILE), OperationPlanLimits.MAX_JOURNAL_BYTES);
    int keep = (int) scan.size();
    byte[] torn = Arrays.copyOfRange(journal, keep, journal.length);
    String hash = OperationPlanCodec.sha256(torn).hex().substring(0, 16);
    out.writeOnce(
        path(id, TORN_PREFIX + scan.status().trustedEntries() + "-" + hash + ".bin"), torn);
    out.replace(
        path(id, JOURNAL_FILE),
        OperationPlanCodec.sha256(journal),
        Arrays.copyOf(journal, keep),
        OperationPlanLimits.MAX_JOURNAL_BYTES);
    return inspect(id);
  }

  // --- internals ------------------------------------------------------------------------------

  /**
   * Everything a scan learned: the public status, plus what an appending handle needs.
   *
   * @param size the byte length of the trusted entries
   */
  record Scan(
      OperationStatus status,
      long size,
      @Nullable Checksum lastChecksum,
      @Nullable JournalEntry lastEntry) {}

  private OperationJournal handle(SidecarWriter writer, Scan scan) throws JournalException {
    OperationStatus status = scan.status();
    OperationProgress progress = status.progress();
    JournalEntry last = scan.lastEntry();
    Checksum lastChecksum = scan.lastChecksum();
    if (!status.writable() || progress == null || last == null || lastChecksum == null) {
      throw notWritable(status, "Operation " + status.operation() + " cannot be appended to");
    }
    return new OperationJournal(
        writer,
        entryCodec,
        clock,
        path(status.operation(), JOURNAL_FILE),
        progress,
        scan.size(),
        last,
        lastChecksum);
  }

  private static JournalException notWritable(OperationStatus status, String message) {
    return new JournalException(
        JournalIssueCodes.NOT_WRITABLE,
        message + " (" + status.integrity() + ")",
        status.issues(),
        status,
        null);
  }

  private Scan scan(OperationId id) throws IOException {
    OperationFiles files = files();
    SidecarPath planPath = path(id, PLAN_FILE);
    SidecarPath journalPath = path(id, JOURNAL_FILE);
    boolean planExists = files.exists(planPath);
    boolean journalExists = files.exists(journalPath);
    if (!planExists && !journalExists) {
      throw new JournalException(
          JournalIssueCodes.NOT_FOUND, "Operation " + id + " has no plan and no journal");
    }
    List<ValidationIssue> issues = new ArrayList<>();
    DecodedPlan plan = null;
    if (!planExists) {
      issues.add(issue(JournalIssueCodes.PLAN_UNREADABLE, "The plan file is missing"));
    } else {
      try {
        plan = planCodec.decode(files.read(planPath, OperationPlanLimits.MAX_PLAN_BYTES));
        if (!plan.executable()) {
          issues.add(
              ValidationIssue.warning(
                  JournalIssueCodes.NEWER_MINOR_VERSION,
                  IssueLocation.root().field("plan"),
                  "The plan was written by schema "
                      + plan.schemaVersion()
                      + "; this reader only inspects it"));
        }
      } catch (OperationFormatException e) {
        issues.add(e.issue());
      } catch (StorageException e) {
        if (!e.code().equals(StorageIssueCodes.TOO_LARGE)) {
          throw e;
        }
        issues.add(issue(JournalIssueCodes.TOO_LARGE, "The plan file is too large"));
      }
    }
    if (plan == null) {
      return new Scan(
          status(
              id,
              JournalIntegrity.PLAN_UNREADABLE,
              null,
              0,
              0,
              null,
              RecoveryRecommendation.manualInspection(
                  null, "The plan cannot be read, so the journal cannot be interpreted."),
              issues,
              false),
          0,
          null,
          null);
    }
    byte[] journal;
    try {
      journal =
          journalExists
              ? files.read(journalPath, OperationPlanLimits.MAX_JOURNAL_BYTES)
              : new byte[0];
    } catch (StorageException e) {
      if (!e.code().equals(StorageIssueCodes.TOO_LARGE)) {
        throw e;
      }
      issues.add(issue(JournalIssueCodes.TOO_LARGE, "The journal file is too large"));
      return new Scan(
          status(
              id,
              JournalIntegrity.CORRUPT,
              plan,
              0,
              0,
              null,
              RecoveryRecommendation.manualInspection(null, "The journal is too large to read."),
              issues,
              false),
          0,
          null,
          null);
    }
    return scanJournal(id, plan, journal, issues);
  }

  /** Classifies journal bytes against a readable plan. Pure: no I/O. */
  Scan scanJournal(OperationId id, DecodedPlan plan, byte[] journal, List<ValidationIssue> issues) {
    List<int[]> lines = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < journal.length; i++) {
      if (journal[i] == JournalEntryCodec.NEWLINE) {
        lines.add(new int[] {start, i});
        start = i + 1;
      }
    }
    boolean torn = start < journal.length;
    OperationProgress progress = null;
    Checksum previous = null;
    JournalEntry last = null;
    boolean newerEntry = false;
    long trustedBytes = 0;
    int trusted = 0;
    JournalIntegrity integrity = null;
    for (int[] range : lines) {
      IssueLocation at = IssueLocation.root().field("journal").index(trusted);
      JournalEntryCodec.Decoded decoded;
      try {
        decoded = entryCodec.decode(Arrays.copyOfRange(journal, range[0], range[1]));
      } catch (OperationFormatException e) {
        issues.add(corrupt(at, e.issue().code() + ": " + e.issue().message()));
        integrity = JournalIntegrity.CORRUPT;
        break;
      }
      JournalEntry entry = decoded.entry();
      String problem = null;
      if (!decoded.operation().equals(id)) {
        problem = JournalIssueCodes.OPERATION_MISMATCH + ": the entry names another operation";
      } else if (!Objects.equals(decoded.previous(), previous)) {
        problem = JournalIssueCodes.CHAIN_BROKEN + ": the entry does not follow the previous one";
      } else if (progress == null) {
        if (entry.event() instanceof JournalEvent.PlanRecorded recorded
            && entry.sequence() == 0
            && !recorded.planDigest().equals(plan.digest())) {
          issues.add(
              ValidationIssue.error(
                  JournalIssueCodes.PLAN_CHANGED,
                  IssueLocation.root().field("plan"),
                  "The plan file's digest differs from the digest the journal recorded"));
          integrity = JournalIntegrity.PLAN_CHANGED;
          break;
        }
        problem = firstProblem(OperationProgress.checkFirst(plan.plan(), plan.digest(), entry));
      } else {
        problem = firstProblem(progress.check(entry));
      }
      if (problem != null) {
        issues.add(corrupt(at, problem));
        integrity = JournalIntegrity.CORRUPT;
        break;
      }
      progress =
          progress == null
              ? OperationProgress.planned(plan.plan(), plan.digest())
              : progress.apply(entry);
      previous = decoded.checksum();
      last = entry;
      newerEntry |= decoded.schemaVersion().compareTo(JournalEntryCodec.CURRENT) > 0;
      trustedBytes = range[1] + 1L;
      trusted++;
    }
    if (newerEntry) {
      issues.add(
          ValidationIssue.warning(
              JournalIssueCodes.NEWER_MINOR_VERSION,
              IssueLocation.root().field("journal"),
              "Entries were written by a newer schema; this reader only inspects the journal"));
    }
    if (integrity == null) {
      if (torn) {
        integrity = JournalIntegrity.TORN_TAIL;
        issues.add(
            ValidationIssue.warning(
                    JournalIssueCodes.TORN_TAIL,
                    IssueLocation.root().field("journal").index(trusted),
                    "The journal ends in an interrupted, never acknowledged entry")
                .withRemediation("Discard it explicitly with discardTornTail() to continue."));
      } else if (trusted == 0) {
        integrity = JournalIntegrity.NOT_RECORDED;
      } else {
        integrity = JournalIntegrity.INTACT;
      }
    }
    if (integrity == JournalIntegrity.NOT_RECORDED || (torn && trusted == 0)) {
      issues.add(
          ValidationIssue.warning(
              JournalIssueCodes.NOT_RECORDED,
              IssueLocation.root().field("journal"),
              "The plan was written but its journal was never started"));
    }
    RecoveryRecommendation recommendation = recommend(integrity, progress, trusted, lines.size());
    boolean writable = integrity == JournalIntegrity.INTACT && plan.executable() && !newerEntry;
    return new Scan(
        status(
            id, integrity, plan, trusted, lines.size(), progress, recommendation, issues, writable),
        trustedBytes,
        previous,
        last);
  }

  private static RecoveryRecommendation recommend(
      JournalIntegrity integrity,
      @Nullable OperationProgress progress,
      int trusted,
      int completeLines) {
    RecoveryRecommendation base =
        progress == null
            ? new RecoveryRecommendation(
                null,
                RecoveryAction.RECORD_PLAN,
                Set.of(),
                false,
                true,
                null,
                null,
                "The plan was written but never recorded in a journal; nothing was applied.")
            : progress.recommend();
    return switch (integrity) {
      case INTACT, NOT_RECORDED -> base;
      case TORN_TAIL ->
          new RecoveryRecommendation(
              base.lastDurableState(),
              base.action(),
              base.alternatives(),
              base.partialEffectsPossible(),
              base.requiresConfirmation(),
              base.nextStep(),
              base.nextCheck(),
              "The last entry was torn by an interruption and never acknowledged; discard it"
                  + " before appending. "
                  + base.explanation());
      case CORRUPT ->
          RecoveryRecommendation.manualInspection(
              progress == null ? null : progress.state(),
              "Journal entry "
                  + trusted
                  + " of "
                  + completeLines
                  + " is corrupt; later entries cannot be trusted. Inspect the world, the"
                  + " sidecar, and the backup.");
      case PLAN_CHANGED ->
          RecoveryRecommendation.manualInspection(
              null, "The plan file changed after it was recorded; it cannot be trusted.");
      case PLAN_UNREADABLE ->
          RecoveryRecommendation.manualInspection(null, "The plan cannot be read.");
    };
  }

  private static @Nullable String firstProblem(ValidationResult result) {
    if (result.isValid()) {
      return null;
    }
    ValidationIssue first = result.withSeverity(Severity.ERROR).get(0);
    return first.code() + ": " + first.message();
  }

  private static ValidationIssue corrupt(IssueLocation at, String message) {
    return ValidationIssue.error(JournalIssueCodes.CORRUPT_ENTRY, at, message);
  }

  private static ValidationIssue issue(
      io.github.kjmorse2.mconeworld.domain.validation.IssueCode code, String message) {
    return ValidationIssue.error(code, IssueLocation.root().field("plan"), message);
  }

  private static OperationStatus status(
      OperationId id,
      JournalIntegrity integrity,
      @Nullable DecodedPlan plan,
      long trusted,
      long lines,
      @Nullable OperationProgress progress,
      RecoveryRecommendation recommendation,
      List<ValidationIssue> issues,
      boolean writable) {
    return new OperationStatus(
        id,
        integrity,
        plan,
        trusted,
        lines,
        progress,
        recommendation,
        ValidationResult.of(issues),
        writable);
  }

  private OperationFiles files() {
    return store.operationFiles();
  }

  private OperationFileWriter writerFor(SidecarWriter writer) {
    if (!writer.store().root().path().equals(store.root().path())) {
      throw new IllegalArgumentException("The writer belongs to a different sidecar");
    }
    return writer.operationFiles();
  }

  static SidecarPath path(OperationId id, String file) {
    return SidecarPath.of(id + "/" + file);
  }
}
