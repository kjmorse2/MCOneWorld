// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.archiveId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.backupId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.operationId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.overworld;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.playerArchiveId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.playerId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.portalMarkerId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.protectedRegionId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.season;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.seasonId;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveCategory;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.archive.PlayerArchiveRecord;
import io.github.kjmorse2.mconeworld.domain.archive.PortalMarker;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FeatureKey;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FeatureRecord;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.EnumSet;
import java.util.List;

/**
 * Canonical manifest fixtures. {@link #fullDocument()} exercises every v1 member. Its canonical
 * encoding is the golden file {@value #GOLDEN_FULL_V1}, whose schema version and SHA-256 are
 * recorded in {@value #GOLDEN_FULL_V1}{@code .meta}. The same bytes are published as {@code
 * docs/formats/examples/manifest-v1-example.json}.
 *
 * <p>Golden files are immutable: tests copy the bytes before corrupting them. If the format changes
 * intentionally, write the new bytes, review the diff, update the {@code .meta} checksum, and
 * record the change in {@code docs/formats/manifest-v1.md}.
 */
public final class ManifestFixtures {
  /** Classpath resource of the canonical v1 golden manifest. */
  public static final String GOLDEN_FULL_V1 =
      "/io/github/kjmorse2/mconeworld/fixtures/manifest/v1/full.json";

  /** An arbitrary but well-formed checksum value used by fixtures. */
  public static final Checksum CHECKSUM_A =
      Checksum.parse("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

  /** A second, different checksum value. */
  public static final Checksum CHECKSUM_B =
      Checksum.parse("sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

  private ManifestFixtures() {}

  /** A valid document that uses every v1 member, with no warnings. */
  public static ManifestDocument fullDocument() {
    ArchiveState state =
        ArchiveState.empty()
            .appendSeason(
                season(1)
                    .name("Genesis")
                    .origin(DimensionKey.OVERWORLD, 0, 64, 0)
                    .origin(DimensionKey.THE_NETHER, 0, 64, 0)
                    .spawn(overworld(8, 70, -8))
                    .build())
            .activate(seasonId(1))
            .appendSeason(
                season(2)
                    .name("Frühling 🌲")
                    .origin(DimensionKey.OVERWORLD, 1_048_576, 64, -2_097_152)
                    .origin(DimensionKey.THE_NETHER, 131_072, 64, -262_144)
                    .spawn(overworld(1_048_600, 72, -2_097_100))
                    .build())
            .activate(seasonId(2))
            .appendSeason(season(3).name("Next").build())
            .withProtectedRegion(
                new ProtectedRegion(
                    protectedRegionId(1),
                    DisplayName.of("Genesis spawn town"),
                    new ChunkRectangle(DimensionKey.OVERWORLD, -8, -8, 7, 7),
                    DomainFixtures.instant(3_000)))
            .withPortalMarker(
                new PortalMarker(
                    portalMarkerId(1),
                    DisplayName.of("Genesis hub"),
                    overworld(16, 70, 16),
                    seasonId(1),
                    instant(3_100)))
            .withPortalMarker(
                new PortalMarker(
                    portalMarkerId(2),
                    DisplayName.of("End gateway"),
                    new BlockPosition(DimensionKey.THE_END, 100, 60, 0),
                    null,
                    instant(3_200)))
            .withPlayerArchive(
                new PlayerArchiveRecord(
                    playerArchiveId(1),
                    playerId(1),
                    seasonId(1),
                    instant(2_500),
                    EnumSet.of(ArchiveCategory.ENDER_CHEST, ArchiveCategory.INVENTORY),
                    List.of(overworld(10, 70, -10), overworld(11, 70, -10))));
    ManifestDocument document =
        ManifestDocument.create(archiveId(1), instant(0))
            .withState(state)
            .withBackup(
                new BackupRecord(
                    backupId(1),
                    instant(2_400),
                    CHECKSUM_A,
                    123_456_789L,
                    operationId(1),
                    seasonId(1)),
                SidecarPath.of("backups/2026-01-01-genesis/world.tar"))
            .withFile(
                new FileReference(
                    "chunk-index",
                    DimensionKey.THE_NETHER,
                    SidecarPath.of("indexes/the_nether.idx"),
                    CHECKSUM_B,
                    4096))
            .withFile(
                new FileReference(
                    "chunk-index",
                    DimensionKey.OVERWORLD,
                    SidecarPath.of("indexes/overworld.idx"),
                    CHECKSUM_A,
                    65_536))
            .withFile(
                new FileReference(
                    "report", null, SidecarPath.of("reports/import-scan.json"), CHECKSUM_B, 512))
            .withFeature(
                new FeatureKey("mconeworld:global_strongholds"),
                new FeatureRecord(instant(2_000), "1"));
    for (int i = 0; i < 42; i++) {
      document = document.nextRevision();
    }
    return document;
  }

  /** Returns the bytes of a classpath resource, for example {@link #GOLDEN_FULL_V1}. */
  public static byte[] resource(String name) {
    try (InputStream in = ManifestFixtures.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("Missing fixture resource " + name);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
