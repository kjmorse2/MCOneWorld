// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexSnapshot;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationStatus;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionIssueCodes;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionStatus;
import io.github.kjmorse2.mconeworld.tool.core.session.SidecarState;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Initialization end to end on a synthetic world, its refusals, and resumption after a crash at
 * every point.
 */
class SidecarInitializerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
  private static final DimensionKey O = DimensionKey.OVERWORLD;
  private static final DimensionKey N = DimensionKey.THE_NETHER;
  private static final DimensionKey E = DimensionKey.THE_END;
  private static final DimensionKey CUSTOM = DimensionKey.parse("example:sky");

  /** Small batches, so the 70 Overworld chunks span several index commits. */
  private static final int BATCH = 16;

  @TempDir Path temp;

  private Path world;

  private static List<ChunkPosition> square(DimensionKey d, int x0, int z0, int size) {
    List<ChunkPosition> out = new ArrayList<>();
    for (int x = x0; x < x0 + size; x++) {
      for (int z = z0; z < z0 + size; z++) {
        out.add(new ChunkPosition(d, x, z));
      }
    }
    return out;
  }

  private Path world() {
    if (world == null) {
      List<ChunkPosition> overworld = new ArrayList<>(square(O, -4, -4, 8));
      overworld.addAll(List.of(new ChunkPosition(O, 1000, -2000), new ChunkPosition(O, -33, 31)));
      overworld.addAll(square(O, 40, 40, 2));
      world =
          SyntheticWorld.modern(temp.resolve("server/world"))
              .levelDat(4903)
              .seed(-6425566648705237911L)
              .sessionLock()
              .chunks(O, "region", overworld, 1_790_000_000L)
              .chunks(O, "entities", square(O, 0, 0, 2), 1_790_000_000L)
              .chunks(N, "region", square(N, -1, -1, 3), 1_790_000_000L)
              .chunks(E, "region", square(E, -2, -2, 4), 1_790_000_000L)
              .chunks(CUSTOM, "region", List.of(new ChunkPosition(CUSTOM, 5, 5)), 1_790_000_000L)
              .regionFile(O, "region", "r.9.9.mca", new byte[0])
              .dir();
    }
    return world;
  }

  private ToolLocations locations(String sidecar) {
    return new ToolLocations(world(), temp.resolve("server").resolve(sidecar));
  }

  private static SidecarInitializer initializer(OperationRunner.Hook hook) {
    return new SidecarInitializer(CLOCK, ProgressListener.NONE, hook, BATCH);
  }

  @Test
  void theSessionKnowsThisOperationIsResumable() {
    assertThat(ToolSession.RESUMABLE_TYPES).contains(SidecarInitializer.TYPE);
  }

  @Test
  void previewCountsEveryDimensionAndWritesNothing() throws Exception {
    InitPreview preview =
        initializer(OperationRunner.Hook.NONE).preview(locations("mconeworld-data"));
    assertThat(preview.inventory().dimension(O).orElseThrow().regionChunks()).isEqualTo(70);
    assertThat(preview.inventory().dimension(O).orElseThrow().entityChunks()).isEqualTo(4);
    assertThat(preview.inventory().dimension(N).orElseThrow().regionChunks()).isEqualTo(9);
    assertThat(preview.inventory().dimension(E).orElseThrow().regionChunks()).isEqualTo(16);
    assertThat(preview.inventory().dimension(CUSTOM).orElseThrow().regionChunks()).isEqualTo(1);
    assertThat(preview.plan().type()).isEqualTo(SidecarInitializer.TYPE);
    assertThat(preview.plan().steps()).hasSize(3);
    assertThat(preview.plan().targets()).hasSize(5); // the manifest and one area per dimension
    assertThat(Files.exists(locations("mconeworld-data").sidecarDir())).isFalse();
  }

  @Test
  void applyCreatesABoundSidecarWithEveryChunkAsLegacyAndLeavesTheWorldAlone() throws Exception {
    Map<String, Long> worldBefore = snapshot(world());
    ToolLocations locations = locations("mconeworld-data");
    SidecarInitializer init = initializer(OperationRunner.Hook.NONE);
    InitResult result = init.apply(init.preview(locations));

    SessionStatus status = ToolSession.inspect(locations, CLOCK);
    assertThat(status.sidecar().state()).isEqualTo(SidecarState.PRESENT);
    assertThat(status.findings().issues()).isEmpty();
    assertThat(status.action(SessionAction.RECONCILE).available()).isTrue();
    assertThat(status.sidecar().manifest().orElseThrow().document().archiveId())
        .isEqualTo(result.archive());

    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    OperationStatus op = OperationJournals.of(store, CLOCK).inspect(result.operation());
    assertThat(op.progress().state()).isEqualTo(OperationState.COMPLETED);
    try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
      assertThat(reader.state().indexId()).isEqualTo(result.operation().value());
      ChunkProvenance record = reader.find(new ChunkPosition(O, 1000, -2000)).orElseThrow();
      assertThat(record.firstObservation()).isInstanceOf(FirstObservation.Legacy.class);
      assertThat(record.activity()).isEmpty();
      assertThat(reader.find(new ChunkPosition(O, 0, 0)).orElseThrow().firstObservation())
          .isInstanceOf(FirstObservation.Legacy.class);
    }
    assertThat(snapshot(world())).as("the world was not modified").isEqualTo(worldBefore);
  }

  @Test
  void initializationIsRefusedWhileTheServerRunsOrOverAnExistingSidecar() throws Exception {
    ToolLocations locations = locations("mconeworld-data");
    try (FileChannel channel =
            FileChannel.open(world().resolve("session.lock"), StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      assertThat(lock.isValid()).isTrue();
      assertThatThrownBy(() -> initializer(OperationRunner.Hook.NONE).preview(locations))
          .isInstanceOfSatisfying(
              ToolException.class,
              e -> {
                assertThat(e.code()).isEqualTo(InitIssueCodes.NOT_ALLOWED);
                assertThat(e.findings().has(SessionIssueCodes.SERVER_RUNNING)).isTrue();
              });
    }
    SidecarInitializer init = initializer(OperationRunner.Hook.NONE);
    InitPreview preview = init.preview(locations);
    init.apply(preview);
    assertThatThrownBy(() -> init.apply(preview))
        .isInstanceOfSatisfying(
            ToolException.class, e -> assertThat(e.code()).isEqualTo(InitIssueCodes.NOT_ALLOWED));
  }

  @Test
  void aWorldChangedSincePlanningIsRefusedBeforeAnythingIsCreated() throws Exception {
    ToolLocations locations = locations("mconeworld-data");
    SidecarInitializer init = initializer(OperationRunner.Hook.NONE);
    InitPreview preview = init.preview(locations);
    SyntheticWorld.modern(world()).chunks(O, "region", List.of(new ChunkPosition(O, 500, 500)), 1L);
    assertThatThrownBy(() -> init.apply(preview))
        .isInstanceOfSatisfying(
            ToolException.class, e -> assertThat(e.code()).isEqualTo(InitIssueCodes.WORLD_CHANGED));
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    assertThat(store.hasManifest()).isFalse();
    OperationStatus op = OperationJournals.of(store, CLOCK).inspect(preview.plan().id());
    assertThat(op.progress().state()).isEqualTo(OperationState.CANCELLED);
  }

  static Stream<String> crashPoints() {
    return Stream.of(
        "recorded",
        "backup-confirmed",
        "apply-started",
        "step-0-started",
        "step-0-completed",
        "step-1-started",
        "step-1-completed",
        "step-2-started",
        "import-batch",
        "step-2-completed",
        "validation-started",
        "check-0-passed",
        "check-1-passed");
  }

  /**
   * A crash at {@code point} (once), then an explicit resume, ends exactly like an uninterrupted
   * run.
   */
  @ParameterizedTest
  @MethodSource("crashPoints")
  void aCrashAnywhereResumesToTheSameResult(String point) throws Exception {
    InitPreview reference = initializer(OperationRunner.Hook.NONE).preview(locations("reference"));
    InitPreview crashed =
        new InitPreview(locations("crashed"), reference.plan(), reference.inventory());
    initializer(OperationRunner.Hook.NONE).apply(reference);

    boolean[] fired = {false};
    SidecarInitializer crashing =
        initializer(
            at -> {
              if (at.equals(point) && !fired[0]) {
                fired[0] = true;
                throw new IOException("simulated crash at " + at);
              }
            });
    assertThatThrownBy(() -> crashing.apply(crashed)).hasMessageContaining("simulated crash");
    assertThat(fired[0]).isTrue();

    SessionStatus interrupted = ToolSession.inspect(crashed.locations(), CLOCK);
    assertThat(interrupted.action(SessionAction.RESUME_OPERATION).available())
        .as("resumable after %s", point)
        .isTrue();
    assertThat(interrupted.action(SessionAction.INITIALIZE_SIDECAR).available()).isFalse();

    initializer(OperationRunner.Hook.NONE).resume(crashed.locations(), crashed.plan().id());

    assertThat(Files.readAllBytes(crashed.locations().sidecarDir().resolve("manifest.json")))
        .isEqualTo(Files.readAllBytes(reference.locations().sidecarDir().resolve("manifest.json")));
    assertThat(indexDigest(crashed.locations())).isEqualTo(indexDigest(reference.locations()));
    SessionStatus after = ToolSession.inspect(crashed.locations(), CLOCK);
    assertThat(after.findings().issues()).isEmpty();
    assertThat(after.action(SessionAction.RESUME_OPERATION).available()).isFalse();
  }

  @Test
  void resumingACompletedInitializationChangesNothingAndACancelledOneIsRefused() throws Exception {
    ToolLocations locations = locations("mconeworld-data");
    SidecarInitializer init = initializer(OperationRunner.Hook.NONE);
    InitResult done = init.apply(init.preview(locations));
    Map<String, Long> before = snapshot(locations.sidecarDir());
    assertThat(init.resume(locations, done.operation()).archive()).isEqualTo(done.archive());
    assertThat(snapshot(locations.sidecarDir())).isEqualTo(before);

    ToolLocations other = locations("other");
    InitPreview preview = init.preview(other);
    SyntheticWorld.modern(world()).chunks(O, "region", List.of(new ChunkPosition(O, 600, 600)), 1L);
    assertThatThrownBy(() -> init.apply(preview)).isInstanceOf(ToolException.class);
    assertThatThrownBy(() -> init.resume(other, preview.plan().id()))
        .isInstanceOfSatisfying(
            ToolException.class, e -> assertThat(e.code()).isEqualTo(InitIssueCodes.NOT_RESUMABLE));
  }

  private static String indexDigest(ToolLocations locations) throws Exception {
    try (ChunkIndexSnapshot reader =
        ChunkIndexStore.of(SidecarStore.open(SidecarRoot.open(locations.sidecarDir())))
            .openReader()) {
      return ChunkRecordText.digest(reader);
    }
  }

  private static Map<String, Long> snapshot(Path root) throws IOException {
    Map<String, Long> out = new TreeMap<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : files.toList()) {
        out.put(
            root.relativize(p).toString(),
            Files.isRegularFile(p)
                ? Files.size(p) * 31 + Files.getLastModifiedTime(p).toMillis()
                : -1);
      }
    }
    return out;
  }
}
