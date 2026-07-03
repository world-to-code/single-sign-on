package com.example.sso.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link IpRules#isAllowed} — the first-match evaluator: rules are walked in order and the
 * first whose CIDR contains the IP decides (ALLOW→permit, BLOCK→deny); no match / empty / null IP → allow.
 * Order (priority) is the caller's; {@code getIpRules()} sorts, so here the lists are already in order.
 */
class IpRulesTest {

    private static IpRuleSpec allow(String cidr, int priority) {
        return new IpRuleSpec(cidr, "ALLOW", priority);
    }

    private static IpRuleSpec block(String cidr, int priority) {
        return new IpRuleSpec(cidr, "BLOCK", priority);
    }

    @Test
    void noRulesAllowsEveryone() {
        assertThat(IpRules.isAllowed(List.of(), "203.0.113.9")).isTrue();
    }

    @Test
    void nullIpIsAllowed() {
        assertThat(IpRules.isAllowed(List.of(block("0.0.0.0/0", 0)), null)).isTrue();
    }

    @Test
    void allowListPermitsInsideAndBlocksOutside() {
        List<IpRuleSpec> rules = List.of(allow("10.0.0.0/8", 0), block("0.0.0.0/0", 1));
        assertThat(IpRules.isAllowed(rules, "10.2.3.4")).isTrue();      // matches the ALLOW first
        assertThat(IpRules.isAllowed(rules, "203.0.113.9")).isFalse();  // falls through to BLOCK-all
    }

    @Test
    void denyListBlocksInsideAndAllowsOutsideByDefault() {
        List<IpRuleSpec> rules = List.of(block("203.0.113.0/24", 0)); // no ALLOW → default allow
        assertThat(IpRules.isAllowed(rules, "203.0.113.9")).isFalse();
        assertThat(IpRules.isAllowed(rules, "10.2.3.4")).isTrue();     // no rule matches → allowed
    }

    @Test
    void orderDecidesAnOverlap() {
        // Block-first: the earlier BLOCK wins even though a later ALLOW also contains the IP.
        assertThat(IpRules.isAllowed(List.of(block("10.5.0.0/16", 0), allow("10.0.0.0/8", 1)), "10.5.1.1")).isFalse();
        // Allow-first: the earlier ALLOW wins (the BLOCK is shadowed).
        assertThat(IpRules.isAllowed(List.of(allow("10.0.0.0/8", 0), block("10.5.0.0/16", 1)), "10.5.1.1")).isTrue();
    }

    @Test
    void anAddressFamilyMismatchCountsAsNonMatch() {
        // An IPv4 rule cannot match an IPv6 client — treated as no match → default allow.
        assertThat(IpRules.isAllowed(List.of(block("10.0.0.0/8", 0)), "2001:db8::1")).isTrue();
    }
}
