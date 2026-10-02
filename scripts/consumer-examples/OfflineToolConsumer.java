// SPDX-License-Identifier: MIT
// Consumer example: what the offline tool (Java 21) needs from the shared jars, read-only.
// Compiled and run by scripts/check-consumer-examples.sh; not part of the Gradle build.

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.coordinate.OriginAlignment;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStatus;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.storage.RecoveryReport;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import java.nio.file.Path;
import java.time.Clock;

public final class OfflineToolConsumer {
  public static void main(String[] args) throws Exception {
    SidecarStore store = SidecarStore.open(SidecarRoot.open(Path.of(args[0])));
    RecoveryReport recovery = store.inspect();
    System.out.println("offline: manifest=" + store.hasManifest() + " clean=" + recovery.isClean());
    ChunkIndexStatus index = ChunkIndexStore.of(store).inspect();
    System.out.println("offline: index usable=" + index.usable() + " findings=" + index.findings().issues().size());
    System.out.println("offline: operations=" + OperationJournals.of(store, Clock.systemUTC()).list().size());
    DimensionOrigin origin = new DimensionOrigin(DimensionKey.OVERWORLD, -1_048_576, 64, 2_097_152);
    BlockPosition block = new BlockPosition(DimensionKey.OVERWORLD, -1_048_577, 70, 2_097_152);
    System.out.println("offline: local=" + origin.toLocal(block) + " chunk=" + block.chunk()
        + " aligned=" + OriginAlignment.recommendedFor(origin).isAligned());
  }
}
