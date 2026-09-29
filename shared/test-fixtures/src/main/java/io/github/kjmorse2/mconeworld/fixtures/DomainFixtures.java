// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.PlayerArchiveId;
import io.github.kjmorse2.mconeworld.domain.PlayerId;
import io.github.kjmorse2.mconeworld.domain.PortalMarkerId;
import io.github.kjmorse2.mconeworld.domain.ProtectedRegionId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.domain.season.SeasonStatus;
import java.time.Instant;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic domain values for tests. Nothing here reads the clock or a random source, so the
 * same call always yields the same value.
 *
 * <ul>
 *   <li>Identifiers are {@code <kind-prefix>-0000-4000-8000-<n>}. Each kind has its own prefix, so
 *       {@code seasonId(1)} and {@code operationId(1)} differ. {@code n >= 1}, so the nil UUID is
 *       never produced by accident.
 *   <li>{@link #instant(long)} is seconds after {@link #EPOCH}, 2026-01-01T00:00:00Z.
 * </ul>
 */
public final class DomainFixtures {
  /** Base instant for {@link #instant(long)}. */
  public static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

  private DomainFixtures() {}

  /** {@code EPOCH + seconds}. */
  public static Instant instant(long seconds) {
    return EPOCH.plusSeconds(seconds);
  }

  public static SeasonId seasonId(int n) {
    return SeasonId.of(uuid(0x5ea50000L, n));
  }

  public static OperationId operationId(int n) {
    return OperationId.of(uuid(0x0be40000L, n));
  }

  public static BackupId backupId(int n) {
    return BackupId.of(uuid(0xbacc0000L, n));
  }

  public static PortalMarkerId portalMarkerId(int n) {
    return PortalMarkerId.of(uuid(0x90e70000L, n));
  }

  public static ProtectedRegionId protectedRegionId(int n) {
    return ProtectedRegionId.of(uuid(0x9e0e0000L, n));
  }

  public static PlayerArchiveId playerArchiveId(int n) {
    return PlayerArchiveId.of(uuid(0xa4c40000L, n));
  }

  /** A stand-in for an existing account UUID. Tests model players that already exist. */
  public static PlayerId playerId(int n) {
    return PlayerId.of(uuid(0x91a70000L, n));
  }

  /** Overworld block at the given coordinates. */
  public static BlockPosition overworld(long x, int y, long z) {
    return new BlockPosition(DimensionKey.OVERWORLD, x, y, z);
  }

  /**
   * Starts a {@link SeasonRecord} builder with valid, documented defaults (see {@link
   * SeasonBuilder}).
   */
  public static SeasonBuilder season(int n) {
    return new SeasonBuilder(seasonId(n));
  }

  /**
   * Builder for {@link SeasonRecord}. Defaults are valid and documented: name {@code "Season <n>"},
   * created at {@code instant(n * 1000)}, {@code PLANNED}, no origins, and spawn at Overworld
   * {@code (n * 1_048_576, 64, 0)}. Invalid values are never substituted. A test that wants one
   * must pass it explicitly and see the domain exception.
   */
  public static final class SeasonBuilder {
    private final SeasonId id;
    private DisplayName name;
    private Instant createdAt;
    private SeasonStatus status = SeasonStatus.PLANNED;
    private final TreeMap<DimensionKey, DimensionOrigin> origins = new TreeMap<>();
    private BlockPosition spawn;

    private SeasonBuilder(SeasonId id) {
      this.id = id;
      long n = id.value().getLeastSignificantBits() & 0xFFFFFFFFL;
      this.name = DisplayName.of("Season " + n);
      this.createdAt = instant(n * 1000);
      this.spawn = overworld(n * 1_048_576L, 64, 0);
    }

    public SeasonBuilder name(String name) {
      this.name = DisplayName.of(name);
      return this;
    }

    public SeasonBuilder createdAt(Instant createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public SeasonBuilder status(SeasonStatus status) {
      this.status = status;
      return this;
    }

    public SeasonBuilder origin(DimensionKey dimension, long x, int y, long z) {
      origins.put(dimension, new DimensionOrigin(dimension, x, y, z));
      return this;
    }

    public SeasonBuilder spawn(BlockPosition spawn) {
      this.spawn = spawn;
      return this;
    }

    public SeasonRecord build() {
      return new SeasonRecord(id, name, createdAt, status, origins, spawn);
    }
  }

  private static UUID uuid(long kindPrefix, int n) {
    if (n < 1) {
      throw new IllegalArgumentException("Fixture ids start at 1, got " + n);
    }
    // Version-4/variant-2 layout so the value looks like a real random UUID.
    return new UUID((kindPrefix << 32) | 0x0000_4000L, 0x8000_0000_0000_0000L | n);
  }
}
