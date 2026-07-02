package com.example.sso.saml.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.authpolicy.Factors;
import com.example.sso.portal.AppAccess;
import com.example.sso.portal.AppAccessQuery;
import com.example.sso.portal.AppStepUpFilter;
import com.example.sso.portal.AppType;
import com.example.sso.portal.ApplicationService;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
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

        Response response = responseBuilder.issueResponse(
                relyingParty, inResponseTo, user.getEmail(), user.getDisplayName());
        String encoded = codec.encode(response);
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
