// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.backupId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupEntry;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupKind;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifest;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Golden {@code backup.json} fixtures ({@code backup-v1.md}): {@value #GOLDEN_FULL} is the encoding
 * of {@link #full()}, {@value #GOLDEN_ARCHIVE} of {@link #endArchive()}. Entry names include the
 * kinds a real world has: spaces, capitals, and parentheses (datapack archives, mod caches).
 *
 * <p>To change them intentionally, run {@link #main} with a scratch directory, review the bytes,
 * copy them in, and update the {@code .meta} checksums.
 */
public final class BackupFixtures {
  public static final String GOLDEN_FULL =
      "/io/github/kjmorse2/mconeworld/fixtures/backup/v1/full.json";

  public static final String GOLDEN_ARCHIVE =
      "/io/github/kjmorse2/mconeworld/fixtures/backup/v1/end-archive.json";

  private BackupFixtures() {}

  private static Checksum sha(char c) {
    return Checksum.parse("sha256:" + String.valueOf(c).repeat(64));
  }

  /** A full backup of a small world and its sidecar. */
  public static BackupManifest full() {
    return new BackupManifest(
        backupId(1),
        BackupKind.FULL,
        instant(2_400),
        null,
        4903,
        41L,
        List.of(
            entry("world/level.dat", 641, 'a'),
            entry("world/datapacks/afk display v1.1.15 (MC 1.21-1.21.11).zip", 20_480, 'b'),
            entry("world/dimensions/minecraft/overworld/region/r.-1.0.mca", 8_192, 'c'),
            entry("world/dimensions/minecraft/overworld/data/DistantHorizons.sqlite", 4_096, 'd'),
            entry("world/players/data/5cd87f22-1adf-47f2-abf3-04a41dae96ee.dat", 1_234, 'e'),
            entry("sidecar/manifest.json", 3_000, 'f'),
            entry("sidecar/chunk-index/catalog", 512, '0')));
  }

  /** The End archived by a season transition (ADR 0010). */
  public static BackupManifest endArchive() {
    return new BackupManifest(
        backupId(2),
        BackupKind.DIMENSION_ARCHIVE,
        instant(2_450),
        DimensionKey.THE_END,
        4903,
        null,
        List.of(
            entry("world/dimensions/minecraft/the_end/region/r.0.0.mca", 16_384, '1'),
            entry("world/dimensions/minecraft/the_end/entities/r.0.0.mca", 8_192, '2'),
            entry(
                "world/dimensions/minecraft/the_end/data/minecraft/ender_dragon_fight.dat",
                300,
                '3')));
  }

  private static BackupEntry entry(String path, long size, char hash) {
    return new BackupEntry(BackupEntryPath.of(path), size, sha(hash));
  }

  /** Writes both golden files into {@code args[0]} for review. */
  public static void main(String[] args) throws IOException {
    Path out = Path.of(args[0]);
    Files.createDirectories(out);
    BackupManifestCodec codec = new BackupManifestCodec();
    Files.write(out.resolve("full.json"), codec.encode(full()));
    Files.write(out.resolve("end-archive.json"), codec.encode(endArchive()));
  }
}
