// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every sidecar state and every action blocker, and that inspection never writes. */
class ToolSessionTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);
  private static final LockOwner OWNER = new LockOwner("mconeworld-tool-test", "0");

  @TempDir Path temp;

  private ToolLocations world(int dataVersion) {
    Path dir =
        SyntheticWorld.modern(temp.resolve("server/world"))
            .levelDat(dataVersion)
            .seed(1L)
            .dimension(DimensionKey.OVERWORLD)
            .dimension(DimensionKey.THE_END)
            .sessionLock()
            .dir();
    return ToolLocations.withDefaultSidecar(dir);
  }

  private static SidecarWriter writer(Path sidecar) throws Exception {
    return SidecarStore.open(SidecarRoot.open(sidecar))
        .openWriter(OWNER, CLOCK, StaleLockPolicy.REFUSE);
  }

  /** A sidecar with a manifest and an empty chunk index. */
  private static void initialized(Path sidecar) throws Exception {
    Files.createDirectories(sidecar);
    try (SidecarWriter writer = writer(sidecar)) {
      writer
          .begin(null)
          .commit(ManifestDocument.create(DomainFixtures.archiveId(1), DomainFixtures.EPOCH));
      ChunkIndexStore.of(writer.store()).create(writer).close();
    }
  }

  private static SessionStatus inspect(ToolLocations locations) throws Exception {
    return ToolSession.inspect(locations, CLOCK);
  }

  private static List<IssueCode> blockers(SessionStatus status, SessionAction action) {
    return status.action(action).blockers().stream().map(ValidationIssue::code).toList();
  }

  @Test
  void theDefaultSidecarSitsBesideTheWorld() {
    ToolLocations locations = world(4903);
    assertThat(locations.sidecarDir())
        .isEqualTo(temp.resolve("server/mconeworld-data").toAbsolutePath());
    assertThat(locations.overlap()).isEmpty();
  }

  @Test
  void anAbsentOrEmptySidecarCanBeInitializedAndNothingElse() throws Exception {
    ToolLocations locations = world(4903);
    SessionStatus absent = inspect(locations);
    assertThat(absent.sidecar().state()).isEqualTo(SidecarState.ABSENT);
    assertThat(absent.action(SessionAction.INITIALIZE_SIDECAR).available()).isTrue();
    assertThat(blockers(absent, SessionAction.RECONCILE))
        .contains(SessionIssueCodes.SIDECAR_UNREADABLE);
    assertThat(Files.exists(locations.sidecarDir())).as("inspection creates nothing").isFalse();

    Files.createDirectories(locations.sidecarDir());
    SessionStatus empty = inspect(locations);
    assertThat(empty.sidecar().state()).isEqualTo(SidecarState.EMPTY);
    assertThat(empty.action(SessionAction.INITIALIZE_SIDECAR).available()).isTrue();
  }

  @Test
  void filesWithoutAManifestAreNeverInitializedOver() throws Exception {
    ToolLocations locations = world(4903);
    Files.createDirectories(locations.sidecarDir());
    Files.writeString(locations.sidecarDir().resolve("notes.txt"), "hello");
    SessionStatus status = inspect(locations);
    assertThat(status.sidecar().state()).isEqualTo(SidecarState.INCOMPLETE);
    assertThat(status.action(SessionAction.INITIALIZE_SIDECAR).available()).isFalse();
    assertThat(status.findings().has(SessionIssueCodes.SIDECAR_INCOMPLETE)).isTrue();
  }

  @Test
  void aHealthySidecarOnAPinnedWorldEnablesTheWorkflows() throws Exception {
    ToolLocations locations = world(4903);
    initialized(locations.sidecarDir());
    SessionStatus status = inspect(locations);
    assertThat(status.sidecar().state()).isEqualTo(SidecarState.PRESENT);
    assertThat(status.findings().issues()).isEmpty();
    for (SessionAction action :
        List.of(SessionAction.RECONCILE, SessionAction.BACKUP, SessionAction.NEW_SEASON)) {
      assertThat(status.action(action).available()).as(action.name()).isTrue();
    }
    assertThat(blockers(status, SessionAction.INITIALIZE_SIDECAR))
        .contains(SessionIssueCodes.SIDECAR_INCOMPLETE);
    assertThat(blockers(status, SessionAction.RECOVER_SIDECAR))
        .contains(SessionIssueCodes.RECOVERY_NEEDED);
    assertThat(blockers(status, SessionAction.MIGRATE_MANIFEST))
        .contains(SessionIssueCodes.MIGRATION_NEEDED);
  }

  @Test
  void anInspectOnlyWorldCanBeBackedUpButGetsNoNewSeason() throws Exception {
    ToolLocations locations = world(4786);
    initialized(locations.sidecarDir());
    SessionStatus status = inspect(locations);
    assertThat(status.action(SessionAction.BACKUP).available()).isTrue();
    assertThat(blockers(status, SessionAction.NEW_SEASON))
        .containsExactly(WorldIssueCodes.VERSION_NOT_PINNED);
  }

  @Test
  void aRunningServerBlocksEveryWorldDependentAction() throws Exception {
    ToolLocations locations = world(4903);
    initialized(locations.sidecarDir());
    try (FileChannel channel =
            FileChannel.open(
                locations.worldDir().resolve("session.lock"), StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      assertThat(lock.isValid()).isTrue();
      SessionStatus status = inspect(locations);
      for (SessionAction action :
          List.of(SessionAction.RECONCILE, SessionAction.BACKUP, SessionAction.NEW_SEASON)) {
        assertThat(blockers(status, action))
            .as(action.name())
            .contains(SessionIssueCodes.SERVER_RUNNING);
      }
    }
  }

  @Test
  void aHeldSidecarLockBlocksEveryAction() throws Exception {
    ToolLocations locations = world(4903);
    initialized(locations.sidecarDir());
    try (SidecarWriter plugin = writer(locations.sidecarDir())) {
      assertThat(plugin).isNotNull();
      SessionStatus status = inspect(locations);
      assertThat(status.sidecar().lockedByAnotherProcess()).isTrue();
      for (SessionAction action : SessionAction.values()) {
        assertThat(blockers(status, action))
            .as(action.name())
            .contains(SessionIssueCodes.SIDECAR_LOCKED);
      }
    }
  }

  @Test
  void leftoversAMissingIndexAndUnfinishedOperationsBlockTheWorkflows() throws Exception {
    ToolLocations locations = world(4903);
    Files.createDirectories(locations.sidecarDir());
    try (SidecarWriter writer = writer(locations.sidecarDir())) {
      writer
          .begin(null)
          .commit(ManifestDocument.create(DomainFixtures.archiveId(1), DomainFixtures.EPOCH));
      OperationJournals.of(writer.store(), CLOCK).create(writer, OperationFixtures.fullPlan());
    }
    Files.createDirectories(
        locations.sidecarDir().resolve(SidecarRoot.STAGING).resolve("not-a-transaction"));
    SessionStatus status = inspect(locations);
    assertThat(blockers(status, SessionAction.RECONCILE))
        .contains(
            SessionIssueCodes.RECOVERY_NEEDED,
            SessionIssueCodes.INDEX_UNUSABLE,
            SessionIssueCodes.OPERATION_UNFINISHED);
    assertThat(status.action(SessionAction.RECOVER_SIDECAR).available()).isTrue();
    assertThat(status.sidecar().unfinishedOperations()).hasSize(1);
  }

  @Test
  void anUnreadableManifestIsReportedAndBlocksEverything() throws Exception {
    ToolLocations locations = world(4903);
    Files.createDirectories(locations.sidecarDir());
    Files.writeString(
        locations.sidecarDir().resolve("manifest.json"), "{\"format\":\"something-else\"}");
    SessionStatus status = inspect(locations);
    assertThat(status.sidecar().state()).isEqualTo(SidecarState.UNREADABLE);
    assertThat(status.findings().has(SessionIssueCodes.SIDECAR_UNREADABLE)).isTrue();
    assertThat(status.action(SessionAction.MIGRATE_MANIFEST).available()).isFalse();
    assertThat(status.action(SessionAction.BACKUP).available()).isFalse();
  }

  @Test
  void aSidecarInsideTheWorldIsRefused() throws Exception {
    ToolLocations base = world(4903);
    ToolLocations inside =
        new ToolLocations(base.worldDir(), base.worldDir().resolve("mconeworld-data"));
    SessionStatus status = inspect(inside);
    assertThat(status.findings().has(SessionIssueCodes.PATHS_OVERLAP)).isTrue();
    assertThat(status.action(SessionAction.INITIALIZE_SIDECAR).available()).isFalse();
    ToolLocations around = new ToolLocations(base.worldDir(), base.worldDir().getParent());
    assertThat(around.overlap()).isPresent();
  }

  @Test
  void inspectionWritesNothing() throws Exception {
    ToolLocations locations = world(4903);
    initialized(locations.sidecarDir());
    Map<String, Long> before = snapshot(temp);
    inspect(locations);
    inspect(locations);
    assertThat(snapshot(temp)).isEqualTo(before);
  }

  private static Map<String, Long> snapshot(Path root) throws Exception {
    Map<String, Long> out = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : files.toList()) {
        out.put(
            root.relativize(p).toString(),
            Files.isRegularFile(p)
                ? Files.size(p) + 31 * Files.getLastModifiedTime(p).toMillis()
                : -1);
      }
    }
    return out;
  }
}
