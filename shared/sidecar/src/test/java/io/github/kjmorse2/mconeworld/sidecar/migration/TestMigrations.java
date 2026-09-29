// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Deliberately faulty or auxiliary migrations used only to exercise failure paths. */
final class TestMigrations {
  private TestMigrations() {}

  static byte[] v09() {
    return resource("v0.9-synthetic.json");
  }

  static byte[] resource(String name) {
    try (var in = TestMigrations.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("missing " + name);
      }
      return in.readAllBytes();
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  static SchemaVersion v(int major, int minor) {
    return new SchemaVersion(major, minor);
  }

  /** A step with the given versions that just relabels the schema version. */
  static ManifestMigration relabel(String id, SchemaVersion from, SchemaVersion to) {
    MigrationStep step = new MigrationStep(new MigrationId(id), from, to, List.of());
    return new ManifestMigration() {
      @Override
      public MigrationStep step() {
        return step;
      }

      @Override
      public byte[] migrate(byte[] source, MigrationRecorder recorder) {
        recorder.transformed("$.schemaVersion", from + " -> " + to);
        return new String(source, StandardCharsets.UTF_8)
            .replace("\"schemaVersion\": \"" + from + "\"", "\"schemaVersion\": \"" + to + "\"")
            .getBytes(StandardCharsets.UTF_8);
      }
    };
  }

  /**
   * Produces a syntactically valid 1.0 manifest that fails validation (a required member is
   * missing).
   */
  static ManifestMigration brokenOutput() {
    MigrationStep step =
        new MigrationStep(new MigrationId("broken-0.9-to-1.0"), v(0, 9), v(1, 0), List.of());
    return new ManifestMigration() {
      @Override
      public MigrationStep step() {
        return step;
      }

      @Override
      public byte[] migrate(byte[] source, MigrationRecorder recorder) {
        String golden =
            new String(
                ManifestFixtures.resource(ManifestFixtures.GOLDEN_FULL_V1), StandardCharsets.UTF_8);
        return golden.replace("  \"revision\": 42,\n", "").getBytes(StandardCharsets.UTF_8);
      }
    };
  }

  /** Correct on the first run, different afterwards: models a migration that reads a clock. */
  static ManifestMigration nondeterministic() {
    AtomicInteger runs = new AtomicInteger();
    SyntheticManifest09To10 real = new SyntheticManifest09To10();
    return new ManifestMigration() {
      @Override
      public MigrationStep step() {
        return real.step();
      }

      @Override
      public byte[] migrate(byte[] source, MigrationRecorder recorder) throws MigrationException {
        byte[] out = real.migrate(source, recorder);
        if (runs.incrementAndGet() > 1) {
          recorder.transformed("$.clock", "run " + runs.get());
        }
        return out;
      }
    };
  }
}
