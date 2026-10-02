// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The negotiation matrix: same, older, and newer peers; unknown capabilities; disjoint versions (an
 * ordinary result, never an exception); and the client's check of the reply.
 */
class NegotiationTest {
  private static final Capability SC = Capability.SEASON_CONTEXT;
  private static final Capability FUTURE = ProtocolFixtures.FUTURE;
  private static final SeasonContext ACTIVE =
      SeasonContext.of(ProtocolFixtures.SEASON, DisplayName.of("S"), 1, List.of());

  private static HelloMessage hello(VersionRange versions, Capability... capabilities) {
    return new HelloMessage(versions, CapabilitySet.of(capabilities), null, Extensions.NONE);
  }

  @Test
  void matchingPeersAgreeOnVersionOneWithTheSeasonContext() {
    Negotiation result = Negotiation.negotiate(ProtocolSupport.CURRENT, HelloMessage.v1("x"));
    assertThat(result).isEqualTo(new Negotiation.Agreed(1, CapabilitySet.of(SC)));
    assertThat(Negotiation.reply(result, ACTIVE))
        .isEqualTo(new ContextMessage.Accepted(1, CapabilitySet.of(SC), ACTIVE, Extensions.NONE));
  }

  @Test
  void aNewerClientGetsTheServersBestVersionAndUnknownCapabilitiesAreIgnored() {
    Negotiation result =
        Negotiation.negotiate(ProtocolSupport.CURRENT, hello(new VersionRange(1, 7), SC, FUTURE));
    assertThat(result).isEqualTo(new Negotiation.Agreed(1, CapabilitySet.of(SC)));
  }

  @Test
  void anOlderClientGetsItsOwnHighestVersion() {
    ProtocolSupport newer =
        new ProtocolSupport(new VersionRange(1, 3), CapabilitySet.of(SC, FUTURE));
    assertThat(Negotiation.negotiate(newer, hello(VersionRange.only(1), SC)))
        .isEqualTo(new Negotiation.Agreed(1, CapabilitySet.of(SC)));
    assertThat(Negotiation.negotiate(newer, hello(new VersionRange(2, 2), SC)))
        .isEqualTo(new Negotiation.Agreed(2, CapabilitySet.of(SC)));
  }

  @Test
  void aCapabilityIsEnabledOnlyIfBothListItAndTheVersionDefinesIt() {
    ProtocolSupport server =
        new ProtocolSupport(new VersionRange(1, 3), CapabilitySet.of(SC, FUTURE));
    // FUTURE is offered by both but defined by no version this implementation knows.
    assertThat(Negotiation.negotiate(server, hello(new VersionRange(1, 3), SC, FUTURE)))
        .isEqualTo(new Negotiation.Agreed(3, CapabilitySet.of(SC)));
    assertThat(ProtocolSupport.sinceVersion(FUTURE)).isZero();
  }

  @Test
  void aClientThatAsksForNothingGetsNoSeason() {
    Negotiation result =
        Negotiation.negotiate(ProtocolSupport.CURRENT, hello(VersionRange.only(1)));
    assertThat(result).isEqualTo(new Negotiation.Agreed(1, CapabilitySet.EMPTY));
    assertThat(Negotiation.reply(result, ACTIVE))
        .isEqualTo(new ContextMessage.Accepted(1, CapabilitySet.EMPTY, null, Extensions.NONE));
  }

  @Test
  void noActiveSeasonIsAnAcceptedReplyWithoutASeason() {
    Negotiation result = Negotiation.negotiate(ProtocolSupport.CURRENT, HelloMessage.v1(null));
    assertThat(Negotiation.reply(result, null))
        .isEqualTo(new ContextMessage.Accepted(1, CapabilitySet.of(SC), null, Extensions.NONE));
  }

  @Test
  void disjointVersionsAreAnIncompatibleResultNotAnException() {
    for (int min = 1; min <= 6; min++) {
      for (int max = min; max <= 6; max++) {
        VersionRange client = new VersionRange(min, max);
        for (int smin = 1; smin <= 6; smin++) {
          for (int smax = smin; smax <= 6; smax++) {
            VersionRange server = new VersionRange(smin, smax);
            Negotiation result =
                Negotiation.negotiate(
                    new ProtocolSupport(server, CapabilitySet.of(SC)), hello(client, SC));
            boolean overlap = Math.max(min, smin) <= Math.min(max, smax);
            if (overlap) {
              assertThat(result)
                  .isEqualTo(new Negotiation.Agreed(Math.min(max, smax), CapabilitySet.of(SC)));
            } else {
              assertThat(result).isEqualTo(new Negotiation.Incompatible(server, client));
              assertThat(Negotiation.reply(result, ACTIVE))
                  .isEqualTo(new ContextMessage.Incompatible(server, Extensions.NONE));
            }
          }
        }
      }
    }
  }

  @Test
  void theClientRejectsAReplyThatContradictsItsHello() throws ProtocolException {
    HelloMessage sent = HelloMessage.v1(null);
    ContextMessage good =
        new ContextMessage.Accepted(1, CapabilitySet.of(SC), ACTIVE, Extensions.NONE);
    assertThat(Negotiation.checkReply(sent, good)).isSameAs(good);
    ContextMessage incompatible =
        new ContextMessage.Incompatible(VersionRange.only(2), Extensions.NONE);
    assertThat(Negotiation.checkReply(sent, incompatible)).isSameAs(incompatible);
    assertThatThrownBy(
            () ->
                Negotiation.checkReply(
                    sent,
                    new ContextMessage.Accepted(2, CapabilitySet.EMPTY, null, Extensions.NONE)))
        .isInstanceOfSatisfying(
            ProtocolException.class,
            e -> assertThat(e.code()).isEqualTo(ProtocolIssueCodes.UNEXPECTED_REPLY));
    assertThatThrownBy(
            () ->
                Negotiation.checkReply(
                    sent,
                    new ContextMessage.Accepted(
                        1, CapabilitySet.of(SC, FUTURE), null, Extensions.NONE)))
        .isInstanceOfSatisfying(
            ProtocolException.class,
            e -> assertThat(e.code()).isEqualTo(ProtocolIssueCodes.UNEXPECTED_REPLY));
  }

  @Test
  void theHelloCarriesNoServerAuthoritativeState() {
    // Root AGENTS.md §15: no client message may set a season, origin, unlock, cost, or destination.
    // Adding a field to the hello must be a deliberate, reviewed change to this list.
    assertThat(
            Arrays.stream(HelloMessage.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly("versions", "capabilities", "clientVersion", "extensions");
  }

  @Test
  void aSeasonWithoutTheCapabilityCannotBeBuilt() {
    assertThatThrownBy(
            () -> new ContextMessage.Accepted(1, CapabilitySet.EMPTY, ACTIVE, Extensions.NONE))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
