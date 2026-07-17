package com.example.sso.saml.internal.logout.application;

import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Participant;
import com.example.sso.saml.internal.logout.application.SamlLogoutChainStore.Responder;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The front-channel logout chain store against a real Redis. Focus: the SP-initiated responder context —
 * it must round-trip (so the initiator gets its LogoutResponse at the end), be absent for a browser logout,
 * and be removed on clear (so the initiator's request id / RelayState do not leak past the chain).
 */
class SamlLogoutChainStoreIT extends AbstractIntegrationTest {

    @Autowired
    SamlLogoutChainStore store;

    private final String logoutId = "L-" + UUID.randomUUID();
    private final String sid = "sid-" + UUID.randomUUID();

    @Test
    void theResponderContextRoundTrips() {
        store.create(logoutId, sid, List.of(new Participant("sp-b", "nameid-b")),
                new Responder("https://initiator", "req-9", "relay-1"));

        assertThat(store.responder(logoutId))
                .contains(new Responder("https://initiator", "req-9", "relay-1"));
    }

    @Test
    void aResponderWithoutRelayStateRoundTripsWithNullRelay() {
        store.create(logoutId, sid, List.of(new Participant("sp-b", "nameid-b")),
                new Responder("https://initiator", "req-9", null));

        Responder responder = store.responder(logoutId).orElseThrow();
        assertThat(responder.entityId()).isEqualTo("https://initiator");
        assertThat(responder.requestId()).isEqualTo("req-9");
        assertThat(responder.relayState()).isNull();
    }

    @Test
    void aBrowserLogoutChainHasNoResponder() {
        store.create(logoutId, sid, List.of(new Participant("sp-a", "nameid-a")), null);

        assertThat(store.responder(logoutId)).isEmpty();
    }

    @Test
    void clearRemovesTheResponderSoItsRequestIdAndRelayStateDoNotLeak() {
        store.create(logoutId, sid, List.of(new Participant("sp-b", "nameid-b")),
                new Responder("https://initiator", "req-9", "relay-1"));

        store.clear(logoutId);

        assertThat(store.responder(logoutId)).isEmpty();
    }

    @Test
    void hopsArePoppedInOrderAndDrainToEmpty() {
        store.create(logoutId, sid, List.of(new Participant("sp-a", "n-a"), new Participant("sp-b", "n-b")), null);

        assertThat(store.next(logoutId)).get().extracting(SamlLogoutChainStore.Hop::entityId).isEqualTo("sp-a");
        assertThat(store.next(logoutId)).get().extracting(SamlLogoutChainStore.Hop::entityId).isEqualTo("sp-b");
        assertThat(store.next(logoutId)).isEmpty();
    }
}
