// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The path of one file inside a backup, relative to the backup's directory, for example {@code
 * world/dimensions/minecraft/the_end/region/r.-1.0.mca} (ADR 0012; {@code backup-v1.md}).
 *
 * <p>Unlike {@link SidecarPath}, names are kept exactly as the world has them: worlds contain
 * spaces, capitals, and parentheses (datapack archives, mod caches), and a backup must restore them
 * byte for byte. The rules only exclude what is unsafe or ambiguous:
 *
 * <ul>
 *   <li>the first segment is an area: {@code world} or {@code sidecar}
 *   <li>2 to {@value #MAX_SEGMENTS} segments separated by {@code /}; at most {@value #MAX_BYTES}
 *       UTF-8 bytes in all, and {@value #MAX_SEGMENT_BYTES} per segment
 *   <li>no empty segment, no {@code .} or {@code ..}, no backslash, and no control character
 *       (U+0000 to U+001F, U+007F)
 * </ul>
 *
 * <p>Restoring onto a filesystem with stricter rules (Windows forbids {@code :} and reserved device
 * names; case-insensitive systems merge names differing only in case) may fail. That is reported at
 * restore time, never "fixed" by renaming.
 *
 * @param value the path text
 */
public record BackupEntryPath(String value) implements Comparable<BackupEntryPath> {
  /** The world area: files copied from the world directory. */
  public static final String WORLD = "world";

  /** The sidecar area: the sidecar's authoritative files. */
  public static final String SIDECAR = "sidecar";

  public static final int MAX_BYTES = 4096;
  public static final int MAX_SEGMENTS = 64;
  public static final int MAX_SEGMENT_BYTES = 255;

  private static final Set<String> AREAS = Set.of(WORLD, SIDECAR);

  /**
   * @throws UnsafeSidecarPathException if {@code value} breaks a rule above
   */
  public BackupEntryPath {
    Objects.requireNonNull(value, "value");
    String problem = findProblem(value);
    if (problem != null) {
      throw new UnsafeSidecarPathException(value, problem);
    }
  }

  public static BackupEntryPath of(String value) {
    return new BackupEntryPath(value);
  }

  /** {@code area} followed by {@code relative} (a path inside the area, '/'-separated). */
  public static BackupEntryPath of(String area, String relative) {
    return new BackupEntryPath(area + "/" + relative);
  }

  public static boolean isValid(String value) {
    return findProblem(value) == null;
  }

  public List<String> segments() {
    return List.of(value.split("/", -1));
  }

  /** The area: {@value #WORLD} or {@value #SIDECAR}. */
  public String area() {
    return segments().getFirst();
  }

  /** The path below the area. */
  public String relative() {
    return value.substring(value.indexOf('/') + 1);
  }

  /**
   * Code-point order, which equals the byte order of the UTF-8 text: the order entries are listed
   * in. (Plain {@link String#compareTo} would differ above U+FFFF.)
   */
  @Override
  public int compareTo(BackupEntryPath other) {
    return CanonicalJsonWriter.CODE_POINT_ORDER.compare(value, other.value);
  }

  @Override
  public String toString() {
    return value;
  }

  private static @Nullable String findProblem(String value) {
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      return "longer than " + MAX_BYTES + " bytes";
    }
    String[] segments = value.split("/", -1);
    if (segments.length < 2 || segments.length > MAX_SEGMENTS) {
      return "must have 2 to " + MAX_SEGMENTS + " segments";
    }
    if (!AREAS.contains(segments[0])) {
      return "must start with " + WORLD + "/ or " + SIDECAR + "/";
    }
    for (String segment : segments) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        return "empty, \".\", or \"..\" segment";
      }
      if (segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
        return "a segment is longer than " + MAX_SEGMENT_BYTES + " bytes";
      }
      boolean bad =
          segment
              .codePoints()
              .anyMatch(
                  cp ->
                      cp < 0x20
                          || cp == 0x7F
                          || cp == '\\'
                          || Character.getType(cp) == Character.SURROGATE);
      if (bad) {
        return "control character, backslash, or unpaired surrogate";
      }
    }
    return null;
  }
}
