// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupIssueCodes;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupVerification;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import io.github.kjmorse2.mconeworld.tool.core.init.InitPreview;
import io.github.kjmorse2.mconeworld.tool.core.init.SidecarInitializer;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Full backups: a faithful verified copy, every refusal, cancellation, and resumption after a
 * crash.
 */
class BackupServiceTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
  private static final DimensionKey O = DimensionKey.OVERWORLD;
  private static final BackupService.SpaceProbe PLENTY = dir -> Long.MAX_VALUE;

  @TempDir Path temp;
  private Path world;
  private InitPreview init;

  private ToolLocations initialized(String name) throws IOException {
    if (world == null) {
      List<ChunkPosition> chunks = new ArrayList<>();
      for (int x = -2; x < 2; x++) {
        for (int z = -2; z < 2; z++) {
          chunks.add(new ChunkPosition(O, x, z));
        }
      }
      world =
          SyntheticWorld.modern(temp.resolve("server/world"))
              .levelDat(4903)
              .seed(7L)
              .sessionLock()
              .chunks(O, "region", chunks, 1_790_000_000L)
              .file(
                  "datapacks/afk display v1.1.15 (MC 1.21-1.21.11).zip/pack.mcmeta",
                  "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
              .file("players/data/5cd87f22-1adf-47f2-abf3-04a41dae96ee.dat", new byte[] {1, 2, 3})
              .file("dimensions/minecraft/overworld/data/DistantHorizons.sqlite", new byte[5000])
              .dir();
    }
    ToolLocations locations = new ToolLocations(world, temp.resolve("server").resolve(name));
    SidecarInitializer initializer = new SidecarInitializer(CLOCK, ProgressListener.NONE);
    if (init == null) {
      init = initializer.preview(locations);
    }
    initializer.apply(new InitPreview(locations, init.plan(), init.inventory()));
    return locations;
  }

  private static BackupService service(OperationRunner.Hook hook, BackupService.SpaceProbe space) {
    return new BackupService(CLOCK, ProgressListener.NONE, hook, space);
  }

  private static BackupService service() {
    return service(OperationRunner.Hook.NONE, PLENTY);
  }

  private static Path payload(ToolLocations l, BackupId id, String entry) {
    return l.sidecarDir().resolve("backups").resolve(id.toString()).resolve(entry);
  }

  @Test
  void previewListsWhatWillBeCopiedAndWritesNothing() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    BackupPreview preview = service().preview(l);
    // level.dat, the seed file, 4 region files (the chunks span 4 regions), 3 others; not
    // session.lock
    assertThat(preview.worldFiles()).isEqualTo(9);
    assertThat(preview.sidecarFiles()).isPositive();
    assertThat(preview.requiredBytes()).isGreaterThan(preview.totalBytes());
    assertThat(Files.exists(l.sidecarDir().resolve("backups"))).isFalse();
  }

  @Test
  void aBackupIsAFaithfulVerifiedCopyOfTheWorldAndTheSidecar() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    BackupPreview preview = service().preview(l);
    BackupRecord record = service().apply(preview);
    BackupId id = BackupService.backupOf(preview.plan().id());
    assertThat(record.id()).isEqualTo(id);
    assertThat(record.operation()).isEqualTo(preview.plan().id());
    assertThat(BackupService.verify(l, id).verified()).isTrue();

    try (Stream<Path> files = Files.walk(world)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String relative = world.relativize(file).toString();
        Path copy = payload(l, id, "world/" + relative);
        if (relative.equals("session.lock")) {
          assertThat(copy).doesNotExist();
        } else {
          assertThat(Files.readAllBytes(copy)).as(relative).isEqualTo(Files.readAllBytes(file));
        }
      }
    }
    assertThat(payload(l, id, "sidecar/manifest.json")).exists();
    assertThat(payload(l, id, "sidecar/chunk-index/catalog")).exists();
    assertThat(payload(l, id, "sidecar/writer.lock")).doesNotExist();
    assertThat(payload(l, id, "sidecar/operations/" + preview.plan().id())).doesNotExist();
    assertThat(ToolSession.inspect(l, CLOCK).findings().issues()).isEmpty();
    assertThat(RestoreInstructions.forFullBackup(l, id))
        .hasSize(9)
        .anyMatch(s -> s.contains("before-restore"));
  }

  @Test
  void laterDamageIsFoundByVerify() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    BackupRecord record = service().apply(service().preview(l));
    Files.writeString(payload(l, record.id(), "world/level.dat"), "damaged");
    BackupVerification verification = BackupService.verify(l, record.id());
    assertThat(verification.verified()).isFalse();
    assertThat(verification.issues().has(BackupIssueCodes.ENTRY_CHANGED)).isTrue();
  }

  @Test
  void tooLittleSpaceASymlinkOrARunningServerRefuseTheBackup() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    assertThatThrownBy(() -> service(OperationRunner.Hook.NONE, dir -> 1000).preview(l))
        .isInstanceOfSatisfying(
            ToolException.class,
            e ->
                assertThat(e.code())
                    .isEqualTo(
                        io.github.kjmorse2.mconeworld.tool.core.backup.BackupIssueCodes
                            .INSUFFICIENT_SPACE));
    Files.createSymbolicLink(world.resolve("link"), world.resolve("level.dat"));
    assertThatThrownBy(() -> service().preview(l))
        .isInstanceOfSatisfying(
            ToolException.class,
            e ->
                assertThat(e.code())
                    .isEqualTo(
                        io.github.kjmorse2.mconeworld.tool.core.backup.BackupIssueCodes
                            .UNSUPPORTED_FILE));
  }

  @Test
  void aWorldChangedAfterPlanningCancelsTheBackup() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    BackupPreview preview = service().preview(l);
    Files.write(world.resolve("players/data/new.dat"), new byte[] {9});
    assertThatThrownBy(() -> service().apply(preview))
        .isInstanceOfSatisfying(
            ToolException.class,
            e ->
                assertThat(e.code())
                    .isEqualTo(
                        io.github.kjmorse2.mconeworld.tool.core.backup.BackupIssueCodes.CHANGED));
    assertThat(Files.exists(l.sidecarDir().resolve("backups"))).isFalse();
  }

  @Test
  void cancellingMidCopyLeavesAResumableOperation() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    BackupPreview preview = service().preview(l);
    BackupService cancelling =
        new BackupService(
            CLOCK,
            (phase, done, total) -> {
              if (done > 0) {
                Thread.currentThread().interrupt();
              }
            },
            OperationRunner.Hook.NONE,
            PLENTY);
    try {
      assertThatThrownBy(() -> cancelling.apply(preview))
          .isInstanceOf(IOException.class); // InterruptedIOException or ClosedByInterruptException
    } finally {
      Thread.interrupted();
    }
    assertThat(ToolSession.inspect(l, CLOCK).action(SessionAction.RESUME_OPERATION).available())
        .isTrue();
    BackupRecord record = service().resume(l, preview.plan().id());
    assertThat(BackupService.verify(l, record.id()).verified()).isTrue();
  }

  static Stream<String> crashPoints() {
    return Stream.of(
        "recorded",
        "backup-confirmed",
        "apply-started",
        "step-0-started",
        "copied",
        "verified",
        "step-0-completed",
        "validation-started",
        "check-0-passed");
  }

  /**
   * A crash at {@code point} (once), then an explicit resume, ends with one verified backup
   * recorded for the operation, a faithful copy of the world, and a clean sidecar.
   */
  @ParameterizedTest
  @MethodSource("crashPoints")
  void aCrashAnywhereResumesToAVerifiedBackup(String point) throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    BackupPreview plan = service().preview(l);
    boolean[] fired = {false};
    BackupService crashing =
        service(
            at -> {
              if (at.equals(point) && !fired[0]) {
                fired[0] = true;
                throw new IOException("simulated crash at " + at);
              }
            },
            PLENTY);
    assertThatThrownBy(() -> crashing.apply(plan)).hasMessageContaining("simulated crash");
    assertThat(ToolSession.inspect(l, CLOCK).action(SessionAction.RESUME_OPERATION).available())
        .isTrue();
    BackupRecord record = service().resume(l, plan.plan().id());

    assertThat(record.id()).isEqualTo(BackupService.backupOf(plan.plan().id()));
    assertThat(BackupService.verify(l, record.id()).verified()).isTrue();
    SidecarStore store = SidecarStore.open(SidecarRoot.open(l.sidecarDir()));
    assertThat(store.readManifest().document().state().backups()).containsExactly(record);
    assertThat(Files.readAllBytes(payload(l, record.id(), "world/level.dat")))
        .isEqualTo(Files.readAllBytes(world.resolve("level.dat")));
    assertThat(ToolSession.inspect(l, CLOCK).findings().issues()).isEmpty();
  }

  @Test
  void theStoreSeesTheBackup() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    BackupRecord record = service().apply(service().preview(l));
    SidecarStore store = SidecarStore.open(SidecarRoot.open(l.sidecarDir()));
    assertThat(store.backupFiles().hasManifest(record.id())).isTrue();
    assertThat(store.inspect().findings()).isEmpty();
  }
}
