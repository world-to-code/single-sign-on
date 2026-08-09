package com.example.sso.config;

import com.example.sso.response.ResponseApiTokenFilter;
import com.example.sso.security.TenantHostFilter;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the response API's filters run, and — the part that actually matters — where they do NOT.
 *
 * <p>A {@code @Component} servlet filter is auto-registered on EVERY chain. Were the response token filter
 * declared that way, a machine credential would authenticate against the main application chain too, turning
 * an API scoped to three verbs into a session for the whole console. Nothing about the filter's own logic
 * would look wrong, and no unit test of it could tell: only chain membership can, which is why this is
 * asserted rather than trusted to a comment. The same mistake is already recorded against the SCIM filter.
 *
 * <p>Order is asserted for the same reason: the tenant must be bound from the host BEFORE a token is looked
 * at, because the org that binding produces is what the token's issuer has to agree with.
 */
class ResponseApiChainWiringIT extends AbstractIntegrationTest {

    @Autowired
    FilterChainProxy filterChainProxy;

    @Test
    void theHostBindsTheTenantBeforeTheTokenIsExamined() {
        List<String> filters = filterTypesFor("/api/response/v1/users/x/hold");

        int host = filters.indexOf(TenantHostFilter.class.getName());
        int token = filters.indexOf(ResponseApiTokenFilter.class.getName());

        assertThat(host).as("TenantHostFilter present on the response chain").isGreaterThanOrEqualTo(0);
        assertThat(token).as("ResponseApiTokenFilter present on the response chain").isGreaterThanOrEqualTo(0);
        assertThat(host).as("the tenant is bound before the token is validated").isLessThan(token);
    }

    @Test
    void theResponseTokenFilterDoesNotRunOnTheApplicationChain() {
        assertThat(filterTypesFor("/api/admin/users"))
                .doesNotContain(ResponseApiTokenFilter.class.getName());
    }

    /** Nor on the SCIM chain: two machine credentials, and neither may stand in for the other. */
    @Test
    void theResponseTokenFilterDoesNotRunOnTheScimChain() {
        assertThat(filterTypesFor("/scim/v2/Users"))
                .doesNotContain(ResponseApiTokenFilter.class.getName());
    }

    private List<String> filterTypesFor(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        SecurityFilterChain chain = filterChainProxy.getFilterChains().stream()
                .filter(candidate -> candidate.matches(request))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No security chain matches " + path));
        return chain.getFilters().stream().map(filter -> filter.getClass().getName()).toList();
    }
}
