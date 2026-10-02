// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.failure;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.appendAll;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.digest;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.journalFile;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.journals;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.planFile;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.store;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.writer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.operation.ExpectedManifest;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.OperationIssueCodes;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationProgress;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockException;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageIssueCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Journal persistence: durability, idempotence, integrity classification, and the lock. */
class OperationJournalStoreTest {
  private static final OperationPlan PLAN = OperationFixtures.fullPlan();
  private static final Checksum DIGEST = digest(PLAN);
  private static final List<JournalEvent> EVENTS = OperationFixtures.goldenJournalEvents(DIGEST);

  @TempDir Path root;

  private static void assertJournalCode(ThrowingRunnable action, IssueCode code) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(StorageException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** Records PLAN and the first {@code count} golden events. */
  private void recordFirst(int count) throws IOException {
    SidecarStore store = store(root);
    try (SidecarWriter writer = writer(store)) {
      appendAll(journals(store).create(writer, PLAN), EVENTS.subList(0, count));
    }
  }

  private OperationStatus inspect() throws IOException {
    return journals(store(root)).inspect(PLAN.id());
  }

  private List<String> journalLines() throws IOException {
    return Files.readAllLines(journalFile(root, PLAN));
  }

  private void writeJournal(List<String> lines, String tail) throws IOException {
    Files.writeString(
        journalFile(root, PLAN),
        String.join("", lines.stream().map(l -> l + "\n").toList()) + tail);
  }

  @Nested
  class Recording {
    @Test
    void createWritesThePlanAndTheFirstEntryDurably() throws Exception {
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal = journals(store).create(writer, PLAN);
        assertThat(journal.progress().state()).isEqualTo(OperationState.PLANNED);
        assertThat(journal.progress().nextSequence()).isEqualTo(1);
      }
      assertThat(Files.readAllBytes(planFile(root, PLAN)))
          .isEqualTo(ManifestFixtures.resource(OperationFixtures.GOLDEN_PLAN_V1));
      assertThat(journalLines()).hasSize(1);
      OperationStatus status = inspect();
      assertThat(status.integrity()).isEqualTo(JournalIntegrity.INTACT);
      assertThat(status.writable()).isTrue();
      assertThat(status.recommendation().action()).isEqualTo(RecoveryAction.CONFIRM_BACKUP);
      assertThat(journals(store(root)).list()).containsExactly(PLAN.id());
    }

    /**
     * Prompt 11 audit: an append on an interrupted thread fails like any I/O failure. The journal
     * keeps the entries before it (and at most the interrupted one, never a torn middle), and a
     * reopened handle continues it.
     */
    @Test
    void anInterruptedAppendFailsCleanlyAndTheJournalContinues() throws Exception {
      recordFirst(2);
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal = journals(store).open(writer, PLAN.id());
        long next = journal.progress().nextSequence();
        Thread.currentThread().interrupt();
        try {
          assertThatThrownBy(() -> journal.append(next, EVENTS.get(2)))
              .isInstanceOf(IOException.class);
        } finally {
          Thread.interrupted();
        }
      }
      OperationStatus status = inspect();
      assertThat(status.integrity()).isIn(JournalIntegrity.INTACT, JournalIntegrity.TORN_TAIL);
      assertThat(journalLines().size()).isBetween(2, 3);
      if (status.integrity() == JournalIntegrity.TORN_TAIL) {
        try (SidecarWriter writer = writer(store(root))) {
          journals(store(root)).discardTornTail(writer, PLAN.id());
        }
      }
      try (SidecarWriter writer = writer(store(root))) {
        OperationJournal reopened = journals(store(root)).open(writer, PLAN.id());
        // Entry 0 is the plan record written by create, so event i has sequence i + 1.
        int done = (int) reopened.progress().nextSequence() - 1;
        assertThat(done).isBetween(2, 3);
        appendAll(reopened, EVENTS.subList(done, Math.min(done + 2, EVENTS.size())));
      }
      assertThat(inspect().integrity()).isEqualTo(JournalIntegrity.INTACT);
    }

