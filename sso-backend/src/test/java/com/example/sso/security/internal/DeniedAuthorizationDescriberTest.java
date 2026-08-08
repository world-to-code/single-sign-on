package com.example.sso.security.internal;

import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ExpressionAuthorizationDecision;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a denial is allowed to say about itself.
 *
 * <p>The audit trail records every refused authorization, and until now it recorded only WHO was refused —
 * an investigator reading it sees "ada was denied something, somewhere". Spring hands the listener both the
 * thing being protected and the check that failed; this turns those into a line worth reading.
 *
 * <p><b>The risk this class exists to contain is the opposite one.</b> The protected object for method
 * security is a {@link MethodInvocation}, and in this codebase its arguments carry passwords, one-time
 * codes, tokens and assertion XML. Describing a denial by its arguments — or by anything's
 * {@code toString()}, which reaches them transitively — would put credentials in the audit trail, where
 * they are retained and widely readable. So the describer may name the METHOD and no more, and the test
 * below asserts that directly rather than trusting the implementation to stay careful.
 *
 * <p>The second risk is volume: denials fire on every anonymous hit to a protected URL, and the path is
 * attacker-controlled. The description is capped, and the query string — the part most likely to carry a
 * token — is dropped entirely.
 */
class DeniedAuthorizationDescriberTest {

    private final DeniedAuthorizationDescriber describer = new DeniedAuthorizationDescriber();

    private MethodInvocation invocationOf(Class<?> type, String method) throws Exception {
        Method target = type.getDeclaredMethod(method, String.class);
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(target);
        return invocation;
    }

    /** Stand-in for a secured service; the parameter is what must never be described. */
    @SuppressWarnings("unused")
    static class SecretHoldingService {
        void changePassword(String password) {
        }
    }

    @Test
    void aMethodDenialNamesTheMethodItProtected() throws Exception {
        DeniedAuthorization denied = describer.describe(invocationOf(SecretHoldingService.class, "changePassword"),
                new AuthorizationDecision(false));

        assertThat(denied.target()).isEqualTo("SecretHoldingService.changePassword");
    }

    /**
     * The whole point of the class. A describer that reached for the arguments — or called {@code toString()}
     * on the invocation, which reaches them — would write the password into a retained audit row.
     */
    @Test
    void aMethodDenialNeverDescribesTheArguments() throws Exception {
        MethodInvocation invocation = invocationOf(SecretHoldingService.class, "changePassword");

        DeniedAuthorization denied = describer.describe(invocation, new AuthorizationDecision(false));

        assertThat(denied.target()).doesNotContain("hunter2");
        assertThat(denied.failedCheck()).isNull();
        org.mockito.Mockito.verify(invocation, org.mockito.Mockito.never()).getArguments();
    }

    /** A {@code @PreAuthorize} denial carries the expression that refused it — the most useful single fact. */
    @Test
    void anExpressionDenialRecordsTheExpressionThatRefused() throws Exception {
        ExpressionAuthorizationDecision result = new ExpressionAuthorizationDecision(false,
                new SpelExpressionParser().parseExpression("hasAuthority('user:delete')"));

        DeniedAuthorization denied = describer.describe(
                invocationOf(SecretHoldingService.class, "changePassword"), result);

        assertThat(denied.failedCheck()).isEqualTo("hasAuthority('user:delete')");
    }

    @Test
    void aUrlDenialNamesTheMethodAndPath() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");

        DeniedAuthorization denied = describer.describe(request, new AuthorizationDecision(false));

        assertThat(denied.target()).isEqualTo("GET /api/admin/users");
    }

    /** A query string is where a token ends up, and it is never needed to know what was refused. */
    @Test
    void aUrlDenialDropsTheQueryString() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/users");
        request.setQueryString("token=secret&page=2");

        DeniedAuthorization denied = describer.describe(request, new AuthorizationDecision(false));

        assertThat(denied.target()).doesNotContain("secret").doesNotContain("token");
    }

    @Test
    void aRequestAuthorizationContextIsUnwrappedToItsRequest() {
        RequestAuthorizationContext context =
                new RequestAuthorizationContext(new MockHttpServletRequest("POST", "/api/admin/roles"));

        DeniedAuthorization denied = describer.describe(context, new AuthorizationDecision(false));

        assertThat(denied.target()).isEqualTo("POST /api/admin/roles");
    }

    /**
     * An unrecognized protected object degrades to its TYPE. Calling {@code toString()} on it is the exact
     * mistake this class exists to avoid, because an unknown object is precisely the one whose contents are
     * unknown.
     */
    @Test
    void anUnknownProtectedObjectIsDescribedByItsTypeOnly() {
        DeniedAuthorization denied = describer.describe(new SecretCarrier("hunter2"),
                new AuthorizationDecision(false));

        assertThat(denied.target()).isEqualTo("SecretCarrier");
        assertThat(denied.target()).doesNotContain("hunter2");
    }

    private record SecretCarrier(String password) {
        @Override
        public String toString() {
            return "SecretCarrier[password=" + password + "]";
        }
    }

    @Test
    void aDenialWithNoProtectedObjectStillDescribesSomething() {
        DeniedAuthorization denied = describer.describe(null, new AuthorizationDecision(false));

        assertThat(denied.target()).isNotBlank();
        assertThat(denied.failedCheck()).isNull();
    }

    /** A denial can arrive with no result at all; describing it must not be the thing that throws. */
    @Test
    void aDenialWithNoResultDoesNotFail() {
        DeniedAuthorization denied = describer.describe(new MockHttpServletRequest("GET", "/x"), null);

        assertThat(denied.target()).isEqualTo("GET /x");
        assertThat(denied.failedCheck()).isNull();
    }

    /** The path is attacker-controlled and the column is unbounded text; a denial is not a storage vector. */
    @Test
    void aRidiculousPathIsCapped() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/" + "x".repeat(5000));

        DeniedAuthorization denied = describer.describe(request, new AuthorizationDecision(false));

        assertThat(denied.target().length()).isLessThanOrEqualTo(DeniedAuthorizationDescriber.MAX_TARGET_LENGTH);
    }
}
