package com.example.sso.saml.internal.sso.application;

import com.example.sso.saml.internal.core.application.SamlBindingCodec;
import com.example.sso.saml.internal.core.application.SamlEntityId;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.portal.access.AppAccess;
import com.example.sso.portal.access.AppAccessQuery;
import com.example.sso.portal.stepup.AppStepUpFilter;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.saml.internal.logout.application.SamlSloSessionIndex;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.shared.error.UnauthorizedException;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.opensaml.saml.saml2.core.Response;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

/**
 * Core SAML SSO decision: enforces the target app's per-app step-up policy for the authenticated user
 * and, when satisfied, builds + signs the SAML {@code Response} and audits the sign-on. Returns a
 * {@link SamlSsoOutcome} for the controller to render (keeping HTTP/binding concerns out of here).
 */
@Service
@RequiredArgsConstructor
public class SamlSsoService {

    private final UserService users;
    private final ApplicationService applications;
    private final SamlResponseBuilder responseBuilder;
    private final SamlBindingCodec codec;
    private final SamlSloSessionIndex sloIndex;
    private final SamlEntityId samlEntityId;
    private final AuditService audit;

    public SamlSsoOutcome process(SamlRelyingParty relyingParty, String inResponseTo, String relayState,
                                  Authentication authentication, HttpServletRequest httpRequest) {
        UserAccount user = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Unknown user"));
        String rpId = relyingParty.getId().toString();

        // Per-app step-up: this app may require extra factors beyond the base login.
        Set<String> granted = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith(Factors.FACTOR_PREFIX))
                .collect(Collectors.toSet());
        AppAccess access = applications.appAccess(new AppAccessQuery(user, AppType.SAML, rpId, granted,
                AppStepUpFilter.lastAppStepUp(httpRequest.getSession(false), AppType.SAML, rpId)));

        if (!access.ready()) {
            if (HttpMethod.GET.matches(httpRequest.getMethod())) {
                primePendingLaunch(httpRequest, rpId);
                return new SamlSsoOutcome.StepUpRedirect();
            }
            audit.record(new AuditRecord(AuditType.SAML_STEPUP_REQUIRED, user.getUsername(), false,
                    "sp=" + relyingParty.getEntityId(), null));
            return new SamlSsoOutcome.StepUpForbidden();
        }

        // The OP session id (shared with OIDC via the SID_ marker) doubles as the SAML SessionIndex, and
        // records this SP as a logout participant so session termination can send it a LogoutRequest.
        String sid = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(Factors.SID_PREFIX))
                .map(a -> a.substring(Factors.SID_PREFIX.length()))
                .findFirst().orElse(null);

        // The organization (tenant) this session logged into, from the shared ORG_ marker — same source as the
        // OIDC `org` claim, so the SAML `org` attribute is symmetric. Null for a global (org-less) session.
        String org = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(Factors.ORG_PREFIX))
                .map(a -> a.substring(Factors.ORG_PREFIX.length()))
                .findFirst().orElse(null);

        // The IdP entityID follows the host the SP reached, matching the tenant's own signing credential.
        Response response = responseBuilder.issueResponse(relyingParty, inResponseTo,
                new AssertionSubject(user.getEmail(), user.getDisplayName(), org), sid,
                samlEntityId.resolve(httpRequest));
        String encoded = codec.encode(response);
        if (sid != null) {
            sloIndex.record(sid, relyingParty.getEntityId(), user.getEmail());
        }
        String flow = inResponseTo == null ? " (idp-initiated)" : "";
        audit.record(new AuditRecord(AuditType.SAML_SSO_ISSUED, user.getUsername(), true,
                "sp=" + relyingParty.getEntityId() + flow, null));
        return new SamlSsoOutcome.Issued(relyingParty.getAcsUrl(), encoded, relayState);
    }

    /** Stores the pending SAML launch in the session so /stepup can return to it once factors are met. */
    private void primePendingLaunch(HttpServletRequest httpRequest, String rpId) {
        HttpSession session = httpRequest.getSession(true);
        String query = httpRequest.getQueryString();
        session.setAttribute(AppStepUpFilter.RETURN, httpRequest.getRequestURI() + (query != null ? "?" + query : ""));
        session.setAttribute(AppStepUpFilter.APP_TYPE, AppType.SAML.name());
        session.setAttribute(AppStepUpFilter.APP_ID, rpId);
    }
}
