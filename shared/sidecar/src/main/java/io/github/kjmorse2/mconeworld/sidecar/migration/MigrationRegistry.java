// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The set of available migration steps, and path finding over them.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Every step moves exactly one version ({@link MigrationStep#isSingleStep()}), so no version
 *       is ever skipped silently.
 *   <li>A path is found only if it is contiguous and <b>unique</b>. Zero paths gives {@code
 *       NO_PATH}, and more than one gives {@code AMBIGUOUS_PATH}.
 *   <li>Towards an older version, only steps explicitly written as downgrades are used. Otherwise
 *       {@code DOWNGRADE_UNSUPPORTED}.
 * </ul>
 */
public final class MigrationRegistry {
  private final List<ManifestMigration> migrations;

  private MigrationRegistry(List<ManifestMigration> migrations) {
    this.migrations = List.copyOf(migrations);
  }

  /**
   * The production registry. It is <b>empty</b>: manifest 1.0 is the first released schema, so
   * there is nothing to migrate from yet. Future releases add their steps here.
   */
  public static MigrationRegistry builtIn() {
    return new MigrationRegistry(List.of());
  }

  /**
   * A registry of the given steps (used by tests and future releases).
   *
   * @throws MigrationException {@code INVALID_STEP} for a multi-version step or a duplicate id
   */
  public static MigrationRegistry of(List<ManifestMigration> migrations) throws MigrationException {
    Set<MigrationId> ids = new HashSet<>();
    for (ManifestMigration m : migrations) {
      MigrationStep step = Objects.requireNonNull(m.step(), "step");
      if (!step.isSingleStep()) {
        throw new MigrationException(
            MigrationIssueCodes.INVALID_STEP,
            step.id()
                + " jumps from "
                + step.from()
                + " to "
                + step.to()
                + "; steps must move exactly one version");
      }
      if (!ids.add(step.id())) {
        throw new MigrationException(
            MigrationIssueCodes.INVALID_STEP, "Duplicate migration id " + step.id());
      }
    }
    return new MigrationRegistry(migrations);
  }

  /** The registered steps. */
  public List<MigrationStep> steps() {
    return migrations.stream().map(ManifestMigration::step).toList();
  }

  /**
   * Finds the unique chain of steps from {@code from} to {@code to}.
   *
   * @throws MigrationException {@code NO_PATH}, {@code AMBIGUOUS_PATH}, or {@code
   *     DOWNGRADE_UNSUPPORTED}
   */
  public List<ManifestMigration> findPath(SchemaVersion from, SchemaVersion to)
      throws MigrationException {
    if (from.equals(to)) {
      return List.of();
    }
    boolean downgrade = to.compareTo(from) < 0;
    List<List<ManifestMigration>> paths = new ArrayList<>();
    search(from, to, downgrade, new ArrayList<>(), new HashSet<>(Set.of(from)), paths);
    if (paths.isEmpty()) {
      throw new MigrationException(
          downgrade ? MigrationIssueCodes.DOWNGRADE_UNSUPPORTED : MigrationIssueCodes.NO_PATH,
          downgrade
              ? "No explicit downgrade from "
                  + from
                  + " to "
                  + to
                  + " exists; downgrades are never implicit"
              : "No contiguous migration path from " + from + " to " + to);
    }
    if (paths.size() > 1) {
      throw new MigrationException(
          MigrationIssueCodes.AMBIGUOUS_PATH,
          paths.size()
              + " different migration paths lead from "
              + from
              + " to "
              + to
              + "; refusing to choose");
    }
    return List.copyOf(paths.get(0));
  }

  private void search(
      SchemaVersion at,
      SchemaVersion goal,
      boolean downgrade,
      List<ManifestMigration> current,
      Set<SchemaVersion> visited,
      List<List<ManifestMigration>> found) {
    if (at.equals(goal)) {
      found.add(List.copyOf(current));
      return;
    }
    for (ManifestMigration m : migrations) {
      MigrationStep step = m.step();
      if (!step.from().equals(at)
          || step.isDowngrade() != downgrade
          || visited.contains(step.to())) {
        continue;
      }
      current.add(m);
      visited.add(step.to());
      search(step.to(), goal, downgrade, current, visited, found);
      visited.remove(step.to());
      current.remove(current.size() - 1);
    }
  }
}
