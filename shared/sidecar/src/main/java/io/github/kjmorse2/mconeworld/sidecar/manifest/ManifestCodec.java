// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.archive.WorldBinding;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonIssueCodes;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Decodes and encodes {@code manifest.json} (schema {@value #FORMAT} {@link #CURRENT}). It is
 * thread-safe and has no I/O side effects: callers supply and receive bytes. See {@code
 * docs/formats/manifest-v1.md} for the normative format.
 *
 * <ul>
 *   <li><b>Decode</b> fully validates before returning: syntax, limits, version, schema,
 *       references, and domain rules. Any error throws {@link ManifestException} with every issue
 *       found. The input is never modified.
 *   <li><b>Encode</b> refuses invalid documents and produces the canonical bytes. The written
 *       schema version is the higher of {@link #CURRENT} and the version the document was read as,
 *       so members preserved from a newer minor version stay labelled correctly.
 * </ul>
 */
public final class ManifestCodec {
  /** Value of the top-level {@code format} member. */
  public static final String FORMAT = "mconeworld-manifest";

  /** The schema version this codec implements. It is not the application release version. */
  public static final SchemaVersion CURRENT = new SchemaVersion(1, 1);

  private final ManifestLimits limits;
  private final JsonReader reader;

  /** A codec with {@link ManifestLimits#DEFAULT}. */
  public ManifestCodec() {
    this(ManifestLimits.DEFAULT);
  }

  public ManifestCodec(ManifestLimits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.reader = new JsonReader(limits.toJsonLimits());
  }

  /**
   * Decodes and validates manifest bytes.
   *
   * @throws ManifestException with every problem found
   */
  public DecodedManifest decode(byte[] bytes) throws ManifestException {
    JsonValue tree;
    try {
      tree = reader.read(bytes);
    } catch (JsonReadException e) {
      throw new ManifestException(
          ValidationResult.of(
              List.of(
                  ValidationIssue.error(
                      e.code(), IssueLocation.root(), String.valueOf(e.getMessage())))));
    }
    ManifestDecoder decoder = new ManifestDecoder();
    ManifestDocument document = decoder.decode(tree);
    ValidationResult issues = ValidationResult.of(decoder.issues());
    if (document == null || !issues.isValid()) {
      throw new ManifestException(issues);
    }
    boolean canonical = Arrays.equals(canonicalBytes(document), bytes);
    return new DecodedManifest(document, issues, canonical);
  }

  /**
   * Reads at most {@code maxDocumentBytes + 1} bytes from {@code in}, then decodes. The stream is
   * not closed.
   *
   * @throws ManifestException with a {@code sidecar.json.limit.document_size} error if the stream
   *     is larger than the limit
   * @throws IOException if reading fails
   */
  public DecodedManifest decode(InputStream in) throws ManifestException, IOException {
    long max = limits.maxDocumentBytes();
    byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
    if (bytes.length > max) {
      throw new ManifestException(
          ValidationResult.of(
              List.of(
                  ValidationIssue.error(
                      JsonIssueCodes.LIMIT_DOCUMENT_SIZE,
                      IssueLocation.root(),
                      "Manifest is larger than " + max + " bytes"))));
    }
    return decode(bytes);
  }

  /**
   * Encodes a valid document to canonical bytes.
   *
   * @throws ManifestException if {@link ManifestDocument#validate()} reports errors, or a timestamp
   *     is outside years 0000–9999
   */
  public byte[] encode(ManifestDocument document) throws ManifestException {
    List<ValidationIssue> issues = new ArrayList<>(document.validate().issues());
    for (Instant instant : instantsOf(document)) {
      if (!ManifestEncoder.isEncodable(instant)) {
        issues.add(
            ValidationIssue.error(
                ManifestIssueCodes.INVALID_VALUE,
                IssueLocation.root(),
                "Timestamp " + instant + " is outside years 0000-9999"));
      }
    }
    ValidationResult result = ValidationResult.of(issues);
    if (!result.isValid()) {
      throw new ManifestException(result);
    }
    return canonicalBytes(document);
  }

  private static byte[] canonicalBytes(ManifestDocument document) {
    SchemaVersion written =
        document.schemaVersion().compareTo(CURRENT) > 0 ? document.schemaVersion() : CURRENT;
    return CanonicalJsonWriter.write(
        new ManifestEncoder(document.preserved()).encode(document, written));
  }

  private static List<Instant> instantsOf(ManifestDocument d) {
    List<Instant> all = new ArrayList<>();
    all.add(d.createdAt());
    d.state().seasons().forEach(s -> all.add(s.createdAt()));
    d.state().protectedRegions().forEach(r -> all.add(r.createdAt()));
    d.state().portalMarkers().forEach(m -> all.add(m.createdAt()));
    d.state().backups().forEach(b -> all.add(b.createdAt()));
    d.state().playerArchives().forEach(a -> all.add(a.archivedAt()));
    d.features().values().forEach(f -> all.add(f.adoptedAt()));
    WorldBinding world = d.state().worldBinding();
    if (world != null) {
      all.add(world.boundAt());
    }
    d.state().archivedDimensions().forEach(a -> all.add(a.archivedAt()));
    return all;
  }
}
