package com.example.sso.response;

import com.example.sso.organization.NewOrganization;
import com.example.sso.organization.OrganizationService;
import com.example.sso.response.internal.api.ResponseController;
import com.example.sso.response.internal.api.ResponseHoldRequest;
import com.example.sso.response.internal.application.ResponseHoldView;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.support.AbstractIntegrationTest;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.NewUser;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The response verbs, driven through the controller so each one's scope is part of what is tested.
 *
 * <p>Two properties carry this feature and both are asserted here rather than assumed.
 *
 * <p><b>A scope is a capability, not a label.</b> A credential that can end sessions must not be able to
 * place a hold, and one that can place a hold must not be able to lift it — that separation is the entire
 * reason there are three scopes rather than one, and it is invisible unless the negative case is asserted.
 *
 * <p><b>A user id says nothing about whose user it is.</b> {@code app_user} is not RLS-confined, so the
 * tenant check is code, and code that is not tested is a cross-tenant response away from being wrong. The
 * refusal is a 404 so a caller cannot use it to discover that an id exists in some other tenant.
 */
class ResponseActionsIT extends AbstractIntegrationTest {

    @Autowired
    ResponseController controller;
    @Autowired
    AccountHoldService holds;
    @Autowired
    UserService users;
    @Autowired
    OrganizationService organizations;
    @Autowired
    OrgContext orgContext;

    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        orgContext.clear();
        for (int i = cleanups.size() - 1; i >= 0; i--) {
            cleanups.get(i).run();
        }
        cleanups.clear();
    }

    @Test
    void aHoldPlacedByAResponseClientTakesEffect() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.HOLD);

        controller.placeHold(target.getId(), new ResponseHoldRequest("impossible travel", 60));

        assertThat(holds.holdInEffect(target.getId())).isPresent();
    }

    /** The correlation id is what joins the hold to the detection that decided it. It must reach the row. */
    @Test
    void theHoldCarriesTheCallersCorrelationId() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.HOLD);

        controller.placeHold(target.getId(), new ResponseHoldRequest("impossible travel", 60));

        ResponseHoldView view = controller.hold(target.getId());
        assertThat(view.held()).isTrue();
        assertThat(view.correlationId()).isEqualTo("xdr-42");
    }

    @Test
    void anAccountWithNoHoldReadsBackAsNotHeld() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.HOLD);

        assertThat(controller.hold(target.getId()).held()).isFalse();
    }

    @Test
    void liftingReleasesTheAccount() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.HOLD, ResponseScopes.HOLD_LIFT);
        controller.placeHold(target.getId(), new ResponseHoldRequest("false positive", 60));

        controller.liftHold(target.getId());

        assertThat(holds.holdInEffect(target.getId())).isEmpty();
    }

    @Test
    void endingSessionsReportsHowManyItEnded() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.SESSION_TERMINATE);

        assertThat(controller.terminateSessions(target.getId()).sessions()).isZero();
    }

    /** The separation the three scopes exist for: holding is not lifting, and neither is ending sessions. */
    @Test
    void aClientThatMayHoldMayNotLift() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.HOLD);

        assertThatThrownBy(() -> controller.liftHold(target.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aClientThatMayEndSessionsMayNotHold() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.SESSION_TERMINATE);

        assertThatThrownBy(() -> controller.placeHold(target.getId(), new ResponseHoldRequest("nope", 60)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The tenant boundary. Every verb takes a bare id, so each one is asked separately — a guard added to the
     * hold path and forgotten on the session path is precisely the shape this has to rule out.
     */
    @Test
    void aResponseClientCannotReachAnotherTenantsUser() {
        UUID mine = tenant();
        UUID theirs = tenant();
        UserAccount stranger = userIn(theirs);
        asResponseClient(mine, ResponseScopes.HOLD, ResponseScopes.HOLD_LIFT, ResponseScopes.SESSION_TERMINATE);

        assertThatThrownBy(() -> controller.hold(stranger.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.placeHold(stranger.getId(), new ResponseHoldRequest("x", 60)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.liftHold(stranger.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.terminateSessions(stranger.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThat(holds.holdInEffect(stranger.getId())).isEmpty();
    }

    /** A tenant's response client must not reach a GLOBAL account either — that is the platform tier. */
    @Test
    void aTenantsResponseClientCannotReachAGlobalAccount() {
        UUID mine = tenant();
        UserAccount global = globalUser();
        asResponseClient(mine, ResponseScopes.HOLD);

        assertThatThrownBy(() -> controller.placeHold(global.getId(), new ResponseHoldRequest("x", 60)))
                .isInstanceOf(NotFoundException.class);
    }

    /** The ceiling holds for a machine exactly as it does for a person — a hold is never a disable. */
    @Test
    void aHoldBeyondTheCeilingIsRefused() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.HOLD);

        assertThatThrownBy(() -> controller.placeHold(target.getId(),
                new ResponseHoldRequest("forever", 60 * 24 * 400)))
                .isInstanceOf(BadRequestException.class);
    }

    /** Every response action is on the trail, attributed to the machine and joined to its detection. */
    @Test
    void endingSessionsIsRecordedAgainstTheServicePrincipalWithTheCorrelationId() {
        UUID org = tenant();
        UserAccount target = userIn(org);
        asResponseClient(org, ResponseScopes.SESSION_TERMINATE);

        controller.terminateSessions(target.getId());

        String detail = ownerJdbc().queryForObject(
                "select detail from audit_event where type = 'SESSION_ADMIN_REVOKED' and principal = ? "
                        + "and subject_id = ?",
                String.class, ResponseApiTokenFilter.RESPONSE_PRINCIPAL, target.getId().toString());
        assertThat(detail).contains("correlation=xdr-42");
    }

    /**
     * Stands in for the filter: the host binds the tenant, the token supplies the scopes. Driving the two
     * separately is what lets the tenant boundary be tested independently of how a token is validated.
     */
    private void asResponseClient(UUID org, String... scopes) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ResponseApiTokenFilter.CORRELATION_HEADER, "xdr-42");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        new ResponseCorrelation().bind("xdr-42"); // package-private, same package — the filter's job
        orgContext.bindOrg(org);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                ResponseApiTokenFilter.RESPONSE_PRINCIPAL, null, authorities));
    }

    private UUID tenant() {
        UUID org = organizations.create(new NewOrganization("resp-" + suffix(), "resp")).id();
        cleanups.add(() -> orgContext.runAsPlatform(
                () -> ownerJdbc().update("delete from organization where id = ?", org)));
        return org;
    }

    private UserAccount userIn(UUID org) {
        String username = "resp-" + suffix();
        UserAccount account = orgContext.callInOrg(org, () -> users.createUser(
                new NewUser(username, username + "@example.com", "Resp", "S3cret!pw", Set.of()), org));
        cleanups.add(() -> orgContext.runAsPlatform(() -> users.delete(account.getId())));
        return account;
    }

    private UserAccount globalUser() {
        String username = "resp-global-" + suffix();
        UserAccount account = users.createUser(
                new NewUser(username, username + "@example.com", "Global", "S3cret!pw", Set.of()));
        cleanups.add(() -> orgContext.runAsPlatform(() -> users.delete(account.getId())));
        return account;
    }

    private String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
