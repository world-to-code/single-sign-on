package com.example.sso.config;

import com.example.sso.portal.AppAssignmentFilter;
import com.example.sso.portal.AppStepUpFilter;
import com.example.sso.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the Model B entry gate: {@link AppAssignmentFilter} must live INSIDE the
 * authorization-server {@code SecurityFilterChain} that serves {@code /oauth2/authorize} (the endpoint
 * filter commits the response and never reaches the outer servlet chain, so an auto-registered
 * {@code @Component} filter would silently never run). This asserts the filter is in that chain AND
 * ordered before {@link AppStepUpFilter}. A unit test of the filter's logic cannot catch mis-wiring —
 * only chain membership can.
 */
class AuthorizeChainWiringIT extends AbstractIntegrationTest {

    @Autowired
    FilterChainProxy filterChainProxy;

    @Test
    void appAssignmentFilterRunsInsideTheAuthorizeChainBeforeStepUp() {
        MockHttpServletRequest authorize = new MockHttpServletRequest("GET", "/oauth2/authorize");
        authorize.setServletPath("/oauth2/authorize");

        SecurityFilterChain chain = filterChainProxy.getFilterChains().stream()
                .filter(c -> c.matches(authorize))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No security chain matches /oauth2/authorize"));

        List<String> filterTypes = chain.getFilters().stream().map(f -> f.getClass().getName()).toList();
        int assignmentIdx = filterTypes.indexOf(AppAssignmentFilter.class.getName());
        int stepUpIdx = filterTypes.indexOf(AppStepUpFilter.class.getName());

        assertThat(assignmentIdx).as("AppAssignmentFilter present in the /oauth2/authorize chain").isGreaterThanOrEqualTo(0);
        assertThat(stepUpIdx).as("AppStepUpFilter present in the /oauth2/authorize chain").isGreaterThanOrEqualTo(0);
        assertThat(assignmentIdx).as("assignment gate runs before step-up").isLessThan(stepUpIdx);
    }
}
