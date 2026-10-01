// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The semantics table in {@link ChunkMutation}'s documentation, row by row. */
class ChunkMutationTest {
  static final ChunkPosition CHUNK = new ChunkPosition(DimensionKey.OVERWORLD, -1, 7);
  static final ChunkPosition OTHER = new ChunkPosition(DimensionKey.OVERWORLD, 0, 7);
  static final SeasonId S1 = SeasonId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
  static final SeasonId S2 = SeasonId.of(UUID.fromString("00000000-0000-4000-8000-000000000002"));
  static final ProvenanceEvidence RUNTIME =
      new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH);
  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  static final FirstObservation FIRST = new FirstObservation.InSeason(S1, T0, RUNTIME);
  static final ChunkProvenance RECORD = ChunkProvenance.firstObserved(CHUNK, FIRST);

  @Test
  void observeFirstCreatesOnceAndThenKeepsTheFirst() throws Exception {
    assertThat(new ChunkMutation.ObserveFirst(CHUNK, FIRST).applyTo(null)).isEqualTo(RECORD);
    FirstObservation later = new FirstObservation.InSeason(S2, T0.plusSeconds(9), RUNTIME);
    assertThat(new ChunkMutation.ObserveFirst(CHUNK, later).applyTo(RECORD)).isSameAs(RECORD);
  }

  @Test
  void activityAndProtectionNeedAnExistingRecord() {
    SeasonActivity activity = new SeasonActivity(S2, T0, RUNTIME);
    assertThatThrownBy(() -> new ChunkMutation.RecordActivity(CHUNK, activity).applyTo(null))
        .isInstanceOf(ChunkIndexException.class)
        .extracting(e -> ((ChunkIndexException) e).code())
        .isEqualTo(ChunkIndexIssueCodes.RECORD_ABSENT);
    assertThatThrownBy(
            () -> new ChunkMutation.SetProtection(CHUNK, ChunkProtection.PROTECTED).applyTo(null))
        .extracting(e -> ((ChunkIndexException) e).code())
        .isEqualTo(ChunkIndexIssueCodes.RECORD_ABSENT);
  }

  @Test
  void activityMergesLaterObservationPerSeason() throws Exception {
    SeasonActivity early = new SeasonActivity(S2, T0, RUNTIME);
    SeasonActivity late = new SeasonActivity(S2, T0.plusSeconds(60), RUNTIME);
    ChunkProvenance once = new ChunkMutation.RecordActivity(CHUNK, late).applyTo(RECORD);
    assertThat(new ChunkMutation.RecordActivity(CHUNK, early).applyTo(once)).isEqualTo(once);
    assertThat(new ChunkMutation.RecordActivity(CHUNK, late).applyTo(once)).isEqualTo(once);
    assertThat(once.activity()).containsExactly(late);
  }

  @Test
  void setProtectionChangesOnlyProtection() throws Exception {
    ChunkProvenance changed =
        new ChunkMutation.SetProtection(CHUNK, ChunkProtection.PROTECTED).applyTo(RECORD);
    assertThat(changed).isEqualTo(RECORD.withProtection(ChunkProtection.PROTECTED));
  }

  @Test
  void replaceAndRemove() throws Exception {
    ChunkProvenance replacement =
        new ChunkProvenance(
            CHUNK,
            new FirstObservation.Legacy(T0, RUNTIME),
            List.of(new SeasonActivity(S1, T0, RUNTIME)),
            ChunkProtection.NONE);
    assertThat(new ChunkMutation.Replace(replacement).applyTo(RECORD)).isSameAs(replacement);
    assertThat(new ChunkMutation.Replace(replacement).applyTo(null)).isSameAs(replacement);
    assertThat(new ChunkMutation.Remove(CHUNK).applyTo(RECORD)).isNull();
    assertThat(new ChunkMutation.Remove(CHUNK).applyTo(null)).isNull();
  }

  @Test
  void aRecordForAnotherChunkIsRejected() {
    ChunkProvenance foreign = ChunkProvenance.firstObserved(OTHER, FIRST);
    assertThatThrownBy(() -> new ChunkMutation.Remove(CHUNK).applyTo(foreign))
        .extracting(e -> ((ChunkIndexException) e).code())
        .isEqualTo(ChunkIndexIssueCodes.RECORD_MISMATCH);
    assertThatThrownBy(() -> new ChunkMutation.ObserveFirst(CHUNK, FIRST).applyTo(foreign))
        .extracting(e -> ((ChunkIndexException) e).code())
        .isEqualTo(ChunkIndexIssueCodes.RECORD_MISMATCH);
  }
}