    @Test
    void createIsIdempotentForTheSamePlanAndRefusesAnother() throws Exception {
      recordFirst(2);
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal again = journals(store).create(writer, PLAN);
        assertThat(again.progress().state()).isEqualTo(OperationState.APPLYING);
        OperationPlan other =
            new OperationCodecTest.OperationPlanParts(PLAN)
                .createdAt(DomainFixtures.instant(999))
                .build();
        assertJournalCode(
            () -> journals(store).create(writer, other), JournalIssueCodes.ALREADY_EXISTS);
      }
      assertThat(journalLines()).hasSize(3);
    }

    @Test
    void aPlanWithoutJournalIsNotRecordedAndCreateCompletesIt() throws Exception {
      Path dir = planFile(root, PLAN).getParent();
      Files.createDirectories(dir);
      Files.write(planFile(root, PLAN), JournalTestSupport.PLANS.encode(PLAN));
      OperationStatus status = inspect();
      assertThat(status.integrity()).isEqualTo(JournalIntegrity.NOT_RECORDED);
      assertThat(status.recommendation().action()).isEqualTo(RecoveryAction.RECORD_PLAN);
      assertThat(status.recommendation().partialEffectsPossible()).isFalse();
      recordFirst(0);
      assertThat(inspect().integrity()).isEqualTo(JournalIntegrity.INTACT);
    }

    @Test
    void persistedFilesHoldNoHostPaths() throws Exception {
      recordFirst(EVENTS.size());
      String rootText = root.toRealPath().toString();
      for (Path file : List.of(planFile(root, PLAN), journalFile(root, PLAN))) {
        String content = Files.readString(file);
        assertThat(content)
            .doesNotContain(rootText)
            .doesNotContain(System.getProperty("user.home"));
      }
    }

    @Test
    void listIgnoresForeignDirectories() throws Exception {
      recordFirst(0);
      Files.createDirectories(root.resolve("operations/not-an-operation"));
      Files.createDirectories(root.resolve("operations/0BE40000-0000-4000-8000-000000000001"));
      assertThat(journals(store(root)).list()).containsExactly(PLAN.id());
      assertJournalCode(
          () -> journals(store(root)).inspect(DomainFixtures.operationId(5)),
          JournalIssueCodes.NOT_FOUND);
    }
  }

  @Nested
  class Appending {
    @Test
    void onlyStartEventsCarryAPermit() throws Exception {
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal = journals(store).create(writer, PLAN);
        JournalAck backup = journal.append(1, EVENTS.get(0));
        assertThat(backup.permit()).isNull();
        assertThat(backup.stateAfter()).isEqualTo(OperationState.BACKED_UP);
        journal.append(2, EVENTS.get(1));
        JournalAck step = journal.append(3, EVENTS.get(2));
        MutationPermit permit = step.permit();
        assertThat(permit).isNotNull();
        assertThat(permit.kind()).isEqualTo(MutationPermit.Kind.STEP);
        assertThat(permit.step()).isZero();
        assertThat(permit.attempt()).isEqualTo(1);
        assertThat(permit.sequence()).isEqualTo(3);
        assertThat(permit.operation()).isEqualTo(PLAN.id());
        appendAll(journal, EVENTS.subList(3, 7));
        JournalAck rollback = journal.append(8, EVENTS.get(7));
        assertThat(rollback.permit()).isNotNull();
        assertThat(rollback.permit().kind()).isEqualTo(MutationPermit.Kind.ROLLBACK);
        assertThat(rollback.permit().step()).isNull();
      }
    }

    @Test
    void resendingTheLastEventIsIdempotentAndGrantsNoSecondPermit() throws Exception {
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal =
            appendAll(journals(store).create(writer, PLAN), EVENTS.subList(0, 2));
        JournalAck first = journal.append(3, EVENTS.get(2));
        long size = Files.size(journalFile(root, PLAN));
        JournalAck again = journal.append(3, EVENTS.get(2));
        assertThat(again.alreadyRecorded()).isTrue();
        assertThat(again.permit()).isNull();
        assertThat(again.entryChecksum()).isEqualTo(first.entryChecksum());
        assertThat(Files.size(journalFile(root, PLAN))).isEqualTo(size);
      }
      assertThat(journalLines()).hasSize(4);
    }

    @Test
    void duplicateReorderedAndMissingAppendsAreRejectedWithoutWriting() throws Exception {
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal =
            appendAll(journals(store).create(writer, PLAN), EVENTS.subList(0, 3));
        byte[] before = Files.readAllBytes(journalFile(root, PLAN));
        // A different event at an already used sequence number.
        assertJournalCode(
            () -> journal.append(3, EVENTS.get(3)), JournalIssueCodes.SEQUENCE_CONFLICT);
        // An older event again at its old sequence number.
        assertJournalCode(
            () -> journal.append(1, EVENTS.get(0)), JournalIssueCodes.SEQUENCE_CONFLICT);
        // Skipping ahead.
        assertJournalCode(
            () -> journal.append(5, EVENTS.get(4)), JournalIssueCodes.SEQUENCE_CONFLICT);
        // The right sequence number but an out-of-order event.
        assertThatThrownBy(() -> journal.append(4, new JournalEvent.StepCompleted(1)))
            .isInstanceOfSatisfying(
                JournalException.class,
                e -> {
                  assertThat(e.code()).isEqualTo(JournalIssueCodes.EVENT_REJECTED);
                  assertThat(e.details().has(OperationIssueCodes.STEP_OUT_OF_ORDER)).isTrue();
                });
        assertThat(Files.readAllBytes(journalFile(root, PLAN))).isEqualTo(before);
        // The handle stays usable after a rejection.
        assertThat(journal.append(4, EVENTS.get(3)).sequence()).isEqualTo(4);
      }
    }

    @Test
    void changedPreconditionsRefuseToApplyAndTheOperationCanBeCancelled() throws Exception {
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal =
            appendAll(journals(store).create(writer, PLAN), EVENTS.subList(0, 1));
        PreconditionSnapshot observed =
            new PreconditionSnapshot(
                new ExpectedManifest.AtRevision(8, OperationFixtures.checksum('a')),
                PLAN.preconditions().checksums(),
                PLAN.preconditions().facts());
        assertThatThrownBy(() -> journal.append(2, new JournalEvent.ApplyStarted(DIGEST, observed)))
            .isInstanceOfSatisfying(
                JournalException.class,
                e ->
                    assertThat(e.details().has(OperationIssueCodes.PRECONDITION_CHANGED)).isTrue());
        assertThatThrownBy(
                () ->
                    journal.append(
                        2,
                        new JournalEvent.ApplyStarted(
                            OperationFixtures.checksum('e'), PLAN.preconditions())))
            .isInstanceOfSatisfying(
                JournalException.class,
                e -> assertThat(e.details().has(OperationIssueCodes.PLAN_CHANGED)).isTrue());
        journal.append(2, new JournalEvent.Cancelled(failure("The manifest moved to revision 8")));
      }
      OperationStatus status = inspect();
      assertThat(status.progress().state()).isEqualTo(OperationState.CANCELLED);
      assertThat(status.recommendation().action()).isEqualTo(RecoveryAction.NONE);
    }

    @Test
    void appendingRequiresTheLock() throws Exception {
      SidecarStore store = store(root);
      OperationJournal journal;
      try (SidecarWriter writer = writer(store)) {
        journal = journals(store).create(writer, PLAN);
      }
      OperationJournal closed = journal;
      assertJournalCode(() -> closed.append(1, EVENTS.get(0)), StorageIssueCodes.LOCK_LOST);
      assertThat(journalLines()).hasSize(1);
    }

    @Test
    void aSecondHandleNoticesTheFirstOnesAppends() throws Exception {
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal a = journals(store).create(writer, PLAN);
        OperationJournal b = journals(store).open(writer, PLAN.id());
        a.append(1, EVENTS.get(0));
        // b's sequence and event match its own (outdated) view, but the file grew under it.
        assertJournalCode(() -> b.append(1, EVENTS.get(0)), JournalIssueCodes.HANDLE_STALE);
        assertJournalCode(() -> b.append(2, EVENTS.get(1)), JournalIssueCodes.HANDLE_BROKEN);
        a.append(2, EVENTS.get(1));
      }
      assertThat(inspect().integrity()).isEqualTo(JournalIntegrity.INTACT);
      assertThat(journalLines()).hasSize(3);
    }

    @Test
    void aFailedSyncBreaksTheHandleAndReopeningShowsTheDurableState() throws Exception {
      JournalFaultFileSystem fs = new JournalFaultFileSystem();
      SidecarStore store = store(root, fs);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal =
            appendAll(journals(store).create(writer, PLAN), EVENTS.subList(0, 2));
        fs.failNextAppendSync = true;
        assertThatThrownBy(() -> journal.append(3, EVENTS.get(2))).isInstanceOf(IOException.class);
        assertJournalCode(() -> journal.append(3, EVENTS.get(2)), JournalIssueCodes.HANDLE_BROKEN);
        // The bytes reached the file although the sync failed: the caller had no ack and did not
        // start the step, but the durable state conservatively says it may have started.
        OperationJournal reopened = journals(store).open(writer, PLAN.id());
        assertThat(reopened.progress().stepInProgress()).isZero();
        assertThat(reopened.progress().recommend().action())
            .isEqualTo(RecoveryAction.FAIL_AND_ROLLBACK);
      }
    }
  }

  @Nested
  class Integrity {
    @Test
    void anInterruptedAppendLeavesATornTailThatIsNeverTrusted() throws Exception {
      JournalFaultFileSystem fs = new JournalFaultFileSystem();
      SidecarStore store = store(root, fs);
      try (SidecarWriter writer = writer(store)) {
        OperationJournal journal =
            appendAll(journals(store).create(writer, PLAN), EVENTS.subList(0, 2));
        fs.tearNextAppendAfter = 40;
        assertThatThrownBy(() -> journal.append(3, EVENTS.get(2)))
            .isInstanceOf(JournalFaultFileSystem.Crash.class);
      }
      OperationStatus status = inspect();
      assertThat(status.integrity()).isEqualTo(JournalIntegrity.TORN_TAIL);
      assertThat(status.trustedEntries()).isEqualTo(3);
      assertThat(status.completeLines()).isEqualTo(3);
      assertThat(status.writable()).isFalse();
      assertThat(status.issues().has(JournalIssueCodes.TORN_TAIL)).isTrue();
      // The torn STEP_STARTED was never acknowledged, so nothing started: resume normally.
      assertThat(status.progress().state()).isEqualTo(OperationState.APPLYING);
      assertThat(status.recommendation().action()).isEqualTo(RecoveryAction.RESUME_APPLY);
      assertThat(status.recommendation().nextStep()).isZero();
      assertThat(status.recommendation().explanation()).contains("torn");
    }

    @Test
    void discardingATornTailPreservesItAndRestoresAppending() throws Exception {
      recordFirst(4);
      byte[] intact = Files.readAllBytes(journalFile(root, PLAN));
      byte[] torn = "{\"checksum\":\"sha256:12".getBytes(StandardCharsets.UTF_8);
      Files.write(journalFile(root, PLAN), torn, StandardOpenOption.APPEND);
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        assertThatThrownBy(() -> journals(store).open(writer, PLAN.id()))
            .isInstanceOfSatisfying(
                JournalException.class,
                e -> {
                  assertThat(e.code()).isEqualTo(JournalIssueCodes.NOT_WRITABLE);
                  assertThat(e.status().integrity()).isEqualTo(JournalIntegrity.TORN_TAIL);
                });
        OperationStatus after = journals(store).discardTornTail(writer, PLAN.id());
        assertThat(after.integrity()).isEqualTo(JournalIntegrity.INTACT);
        assertJournalCode(
            () -> journals(store).discardTornTail(writer, PLAN.id()),
            JournalIssueCodes.NOT_WRITABLE);
        appendAll(journals(store).open(writer, PLAN.id()), EVENTS.subList(4, 5));
      }
      try (Stream<Path> files = Files.list(journalFile(root, PLAN).getParent())) {
        List<Path> preserved =
            files.filter(f -> f.getFileName().toString().startsWith("journal.torn-5-")).toList();
        assertThat(preserved).hasSize(1);
        assertThat(Files.readAllBytes(preserved.get(0))).isEqualTo(torn);
      }
      byte[] now = Files.readAllBytes(journalFile(root, PLAN));
      assertThat(Arrays.copyOf(now, intact.length)).isEqualTo(intact);
    }

    @Test
    void corruptionOfTheFinalCompleteEntryIsNotMistakenForATornWrite() throws Exception {
      recordFirst(3);
      List<String> lines = new ArrayList<>(journalLines());
      int last = lines.size() - 1;
      lines.set(last, lines.get(last).replace("\"attempt\":1", "\"attempt\":2"));
      writeJournal(lines, "");
      OperationStatus status = inspect();
      assertThat(status.integrity()).isEqualTo(JournalIntegrity.CORRUPT);
      assertThat(status.corruptionInMiddle()).isFalse();
      assertThat(status.trustedEntries()).isEqualTo(3);
      assertThat(status.recommendation().action()).isEqualTo(RecoveryAction.MANUAL_INSPECTION);
      assertThat(status.recommendation().lastDurableState()).isEqualTo(OperationState.APPLYING);
      assertThat(status.recommendation().partialEffectsPossible()).isTrue();
      assertThat(status.issues().has(JournalIssueCodes.CORRUPT_ENTRY)).isTrue();
    }

    @Test
    void corruptionInTheMiddleDistrustsEverythingAfterIt() throws Exception {
      recordFirst(EVENTS.size());
      List<String> lines = new ArrayList<>(journalLines());
      lines.set(4, lines.get(4).replace("STEP_COMPLETED", "STEP_COMPLETEX"));
      writeJournal(lines, "");
      OperationStatus status = inspect();
      assertThat(status.integrity()).isEqualTo(JournalIntegrity.CORRUPT);
      assertThat(status.corruptionInMiddle()).isTrue();
      assertThat(status.trustedEntries()).isEqualTo(4);
      assertThat(status.completeLines()).isEqualTo(12);
      assertThat(status.recommendation().action()).isEqualTo(RecoveryAction.MANUAL_INSPECTION);
      assertThat(status.writable()).isFalse();
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        assertJournalCode(
            () -> journals(store).discardTornTail(writer, PLAN.id()),
            JournalIssueCodes.NOT_WRITABLE);
      }
    }

    @Test
    void removedSwappedAndForeignEntriesAreCorruption() throws Exception {
      recordFirst(6);
      List<String> original = journalLines();

      List<String> removed = new ArrayList<>(original);
      removed.remove(3);
      writeJournal(removed, "");
      assertThat(inspect().issues().issues().get(0).message()).contains("chain_broken");

      List<String> swapped = new ArrayList<>(original);
      swapped.set(3, original.get(4));
      swapped.set(4, original.get(3));
      writeJournal(swapped, "");
      assertThat(inspect().integrity()).isEqualTo(JournalIntegrity.CORRUPT);
      assertThat(inspect().trustedEntries()).isEqualTo(3);

      List<String> duplicated = new ArrayList<>(original);
      duplicated.add(original.get(original.size() - 1));
      writeJournal(duplicated, "");
      assertThat(inspect().integrity()).isEqualTo(JournalIntegrity.CORRUPT);
      assertThat(inspect().trustedEntries()).isEqualTo(original.size());

      writeJournal(List.of(original.get(0), "", original.get(1)), "");
      assertThat(inspect().trustedEntries()).isEqualTo(1);
    }

    @Test
    void aChangedOrMissingPlanIsDetected() throws Exception {
      recordFirst(2);
      byte[] plan = Files.readAllBytes(planFile(root, PLAN));
      String edited =
          new String(plan, StandardCharsets.UTF_8).replace("Reopen every", "Reopen some");
      Files.writeString(planFile(root, PLAN), edited);
      OperationStatus changed = inspect();
      assertThat(changed.integrity()).isEqualTo(JournalIntegrity.PLAN_CHANGED);
      assertThat(changed.recommendation().action()).isEqualTo(RecoveryAction.MANUAL_INSPECTION);
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        assertJournalCode(
            () -> journals(store).open(writer, PLAN.id()), JournalIssueCodes.NOT_WRITABLE);
      }
      Files.delete(planFile(root, PLAN));
      assertThat(inspect().integrity()).isEqualTo(JournalIntegrity.PLAN_UNREADABLE);
      Files.writeString(planFile(root, PLAN), "{}");
      assertThat(inspect().integrity()).isEqualTo(JournalIntegrity.PLAN_UNREADABLE);
    }

    @Test
    void entriesFromANewerMinorVersionAreReadOnly() throws Exception {
      recordFirst(1);
      List<String> lines = new ArrayList<>(journalLines());
      // Rewrite both entries as a 1.1 writer would; the chain must follow the new checksums.
      lines.set(0, JournalTestSupport.rewritten(lines.get(0), "1.1"));
      writeJournal(List.of(lines.get(0)), "");
      OperationStatus status = inspect();
      assertThat(status.integrity()).isEqualTo(JournalIntegrity.INTACT);
      assertThat(status.writable()).isFalse();
      assertThat(status.issues().has(JournalIssueCodes.NEWER_MINOR_VERSION)).isTrue();
      assertThat(status.recommendation().action()).isEqualTo(RecoveryAction.CONFIRM_BACKUP);
      SidecarStore store = store(root);
      try (SidecarWriter writer = writer(store)) {
        assertJournalCode(
            () -> journals(store).open(writer, PLAN.id()), JournalIssueCodes.NOT_WRITABLE);
      }
    }

    @Test
    void anOversizedJournalIsNotRead() throws Exception {
      recordFirst(0);
      byte[] huge = new byte[(int) OperationPlanLimits.MAX_JOURNAL_BYTES + 1];
      Files.write(journalFile(root, PLAN), huge);
      OperationStatus status = inspect();
      assertThat(status.integrity()).isEqualTo(JournalIntegrity.CORRUPT);
      assertThat(status.issues().has(JournalIssueCodes.TOO_LARGE)).isTrue();
    }

    @Test
    void inspectionWritesNothing() throws Exception {
      recordFirst(5);
      Files.write(
          journalFile(root, PLAN),
          "{\"torn".getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.APPEND);
      JournalFaultFileSystem fs = new JournalFaultFileSystem();
      OperationJournals journals = journals(store(root, fs));
      byte[] before = Files.readAllBytes(journalFile(root, PLAN));
      journals.list();
      journals.inspect(PLAN.id());
      assertThat(fs.mutations.get()).isZero();
      assertThat(Files.readAllBytes(journalFile(root, PLAN))).isEqualTo(before);
    }
  }

  /** Every durable prefix of the golden history: 1 to 12 entries. */
  static Stream<Integer> everyDurablePrefix() {
    return IntStream.rangeClosed(0, EVENTS.size()).boxed();
  }

  @ParameterizedTest(name = "after {0} events")
  @MethodSource("everyDurablePrefix")
  void resumeDecisionFromDiskMatchesTheDomain(int count) throws Exception {
    recordFirst(count);
    // A fresh store, as after a restart.
    OperationStatus status = inspect();
    OperationProgress expected = OperationProgress.planned(PLAN, DIGEST);
    for (JournalEvent event : EVENTS.subList(0, count)) {
      expected = expected.apply(event);
    }
    assertThat(status.integrity()).isEqualTo(JournalIntegrity.INTACT);
    assertThat(status.trustedEntries()).isEqualTo(count + 1);
    assertThat(status.progress().state()).isEqualTo(expected.state());
    assertThat(status.recommendation()).isEqualTo(expected.recommend());
    assertThat(status.recommendation().requiresConfirmation())
        .isEqualTo(!expected.state().isTerminal());
  }

  @Test
  void rollbackFailureAndSuccessAreBothKeptInTheHistory() throws Exception {
    recordFirst(9);
    assertThat(inspect().progress().state()).isEqualTo(OperationState.ROLLBACK_FAILED);
    assertThat(inspect().recommendation().action()).isEqualTo(RecoveryAction.RETRY_ROLLBACK);
    SidecarStore store = store(root);
    try (SidecarWriter writer = writer(store)) {
      appendAll(journals(store).open(writer, PLAN.id()), EVENTS.subList(9, 11));
    }
    List<String> lines = journalLines();
    assertThat(lines).hasSize(12);
    assertThat(lines.get(7)).contains("OPERATION_FAILED");
    assertThat(lines.get(9)).contains("ROLLBACK_FAILED");
    assertThat(lines.get(11)).contains("ROLLBACK_COMPLETED");
  }

  @Test
  void theStoreWritesTheGoldenJournalByteForByte() throws Exception {
    // Entry n is stamped instant(100 + n), as in the golden file.
    Clock stepping =
        new Clock() {
          private long next = 100;

          @Override
          public Instant instant() {
            return DomainFixtures.instant(next++);
          }

          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
          }
        };
    SidecarStore store = store(root);
    try (SidecarWriter writer = writer(store)) {
      appendAll(OperationJournals.of(store, stepping).create(writer, PLAN), EVENTS);
    }
    assertThat(Files.readAllBytes(journalFile(root, PLAN)))
        .isEqualTo(ManifestFixtures.resource(OperationFixtures.GOLDEN_JOURNAL_V1));
  }

  @Test
  void concurrentAppendersAreSerializedByTheWriterLock() throws Exception {
    recordFirst(0);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> results = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      results.add(
          pool.submit(
              () -> {
                start.await();
                int contended = 0;
                while (true) {
                  SidecarStore store = store(root);
                  SidecarWriter writer;
                  try {
                    writer = writer(store);
                  } catch (LockException e) {
                    assertThat(e.code()).isEqualTo(StorageIssueCodes.LOCK_CONTENDED);
                    contended++;
                    Thread.onSpinWait();
                    continue;
                  }
                  try (writer) {
                    OperationJournal journal = journals(store).open(writer, PLAN.id());
                    long next = journal.progress().nextSequence();
                    journal.append(next, EVENTS.get((int) next - 1));
                    return contended;
                  }
                }
              }));
    }
    start.countDown();
    for (Future<Integer> result : results) {
      result.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();
    OperationStatus status = inspect();
    assertThat(status.integrity()).isEqualTo(JournalIntegrity.INTACT);
    assertThat(status.trustedEntries()).isEqualTo(threads + 1);
    assertThat(journalLines()).hasSize(threads + 1);
  }
}
