package com.example.sso.saml.internal.logout.application;

import com.example.sso.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SAML SLO participant index against a real Redis, focused on clear-only-delivered: settling a subset of
 * SPs removes exactly those and reports the remainder, and settling the last one drops the whole mapping.
 */
class SamlSloSessionIndexIT extends AbstractIntegrationTest {

    @Autowired
    SamlSloSessionIndex index;

    private final String sid = "sid-" + UUID.randomUUID();

    @Test
    void removingASubsetKeepsTheRest() {
        index.record(sid, "sp-a", "nameid-a");
        index.record(sid, "sp-b", "nameid-b");

        int remaining = index.removeParticipants(sid, Set.of("sp-a"));

        assertThat(remaining).isEqualTo(1);
        assertThat(index.lookup(sid)).containsExactly(Map.entry("sp-b", "nameid-b"));
    }

    @Test
    void removingTheLastSpDropsTheWholeMapping() {
        index.record(sid, "sp-a", "nameid-a");

        int remaining = index.removeParticipants(sid, Set.of("sp-a"));

        assertThat(remaining).isZero();
        assertThat(index.lookup(sid)).isEmpty();
    }

    @Test
    void removingAnEmptySetReportsTheCurrentCountWithoutChange() {
        index.record(sid, "sp-a", "nameid-a");
        index.record(sid, "sp-b", "nameid-b");

        int remaining = index.removeParticipants(sid, Set.of());

        assertThat(remaining).isEqualTo(2);
        assertThat(index.lookup(sid)).hasSize(2);
    }
}
