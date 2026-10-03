// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexSnapshot;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.SidecarChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import io.github.kjmorse2.mconeworld.tool.core.ToolInfo;
import io.github.kjmorse2.mconeworld.tool.core.init.InitPreview;
import io.github.kjmorse2.mconeworld.tool.core.init.SidecarInitializer;
import io.github.kjmorse2.mconeworld.tool.core.inventory.IndexComparison;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldChunks;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionIssueCodes;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionStatus;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspector;
import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Reconcile after external trimming: the diff, the report, protection, binding, and resumption. */
class ReconcilerTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
  private static final DimensionKey O = DimensionKey.OVERWORLD;
  private static final DimensionKey N = DimensionKey.THE_NETHER;
  private static final int BATCH = 8;

  @TempDir Path temp;
  private Path world;
  private InitPreview init;

  private static List<ChunkPosition> square(DimensionKey d, int x0, int z0, int size) {
    List<ChunkPosition> out = new ArrayList<>();
    for (int x = x0; x < x0 + size; x++) {
      for (int z = z0; z < z0 + size; z++) {
        out.add(new ChunkPosition(d, x, z));
      }
    }
    return out;
  }

  /**
   * A world of 4 regions (-1..0 by -1..0, 16 chunks each) plus 9 Nether chunks, with one sidecar
   * initialized for it at {@code name} (every sidecar from the same initialization plan, so all
   * start byte-identical).
   */
  private ToolLocations initialized(String name) throws IOException {
    if (world == null) {
      List<ChunkPosition> overworld = new ArrayList<>();
      for (int rx = -1; rx <= 0; rx++) {
        for (int rz = -1; rz <= 0; rz++) {
          overworld.addAll(square(O, rx * 32, rz * 32, 4));
        }
      }
      world =
          SyntheticWorld.modern(temp.resolve("server/world"))
              .levelDat(4903)
              .seed(42L)
              .sessionLock()
              .chunks(O, "region", overworld, 1_790_000_000L)
              .chunks(N, "region", square(N, 0, 0, 3), 1_790_000_000L)
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

  /**
   * What MCA Selector does: region r.-1.-1 deleted whole, region r.0.0 rewritten with 4 of its
   * chunks.
   */
  private void trim() throws IOException {
    Files.delete(world.resolve("dimensions/minecraft/overworld/region/r.-1.-1.mca"));
    SyntheticWorld.modern(world).chunks(O, "region", square(O, 0, 0, 2), 1_790_000_000L);
  }

  /** Chunks the server generated after the import: four in a new region. */
  private void grow() throws IOException {
    SyntheticWorld.modern(world).chunks(O, "region", square(O, 64, 64, 2), 1_790_000_100L);
  }

  private static Reconciler reconciler(OperationRunner.Hook hook) {
    return new Reconciler(CLOCK, ProgressListener.NONE, hook, BATCH);
  }

  private static void editManifest(ToolLocations l, UnaryOperator<ManifestDocument> edit)
      throws Exception {
    SidecarStore store = SidecarStore.open(SidecarRoot.open(l.sidecarDir()));
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, CLOCK, StaleLockPolicy.REFUSE)) {
      ManifestDocument doc = store.readManifest().document();
      writer.begin(doc.revision()).commit(edit.apply(doc).nextRevision());
    }
  }

  private static boolean matches(ToolLocations l) throws IOException {
    SidecarStore store = SidecarStore.open(SidecarRoot.open(l.sidecarDir()));
    IndexComparison.Counter counter = new IndexComparison.Counter();
    try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
      IndexComparison.compare(
          WorldChunks.collect(WorldInspector.inspect(l.worldDir()), ProgressListener.NONE),
          reader,
          counter);
    }
    return counter.same();
  }

  @Test
  void previewReportsGainedAndLostChunksAndWritesNothing() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    trim();
    grow();
    byte[] manifest = Files.readAllBytes(l.sidecarDir().resolve("manifest.json"));
    ReconcileDiff diff = reconciler(OperationRunner.Hook.NONE).preview(l, false).diff();
    assertThat(diff.dimensions()).containsExactly(new DimensionChanges(O, 4, 28, 0));
    assertThat(diff.removedByFirstSeason())
        .containsExactly(org.assertj.core.api.Assertions.entry("legacy", 28L));
    assertThat(diff.bindsWorld()).isFalse();
    assertThat(Files.readAllBytes(l.sidecarDir().resolve("manifest.json"))).isEqualTo(manifest);
    assertThat(matches(l)).isFalse();
  }

  @Test
  void applyReportsEveryRemovedRecordThenMatchesTheWorld() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    trim();
    grow();
    Reconciler reconciler = reconciler(OperationRunner.Hook.NONE);
    ReconcilePreview preview = reconciler.preview(l, false);
    reconciler.apply(preview);

    assertThat(matches(l)).isTrue();
    SidecarStore store = SidecarStore.open(SidecarRoot.open(l.sidecarDir()));
    ManifestDocument doc = store.readManifest().document();
    assertThat(doc.files())
        .anyMatch(f -> f.path().equals(Reconciler.reportPath(preview.plan().id())));
    List<String> report =
        Files.readAllLines(
            l.sidecarDir().resolve(Reconciler.reportPath(preview.plan().id()).value()));
    assertThat(report).hasSize(29);
    assertThat(report.getFirst())
        .isEqualTo(
            "{\"format\":\"mconeworld-reconcile-report\",\"operation\":\""
                + preview.plan().id()
                + "\",\"removed\":28,\"version\":\"1.0\"}");
    assertThat(report.get(1)).contains("\"x\":-32").contains("\"legacy\":true");
    try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
      assertThat(reader.find(new ChunkPosition(O, 64, 64)).orElseThrow().firstObservation())
          .isInstanceOf(FirstObservation.Legacy.class);
      assertThat(reader.find(new ChunkPosition(O, -32, -32))).isEmpty();
    }
    assertThat(OperationJournals.of(store, CLOCK).inspect(preview.plan().id()).progress().state())
        .isEqualTo(OperationState.COMPLETED);
    assertThat(ToolSession.inspect(l, CLOCK).findings().issues()).isEmpty();

    ReconcilePreview again = reconciler.preview(l, false);
    assertThat(again.diff().nothingToDo()).isTrue();
    assertThatThrownBy(() -> reconciler.apply(again))
        .isInstanceOfSatisfying(
            ToolException.class,
            e -> assertThat(e.code()).isEqualTo(ReconcileIssueCodes.NOTHING_TO_DO));
  }

  @Test
  void chunksGainedDuringASeasonAreAttributedToItWithLowConfidence() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    editManifest(
        l,
        doc ->
            doc.withState(
                doc.state()
                    .appendSeason(DomainFixtures.season(1).build())
                    .activate(DomainFixtures.seasonId(1))));
    grow();
    reconciler(OperationRunner.Hook.NONE)
        .apply(reconciler(OperationRunner.Hook.NONE).preview(l, false));
    try (ChunkIndexSnapshot reader =
        ChunkIndexStore.of(SidecarStore.open(SidecarRoot.open(l.sidecarDir()))).openReader()) {
      FirstObservation first =
          reader.find(new ChunkPosition(O, 65, 65)).orElseThrow().firstObservation();
      assertThat(first).isInstanceOf(FirstObservation.InSeason.class);
      assertThat(((FirstObservation.InSeason) first).season())
          .isEqualTo(DomainFixtures.seasonId(1));
      assertThat(first.evidence().confidence())
          .isEqualTo(io.github.kjmorse2.mconeworld.domain.provenance.Confidence.LOW);
    }
  }

  @Test
  void removingProtectedChunksNeedsAcknowledgement() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    SidecarStore store = SidecarStore.open(SidecarRoot.open(l.sidecarDir()));
    try (SidecarWriter writer =
            store.openWriter(ToolInfo.LOCK_OWNER, CLOCK, StaleLockPolicy.REFUSE);
        SidecarChunkIndex index = ChunkIndexStore.of(store).openWriter(writer);
        var batch = index.begin()) {
      batch.apply(
          new ChunkMutation.SetProtection(
              new ChunkPosition(O, -32, -32), ChunkProtection.PROTECTED));
      batch.commit();
    }
    editManifest(
        l,
        doc ->
            doc.withState(
                doc.state()
                    .withProtectedRegion(
                        new ProtectedRegion(
                            DomainFixtures.protectedRegionId(1),
                            DisplayName.of("Spawn town"),
                            new ChunkRectangle(O, -30, -30, -29, -29),
                            DomainFixtures.instant(1)))));
    trim();
    Reconciler reconciler = reconciler(OperationRunner.Hook.NONE);
    assertThatThrownBy(() -> reconciler.preview(l, false))
        .isInstanceOfSatisfying(
            ToolException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ReconcileIssueCodes.PROTECTED_REMOVAL);
              assertThat(e.findings().issues())
                  .hasSize(5); // one flagged chunk plus four in the region
            });
    ReconcilePreview acknowledged = reconciler.preview(l, true);
    assertThat(acknowledged.diff().protectedRemovedTotal()).isEqualTo(5);
    assertThat(acknowledged.plan().steps().get(1).description().value())
        .contains("removal acknowledged: true");
    reconciler.apply(acknowledged);
    assertThat(matches(l)).isTrue();
  }

  @Test
  void anUnboundSidecarGetsItsWorldBinding() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    editManifest(
        l,
        doc ->
            doc.withState(
                new io.github.kjmorse2.mconeworld.domain.archive.ArchiveState(
                    doc.state().seasons(),
                    doc.state().activeSeasonId(),
                    doc.state().protectedRegions(),
                    doc.state().portalMarkers(),
                    doc.state().backups(),
                    doc.state().playerArchives())));
    assertThat(ToolSession.inspect(l, CLOCK).findings().has(SessionIssueCodes.WORLD_UNBOUND))
        .isTrue();
    ReconcilePreview preview = reconciler(OperationRunner.Hook.NONE).preview(l, false);
    assertThat(preview.diff().bindsWorld()).isTrue();
    assertThat(preview.diff().nothingToDo()).isFalse();
    reconciler(OperationRunner.Hook.NONE).apply(preview);
    assertThat(ToolSession.inspect(l, CLOCK).findings().issues()).isEmpty();
  }

  @Test
  void aWorldChangedAfterPlanningCancelsBeforeAnythingChanges() throws Exception {
    ToolLocations l = initialized("mconeworld-data");
    trim();
    Reconciler reconciler = reconciler(OperationRunner.Hook.NONE);
    ReconcilePreview preview = reconciler.preview(l, false);
    grow();
    byte[] manifest = Files.readAllBytes(l.sidecarDir().resolve("manifest.json"));
    assertThatThrownBy(() -> reconciler.apply(preview))
        .isInstanceOfSatisfying(
            ToolException.class, e -> assertThat(e.code()).isEqualTo(ReconcileIssueCodes.CHANGED));
    assertThat(Files.readAllBytes(l.sidecarDir().resolve("manifest.json"))).isEqualTo(manifest);
    SessionStatus status = ToolSession.inspect(l, CLOCK);
    assertThat(status.action(SessionAction.RECONCILE).available())
        .as("a cancelled operation is finished")
        .isTrue();
  }

  static Stream<String> crashPoints() {
    return Stream.of(
        "recorded",
        "backup-confirmed",
        "apply-started",
        "step-0-started",
        "step-0-completed",
        "step-1-started",
        "index-batch",
        "step-1-completed",
        "validation-started",
        "check-0-passed",
        "check-1-passed");
  }

  @ParameterizedTest
  @MethodSource("crashPoints")
  void aCrashAnywhereResumesToTheSameResult(String point) throws Exception {
    ToolLocations reference = initialized("reference");
    ToolLocations crashed = initialized("crashed");
    trim();
    grow();
    ReconcilePreview plan = reconciler(OperationRunner.Hook.NONE).preview(reference, false);
    reconciler(OperationRunner.Hook.NONE).apply(plan);

    boolean[] fired = {false};
    Reconciler crashing =
        reconciler(
            at -> {
              if (at.equals(point) && !fired[0]) {
                fired[0] = true;
                throw new IOException("simulated crash at " + at);
              }
            });
    ReconcilePreview same = new ReconcilePreview(crashed, plan.plan(), plan.diff());
    assertThatThrownBy(() -> crashing.apply(same)).hasMessageContaining("simulated crash");
    assertThat(
            ToolSession.inspect(crashed, CLOCK).action(SessionAction.RESUME_OPERATION).available())
        .isTrue();
    reconciler(OperationRunner.Hook.NONE).resume(crashed, plan.plan().id());

    for (String file : List.of("manifest.json", Reconciler.reportPath(plan.plan().id()).value())) {
      assertThat(Files.readAllBytes(crashed.sidecarDir().resolve(file)))
          .as(file)
          .isEqualTo(Files.readAllBytes(reference.sidecarDir().resolve(file)));
    }
    assertThat(digest(crashed)).isEqualTo(digest(reference));
    assertThat(ToolSession.inspect(crashed, CLOCK).findings().issues()).isEmpty();
  }

  private static String digest(ToolLocations l) throws Exception {
    try (ChunkIndexSnapshot reader =
        ChunkIndexStore.of(SidecarStore.open(SidecarRoot.open(l.sidecarDir()))).openReader()) {
      return ChunkRecordText.digest(reader);
    }
  }
}
