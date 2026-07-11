package com.example.sso.session;

import com.example.sso.session.networkzone.IpRuleSpec;
import com.example.sso.session.networkzone.IpRules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link IpRules#isAllowed} — the first-match, zone-resolving evaluator. Rules reference a
 * network zone; a rule matches if ANY of its zone's CIDRs contains the IP. The first matching rule (in
 * priority order) decides; no match / empty / null IP → allow. IPv4-zone-vs-IPv6-client counts as non-match.
 */
class IpRulesTest {

    private static final UUID OFFICE = UUID.randomUUID();      // 192.168.0.0/16 + 10.0.0.0/8
    private static final UUID CLOUD = UUID.randomUUID();       // 3.34.0.0/16
    private static final UUID EVERYWHERE = UUID.randomUUID();  // 0.0.0.0/0 + ::/0

    private final Function<UUID, List<String>> zones = id -> Map.of(
            OFFICE, List.of("192.168.0.0/16", "10.0.0.0/8"),
            CLOUD, List.of("3.34.0.0/16"),
            EVERYWHERE, List.of("0.0.0.0/0", "::/0")
    ).getOrDefault(id, List.of());

    private static IpRuleSpec allow(UUID zone, int priority) {
        return new IpRuleSpec(zone.toString(), "ALLOW", priority);
    }

    private static IpRuleSpec block(UUID zone, int priority) {
        return new IpRuleSpec(zone.toString(), "BLOCK", priority);
    }

    @Test
    void noRulesAllowsEveryone() {
        assertThat(IpRules.isAllowed(List.of(), zones, "203.0.113.9")).isTrue();
    }

    @Test
    void nullIpIsAllowed() {
        assertThat(IpRules.isAllowed(List.of(block(EVERYWHERE, 0)), zones, null)).isTrue();
    }

    @Test
    void allowListPermitsInsideAndBlocksOutside() {
        List<IpRuleSpec> rules = List.of(allow(OFFICE, 0), block(EVERYWHERE, 1));
        assertThat(IpRules.isAllowed(rules, zones, "10.2.3.4")).isTrue();      // in OFFICE → allowed first
        assertThat(IpRules.isAllowed(rules, zones, "203.0.113.9")).isFalse();  // falls to EVERYWHERE block
    }

    @Test
    void aMultiCidrZoneMatchesAnyOfItsRanges() {
        List<IpRuleSpec> rules = List.of(allow(OFFICE, 0), block(EVERYWHERE, 1));
        assertThat(IpRules.isAllowed(rules, zones, "192.168.5.5")).isTrue(); // OFFICE's SECOND CIDR matches
    }

    @Test
    void denyListBlocksInsideAndAllowsOutsideByDefault() {
        List<IpRuleSpec> rules = List.of(block(CLOUD, 0)); // no ALLOW → default allow
        assertThat(IpRules.isAllowed(rules, zones, "3.34.1.1")).isFalse();
        assertThat(IpRules.isAllowed(rules, zones, "10.2.3.4")).isTrue(); // no rule matches → allowed
    }

    @Test
    void orderDecidesAnOverlap() {
        // Block-first: the earlier EVERYWHERE block wins even though OFFICE also contains the IP.
        assertThat(IpRules.isAllowed(List.of(block(EVERYWHERE, 0), allow(OFFICE, 1)), zones, "10.2.3.4")).isFalse();
        // Allow-first: the earlier OFFICE allow wins (block shadowed).
        assertThat(IpRules.isAllowed(List.of(allow(OFFICE, 0), block(EVERYWHERE, 1)), zones, "10.2.3.4")).isTrue();
    }

    @Test
    void aBlockRuleWhoseZoneResolvesToNoCidrsFailsClosed() {
        // Defense in depth: if a BLOCK rule's zone cannot be resolved (unknown id / stale cache), we cannot
        // prove the client is OUTSIDE the blocked range — refuse rather than silently skipping the rule.
        UUID ghost = UUID.randomUUID(); // resolver returns List.of() for it
        assertThat(IpRules.isAllowed(List.of(block(ghost, 0)), zones, "10.2.3.4")).isFalse();
    }

    @Test
    void anAllowRuleWhoseZoneResolvesToNoCidrsIsSkippedNotGranted() {
        // An unresolvable ALLOW must not admit anyone; evaluation falls through to the next rule.
        UUID ghost = UUID.randomUUID();
        assertThat(IpRules.isAllowed(List.of(allow(ghost, 0), block(EVERYWHERE, 1)), zones, "10.2.3.4")).isFalse();
        // ...and with no later rule, the default allow applies (the ALLOW itself grants nothing extra).
        assertThat(IpRules.isAllowed(List.of(allow(ghost, 0)), zones, "10.2.3.4")).isTrue();
    }

    @Test
    void ipv6IsCoveredByAnIpv6CatchAllButNotByAnIpv4Zone() {
        // ::/0 in EVERYWHERE catches an IPv6 client...
        assertThat(IpRules.isAllowed(List.of(block(EVERYWHERE, 0)), zones, "2001:db8::1")).isFalse();
        // ...but an IPv4-only zone cannot match an IPv6 client → no match → default allow.
        assertThat(IpRules.isAllowed(List.of(block(OFFICE, 0)), zones, "2001:db8::1")).isTrue();
    }
}
