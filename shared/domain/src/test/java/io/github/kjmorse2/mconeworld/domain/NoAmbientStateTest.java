// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Domain code must be deterministic: timestamps and identifiers are passed in, never read from the
 * wall clock or a random source (Prompt 03 §8, shared/AGENTS.md "Test quality"). This scans the
 * production sources, and the Gradle test task runs with the project directory as its working
 * directory.
 */
class NoAmbientStateTest {
  private static final List<String> FORBIDDEN =
      List.of(
          "Instant.now(",
          "Clock.system",
          "System.currentTimeMillis(",
          "System.nanoTime(",
          "LocalDateTime.now(",
          "LocalDate.now(",
          "ZonedDateTime.now(",
          "OffsetDateTime.now(",
          "UUID.randomUUID(",
          "new Random(",
          "ThreadLocalRandom",
          "SecureRandom",
          "Math.random(");

  @Test
  void productionSourcesNeverReadTheClockOrRandomness() throws IOException {
    Path main = Path.of("src/main/java");
    assertThat(main).isDirectory();
    List<String> violations = new ArrayList<>();
    try (Stream<Path> files = Files.walk(main)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
        String source = withoutComments(Files.readString(file));
        for (String forbidden : FORBIDDEN) {
          if (source.contains(forbidden)) {
            violations.add(main.relativize(file) + " uses " + forbidden);
          }
        }
      }
    }
    assertThat(violations).isEmpty();
  }

  @Test
  void commentStrippingKeepsCodeAndDropsDocs() {
    String code = "/** call UUID.randomUUID() */ int a; // Instant.now(\nInstant.now();";
    assertThat(withoutComments(code)).doesNotContain("UUID.randomUUID(").contains("Instant.now();");
  }

  /** Removes block and line comments, so documentation may mention forbidden calls. */
  static String withoutComments(String source) {
    return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
  }
}
