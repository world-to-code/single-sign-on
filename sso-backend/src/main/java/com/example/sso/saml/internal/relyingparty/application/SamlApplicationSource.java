package com.example.sso.saml.internal.relyingparty.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationDescriptor;
import com.example.sso.portal.application.ApplicationSource;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.tenancy.OrgTierGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Publishes SAML relying parties as launchable applications. Launch prefers the SP's own
 * SP-initiated login URL (the SP then sends us an AuthnRequest — the standard, secure flow),
 * falling back to IdP-initiated (unsolicited) SSO when none is configured.
 */
@Component
@RequiredArgsConstructor
public class SamlApplicationSource implements ApplicationSource {

    private final SamlRelyingPartyRepository relyingParties;
    private final OrgTierGuard tierGuard;

    @Override
    public List<ApplicationDescriptor> applications() {
        // Scope to the acting tier, symmetric with OidcApplicationSource/ClientAdminService: the platform
        // (no org bound) sees global RPs; a tenant sees only its OWN org's RPs — never another tenant's, and
        // not the global fixtures. Explicit code-level scoping, not merely RLS, is the app catalog's guard.
        UUID org = tierGuard.currentTier();
        List<SamlRelyingParty> rps = org == null
                ? relyingParties.findAllByOrgIdIsNull()
                : relyingParties.findAllByOrgId(org);
        return rps.stream()
                .map(rp -> new ApplicationDescriptor(AppType.SAML, rp.getId().toString(),
                        StringUtils.hasText(rp.getDisplayName()) ? rp.getDisplayName() : rp.getEntityId(),
                        launchUrl(rp), false))
                .toList();
    }

    private String launchUrl(SamlRelyingParty rp) {
        if (StringUtils.hasText(rp.getSpLoginUrl())) {
            return rp.getSpLoginUrl().trim();
        }

        return "/saml2/idp/sso/init?sp=" + UriUtils.encodeQueryParam(rp.getEntityId(), StandardCharsets.UTF_8);
    }
}
