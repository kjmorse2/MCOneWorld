// SPDX-License-Identifier: MIT
// Consumer example: what the Paper adapter (Java 25) needs, with no Paper types: the writer lock,
// a durable chunk-index batch, and the server side of the protocol over plain byte arrays.
// Compiled and run by scripts/check-consumer-examples.sh; not part of the Gradle build.

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.protocol.ContextMessage;
import io.github.kjmorse2.mconeworld.protocol.HelloMessage;
import io.github.kjmorse2.mconeworld.protocol.Negotiation;
import io.github.kjmorse2.mconeworld.protocol.ProtocolException;
import io.github.kjmorse2.mconeworld.protocol.ProtocolSupport;
import io.github.kjmorse2.mconeworld.protocol.SeasonContext;
import io.github.kjmorse2.mconeworld.protocol.codec.ProtocolCodec;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.SidecarChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

public final class PaperAdapterConsumer {
  public static void main(String[] args) throws Exception {
    SeasonId season = SeasonId.of(UUID.fromString("5ea50001-0000-4000-8000-000000000001"));
    SidecarStore store = SidecarStore.open(SidecarRoot.open(Path.of(args[0])));
    try (SidecarWriter writer =
            store.openWriter(new LockOwner("mconeworld-paper", "0.0.0-example"), Clock.systemUTC(), StaleLockPolicy.REFUSE);
        SidecarChunkIndex index = ChunkIndexStore.of(store).create(writer)) {
      try (var batch = index.begin()) {
        batch.apply(new ChunkMutation.ObserveFirst(
            new ChunkPosition(DimensionKey.OVERWORLD, -65_536, 131_072),
            new FirstObservation.InSeason(season, Clock.systemUTC().instant(),
                new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH))));
        batch.commit();
      }
      System.out.println("paper: committed, generation=" + index.state().generation());
    }

    // A custom payload arrives as bytes; a malformed one is logged by code and dropped.
    byte[] packet = ProtocolCodec.encodeHello(HelloMessage.v1("example"));
    HelloMessage hello;
    try {
      hello = ProtocolCodec.decodeHello(packet);
    } catch (ProtocolException e) {
      System.out.println("paper: dropped hello " + e.code().value());
      return;
    }
    SeasonContext active = SeasonContext.of(season, DisplayName.of("Season 1"), 1,
        List.of(new DimensionOrigin(DimensionKey.OVERWORLD, -1_048_576, 64, 2_097_152)));
    ContextMessage reply = Negotiation.reply(Negotiation.negotiate(ProtocolSupport.CURRENT, hello), active);
    System.out.println("paper: reply " + ProtocolCodec.encodeContext(reply).length + " bytes");
  }
}
