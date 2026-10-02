// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.IndexFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Read-only inspection and verification. Inspection explains every file in {@code chunk-index/}
 * without reading blocks; verification also decodes every block, compares it with its catalog entry
 * and summary, and resolves every logged mutation. Both report precise locations, for example
 * {@code $.regions[minecraft:overworld][r.-2.5]}, and keep going after a failure (up to {@value
 * #MAX_ISSUES} issues) so one damaged region does not hide another.
 */
final class IndexInspector {
  static final int MAX_ISSUES = 1_000;

  private IndexInspector() {}

  static IssueLocation fileLocation(String name) {
    return IssueLocation.root().field("files").key(name);
  }

  static IssueLocation regionLocation(DimensionKey dimension, Catalog.Entry e) {
    return IssueLocation.root()
        .field("regions")
        .key(dimension.namespacedId())
        .key("r." + e.regionX + "." + e.regionZ);
  }

  /** Explains every file of the area against the committed {@code snapshot}. */
  static ValidationResult inspectFiles(IndexFiles files, IndexSnapshot snapshot)
      throws ChunkIndexException {
    List<ValidationIssue> issues = new ArrayList<>();
    long generation = snapshot.catalog().generation;
    if (snapshot.readOnly()) {
      issues.add(
          ValidationIssue.warning(
              ChunkIndexIssueCodes.NEWER_MINOR_VERSION,
              IssueLocation.root(),
              "a newer minor format version wrote this index; it is read-only here"));
    }
    if (snapshot.tornTail()) {
      issues.add(
          ValidationIssue.warning(
                  ChunkIndexIssueCodes.TORN_TAIL,
                  fileLocation(IndexFormat.logPath(generation).toString()),
                  "the log ends in an incomplete frame: a commit that was never acknowledged")
              .withRemediation("Nothing to do: the next commit checkpoints first."));
    }
    Set<Long> referenced = new HashSet<>();
    for (NavigableMap<Long, Catalog.Entry> map : snapshot.catalog().regions.values()) {
      for (Catalog.Entry e : map.values()) {
        referenced.add(e.segment);
      }
    }
    try {
      for (String name : files.list(null)) {
        if (name.equals(IndexFormat.CATALOG.toString()) || name.equals(IndexFormat.SEGMENTS_DIR)) {
          continue;
        }
        long logGeneration = IndexFormat.logGeneration(name);
        long tempGeneration = IndexFormat.catalogTempGeneration(name);
        if (logGeneration == generation) {
          continue;
        }
        if (logGeneration >= 0 && logGeneration < generation) {
          issues.add(
              info(
                  ChunkIndexIssueCodes.STALE_LOG,
                  name,
                  "log of generation " + logGeneration + ", whose checkpoint completed"));
        } else if (tempGeneration == generation + 1) {
          issues.add(
              info(
                  ChunkIndexIssueCodes.INTERRUPTED_CHECKPOINT,
                  name,
                  "temporary catalog of an interrupted checkpoint; it was never committed"));
        } else {
          issues.add(
              ValidationIssue.error(
                      ChunkIndexIssueCodes.UNEXPLAINED_FILE,
                      fileLocation(name),
                      "the index format does not explain this entry")
                  .withRemediation(
                      "Inspect it and remove it by hand; it is never removed automatically."));
        }
      }
      for (String name : files.list(SidecarPath.of(IndexFormat.SEGMENTS_DIR))) {
        long number = IndexFormat.segmentNumber(name);
        String path = IndexFormat.SEGMENTS_DIR + "/" + name;
        if (number < 1) {
          issues.add(
              ValidationIssue.error(
                  ChunkIndexIssueCodes.UNEXPLAINED_FILE,
                  fileLocation(path),
                  "the index format does not explain this entry"));
        } else if (!referenced.contains(number)) {
          issues.add(
              info(
                  ChunkIndexIssueCodes.ORPHAN_SEGMENT,
                  path,
                  "no block of the current catalog is in this segment"));
        }
      }
    } catch (IOException e) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.IO_FAILURE, "cannot list chunk-index/", "chunk-index", e);
    }
    return ValidationResult.of(issues);
  }

  private static ValidationIssue info(
      io.github.kjmorse2.mconeworld.domain.validation.IssueCode code, String file, String message) {
    return ValidationIssue.of(code, Severity.INFO, fileLocation(file), message)
        .withRemediation("Harmless. Explicit maintenance (removeObsoleteFiles) may delete it.");
  }

  /** {@link #inspectFiles} plus every block and every logged mutation. */
  static ValidationResult verify(IndexFiles files, IndexData data, IndexSnapshot snapshot)
      throws ChunkIndexException {
    List<ValidationIssue> issues = new ArrayList<>(inspectFiles(files, snapshot).issues());
    for (Map.Entry<DimensionKey, NavigableMap<Long, Catalog.Entry>> dimension :
        snapshot.catalog().regions.entrySet()) {
      for (Catalog.Entry e : dimension.getValue().values()) {
        if (issues.size() >= MAX_ISSUES) {
          return truncated(issues);
        }
        try {
          @Nullable ChunkProvenance[] records = data.block(dimension.getKey(), e).records();
          byte[] expected = Catalog.Summary.of(records).encoded();
          if (!Arrays.equals(expected, e.summary.encoded())) {
            issues.add(
                ValidationIssue.error(
                    ChunkIndexIssueCodes.SUMMARY_MISMATCH,
                    regionLocation(dimension.getKey(), e),
                    "the catalog summary differs from the block's records"));
          }
        } catch (ChunkIndexException failure) {
          issues.add(issue(failure, regionLocation(dimension.getKey(), e)));
        }
      }
    }
    for (Map.Entry<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> dimension :
        snapshot.overlay().dimensions().entrySet()) {
      for (Map.Entry<Long, List<ChunkMutation>> logged : dimension.getValue().entrySet()) {
        if (issues.size() >= MAX_ISSUES) {
          return truncated(issues);
        }
        ChunkPosition chunk = ChunkOrder.position(dimension.getKey(), logged.getKey());
        try {
          long key = logged.getKey();
          Catalog.Entry e = snapshot.catalog().entry(dimension.getKey(), key);
          @Nullable ChunkProvenance stored =
              e == null ? null : data.record(dimension.getKey(), e, ChunkOrder.localIndex(key));
          Overlay.resolve(stored, logged.getValue());
        } catch (ChunkIndexException failure) {
          issues.add(
              issue(
                  failure,
                  IssueLocation.root()
                      .field("log")
                      .key(dimension.getKey().namespacedId())
                      .key(chunk.x() + "," + chunk.z())));
        }
      }
    }
    return ValidationResult.of(issues);
  }

  static ValidationIssue issue(ChunkIndexException failure, IssueLocation location) {
    return ValidationIssue.error(failure.code(), location, String.valueOf(failure.getMessage()))
        .withCause(failure);
  }

  private static ValidationResult truncated(List<ValidationIssue> issues) {
    issues.add(
        ValidationIssue.of(
            ChunkIndexIssueCodes.TOO_LARGE,
            Severity.INFO,
            IssueLocation.root(),
            "verification stopped after " + MAX_ISSUES + " issues"));
    return ValidationResult.of(issues);
  }
}
