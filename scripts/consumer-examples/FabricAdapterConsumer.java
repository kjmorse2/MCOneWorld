// SPDX-License-Identifier: MIT
// Consumer example: what the Fabric adapter (Java 25) needs, with no Fabric types: build the hello,
// check the reply, and show season-local coordinates only where an origin exists.
// Compiled and run by scripts/check-consumer-examples.sh; not part of the Gradle build.

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.protocol.ContextMessage;
import io.github.kjmorse2.mconeworld.protocol.HelloMessage;
import io.github.kjmorse2.mconeworld.protocol.Negotiation;
import io.github.kjmorse2.mconeworld.protocol.ProtocolException;
import io.github.kjmorse2.mconeworld.protocol.ProtocolSupport;
import io.github.kjmorse2.mconeworld.protocol.SeasonContext;
import io.github.kjmorse2.mconeworld.protocol.codec.ProtocolCodec;
import java.util.List;
import java.util.UUID;

public final class FabricAdapterConsumer {
  public static void main(String[] args) throws Exception {
    HelloMessage sent = HelloMessage.v1("0.0.0-example");
    byte[] helloBytes = ProtocolCodec.encodeHello(sent);
    // Stand-in for the server: the bytes it would send back.
    SeasonContext season = SeasonContext.of(
        SeasonId.of(UUID.fromString("5ea50001-0000-4000-8000-000000000001")), DisplayName.of("Season 1"), 1,
        List.of(new DimensionOrigin(DimensionKey.OVERWORLD, -1_048_576, 64, 2_097_152)));
    byte[] contextBytes = ProtocolCodec.encodeContext(Negotiation.reply(
        Negotiation.negotiate(ProtocolSupport.CURRENT, ProtocolCodec.decodeHello(helloBytes)), season));

    SeasonContext context = null;
    try {
      ContextMessage reply = Negotiation.checkReply(sent, ProtocolCodec.decodeContext(contextBytes));
      if (reply instanceof ContextMessage.Accepted accepted) {
        context = accepted.season();
      }
    } catch (ProtocolException e) {
      System.out.println("fabric: ignored context " + e.code().value());
    }
    for (DimensionKey dimension : List.of(DimensionKey.OVERWORLD, DimensionKey.THE_END)) {
      BlockPosition block = new BlockPosition(dimension, -1_048_500, 70, 2_097_000);
      DimensionOrigin origin = context == null ? null : context.origins().get(dimension);
      System.out.println("fabric: " + dimension + " absolute=" + block
          + (origin == null ? " (no origin: absolute only)" : " local=" + origin.toLocal(block)));
    }
  }
}
