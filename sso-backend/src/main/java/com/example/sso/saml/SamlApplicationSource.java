package com.example.sso.saml;

import com.example.sso.portal.AppAssignment.AppType;
import com.example.sso.portal.ApplicationDescriptor;
import com.example.sso.portal.ApplicationSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Publishes SAML relying parties as launchable applications. Launch prefers the SP's own
 * SP-initiated login URL (the SP then sends us an AuthnRequest — the standard, secure flow),
 * falling back to IdP-initiated (unsolicited) SSO when none is configured.
 */
@Component
@RequiredArgsConstructor
public class SamlApplicationSource implements ApplicationSource {

    private final SamlRelyingPartyRepository relyingParties;

    @Override
    public List<ApplicationDescriptor> applications() {
        return relyingParties.findAll().stream()
                .map(rp -> new ApplicationDescriptor(AppType.SAML, rp.getId().toString(), rp.getEntityId(),
                        launchUrl(rp), false))
                .toList();
    }

    private static String launchUrl(SamlRelyingParty rp) {
        if (StringUtils.hasText(rp.getSpLoginUrl())) {
            return rp.getSpLoginUrl().trim();
        }
        return "/saml2/idp/sso/init?sp=" + UriUtils.encodeQueryParam(rp.getEntityId(), StandardCharsets.UTF_8);
    }
}
