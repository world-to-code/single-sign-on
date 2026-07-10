package com.example.sso.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin console's network gate. It is the only thing standing between a stolen elevation token and the
 * admin API from an arbitrary network, so its edges are pinned: an empty allowlist means any network, a
 * populated one admits only a matching CIDR, and anything unparsable (or of the wrong address family) never
 * matches — never the reverse.
 */
class AdminConsoleNetworkTest {

    @Test
    void anEmptyAllowlistPermitsAnyNetwork() {
        assertThat(AdminConsoleNetwork.allows(null, "203.0.113.7")).isTrue();
        assertThat(AdminConsoleNetwork.allows("", "203.0.113.7")).isTrue();
        assertThat(AdminConsoleNetwork.allows("   ", "203.0.113.7")).isTrue();
    }

    @Test
    void aPopulatedAllowlistAdmitsOnlyAMatchingAddress() {
        assertThat(AdminConsoleNetwork.allows("203.0.113.0/24", "203.0.113.7")).isTrue();
        assertThat(AdminConsoleNetwork.allows("203.0.113.0/24", "198.51.100.7")).isFalse();
    }

    @Test
    void anyEntryOfTheListMayMatch() {
        assertThat(AdminConsoleNetwork.allows("10.0.0.0/8, 203.0.113.0/24", "203.0.113.7")).isTrue();
        assertThat(AdminConsoleNetwork.allows(" 10.0.0.0/8 ,203.0.113.0/24 ", "10.1.2.3")).isTrue();
        assertThat(AdminConsoleNetwork.allows("10.0.0.0/8,203.0.113.0/24", "192.0.2.1")).isFalse();
    }

    @Test
    void anUnparsableCidrNeverMatches() {
        // Fail closed: a typo in the allowlist must lock the console down, never open it.
        assertThat(AdminConsoleNetwork.allows("garbage", "203.0.113.7")).isFalse();
        assertThat(AdminConsoleNetwork.allows("garbage,203.0.113.0/24", "203.0.113.7")).isTrue();
    }

    @Test
    void aRuleOfTheWrongAddressFamilyDoesNotMatch() {
        assertThat(AdminConsoleNetwork.allows("203.0.113.0/24", "2001:db8::1")).isFalse();
        assertThat(AdminConsoleNetwork.allows("2001:db8::/32", "203.0.113.7")).isFalse();
        assertThat(AdminConsoleNetwork.allows("2001:db8::/32", "2001:db8::1")).isTrue();
    }
}
