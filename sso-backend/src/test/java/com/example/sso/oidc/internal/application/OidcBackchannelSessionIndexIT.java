package com.example.sso.oidc.internal.application;

import com.example.sso.oidc.OidcBackchannelSessionIndex;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OIDC back-channel participant index against a real Redis, focused on clear-only-delivered: settling a
 * subset of clients must remove exactly those, keep the subject while any client remains (a give-up audit
 * needs it), and drop the whole mapping only when the last client is settled.
 */
class OidcBackchannelSessionIndexIT extends AbstractIntegrationTest {

    @Autowired
    OidcBackchannelSessionIndex index;

    private final String sid = "sid-" + UUID.randomUUID();

    @Test
    void removingASubsetKeepsTheRestAndTheSubject() {
        index.record(sid, "client-a", "bob");
        index.record(sid, "client-b", "bob");

        int remaining = index.removeParticipants(sid, Set.of("client-a"));

        assertThat(remaining).isEqualTo(1);
        OidcBackchannelSessionIndex.Participants after = index.lookup(sid);
        assertThat(after.registeredClientIds()).containsExactly("client-b");
        assertThat(after.username()).isEqualTo("bob"); // retained while a client remains
    }

    @Test
    void removingTheLastClientDropsTheWholeMapping() {
        index.record(sid, "client-a", "bob");

        int remaining = index.removeParticipants(sid, Set.of("client-a"));

        assertThat(remaining).isZero();
        OidcBackchannelSessionIndex.Participants after = index.lookup(sid);
        assertThat(after.registeredClientIds()).isEmpty();
        assertThat(after.username()).isNull(); // subject dropped with the last client
    }

    @Test
    void removingAnEmptySetReportsTheCurrentCountWithoutChange() {
        index.record(sid, "client-a", "bob");
        index.record(sid, "client-b", "bob");

        int remaining = index.removeParticipants(sid, Set.of());

        assertThat(remaining).isEqualTo(2);
        assertThat(index.lookup(sid).registeredClientIds()).containsExactlyInAnyOrder("client-a", "client-b");
    }
}
