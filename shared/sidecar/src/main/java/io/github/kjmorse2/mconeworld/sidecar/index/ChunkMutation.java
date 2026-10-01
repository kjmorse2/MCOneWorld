// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One change to one chunk's record. This is the complete set of index updates, and {@link #applyTo}
 * is their only definition: every implementation applies mutations through it, so storage choices
 * can never change semantics.
 *
 * <table>
 *   <caption>Effect of each mutation</caption>
 *   <tr><th>Mutation</th><th>No record yet</th><th>Existing record</th></tr>
 *   <tr><td>{@link ObserveFirst}</td><td>creates it</td><td>unchanged: the first observation
 *       wins, so a repeated or late observation is a safe no-op</td></tr>
 *   <tr><td>{@link RecordActivity}</td><td>rejected ({@code record_absent})</td><td>merged by
 *       {@link ChunkProvenance#withActivity}: the later observation per season wins</td></tr>
 *   <tr><td>{@link SetProtection}</td><td>rejected ({@code record_absent})</td><td>protection
 *       replaced; provenance unchanged</td></tr>
 *   <tr><td>{@link Replace}</td><td>creates it</td><td>replaced entirely (import or administrator
 *       correction)</td></tr>
 *   <tr><td>{@link Remove}</td><td>no-op</td><td>removed (only after a journaled operation deleted
 *       the chunk itself)</td></tr>
 * </table>
 *
 * <p>Every mutation except {@link Replace} is idempotent, so a retried batch cannot double-apply.
 */
public sealed interface ChunkMutation {

  /** The chunk this mutation changes. */
  ChunkPosition chunk();

  /**
   * Returns the record after this mutation, or {@code null} if the chunk has no record afterwards.
   *
   * @param existing the current record of {@link #chunk()}, or {@code null} if there is none
   * @throws ChunkIndexException with {@link ChunkIndexIssueCodes#RECORD_ABSENT} if the mutation
   *     needs an existing record, or {@link ChunkIndexIssueCodes#RECORD_MISMATCH} if {@code
   *     existing} describes another chunk
   */
  @Nullable ChunkProvenance applyTo(@Nullable ChunkProvenance existing) throws ChunkIndexException;

  /**
   * Records how MCOneWorld first saw the chunk, unless it already has a record.
   *
   * @param chunk the chunk
   * @param first the first observation
   */
  record ObserveFirst(ChunkPosition chunk, FirstObservation first) implements ChunkMutation {
    public ObserveFirst {
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(first, "first");
    }

    @Override
    public ChunkProvenance applyTo(@Nullable ChunkProvenance existing) throws ChunkIndexException {
      return existing != null
          ? checked(chunk, existing)
          : ChunkProvenance.firstObserved(chunk, first);
    }
  }

  /**
   * Records activity in a season on a chunk that already has a record.
   *
   * @param chunk the chunk
   * @param activity the observation
   */
  record RecordActivity(ChunkPosition chunk, SeasonActivity activity) implements ChunkMutation {
    public RecordActivity {
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(activity, "activity");
    }

    @Override
    public ChunkProvenance applyTo(@Nullable ChunkProvenance existing) throws ChunkIndexException {
      return requireExisting(this, existing).withActivity(activity);
    }
  }

  /**
   * Sets the explicit protection of a chunk that already has a record.
   *
   * @param chunk the chunk
   * @param protection the new protection
   */
  record SetProtection(ChunkPosition chunk, ChunkProtection protection) implements ChunkMutation {
    public SetProtection {
      Objects.requireNonNull(chunk, "chunk");
      Objects.requireNonNull(protection, "protection");
    }

    @Override
    public ChunkProvenance applyTo(@Nullable ChunkProvenance existing) throws ChunkIndexException {
      return requireExisting(this, existing).withProtection(protection);
    }
  }

  /**
   * Stores {@code record} as the chunk's complete record, whatever was there.
   *
   * @param record the new record; its chunk is this mutation's chunk
   */
  record Replace(ChunkProvenance record) implements ChunkMutation {
    public Replace {
      Objects.requireNonNull(record, "record");
    }

    @Override
    public ChunkPosition chunk() {
      return record.chunk();
    }

    @Override
    public ChunkProvenance applyTo(@Nullable ChunkProvenance existing) throws ChunkIndexException {
      if (existing != null) {
        checked(record.chunk(), existing);
      }
      return record;
    }
  }

  /**
   * Removes the chunk's record, if any.
   *
   * @param chunk the chunk
   */
  record Remove(ChunkPosition chunk) implements ChunkMutation {
    public Remove {
      Objects.requireNonNull(chunk, "chunk");
    }

    @Override
    public @Nullable ChunkProvenance applyTo(@Nullable ChunkProvenance existing)
        throws ChunkIndexException {
      if (existing != null) {
        checked(chunk, existing);
      }
      return null;
    }
  }

  private static ChunkProvenance requireExisting(
      ChunkMutation mutation, @Nullable ChunkProvenance existing) throws ChunkIndexException {
    if (existing == null) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.RECORD_ABSENT,
          mutation.getClass().getSimpleName()
              + " needs an existing record, but chunk "
              + mutation.chunk()
              + " has none. Record its first observation first.");
    }
    return checked(mutation.chunk(), existing);
  }

  private static ChunkProvenance checked(ChunkPosition chunk, ChunkProvenance existing)
      throws ChunkIndexException {
    if (!existing.chunk().equals(chunk)) {
      throw new ChunkIndexException(
          ChunkIndexIssueCodes.RECORD_MISMATCH,
          "Record for " + existing.chunk() + " was supplied for chunk " + chunk);
    }
    return existing;
  }
}
