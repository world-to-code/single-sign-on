package com.example.sso.saml.internal.logout.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.portal.application.AppSessionParticipation;
import com.example.sso.portal.application.AppSessionSource;
import com.example.sso.portal.application.AppType;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingParty;
import com.example.sso.saml.internal.relyingparty.domain.SamlRelyingPartyRepository;
import com.example.sso.saml.relyingparty.SloBinding;
import com.example.sso.shared.error.BadRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Publishes a user's live SAML SP sessions to the portal and logs ONE SP out from the IdP (goal ③). Only a
 * SOAP-binding SP with an SLO endpoint is one-click capable: a front-channel SP cannot be logged out
 * browser-less, so it is listed but its {@code logout} is refused (the button is disabled client-side too).
 * Resolves RPs under the CALLER's own context (RLS backstops cross-tenant reach); signing stays per-tenant.
 */
@Component
@RequiredArgsConstructor
class SamlAppSessionSource implements AppSessionSource {

    private final SamlSloSessionIndex index;
    private final SamlRelyingPartyRepository relyingParties;
    private final SamlParticipantDelivery delivery;
    private final AuditService audit;

    @Override
    public AppType type() {
        return AppType.SAML;
    }

    @Override
    public List<AppSessionParticipation> participationsFor(Set<String> sids) {
        List<AppSessionParticipation> participations = new ArrayList<>();
        for (String sid : sids) {
            for (String entityId : index.lookup(sid).keySet()) {
                relyingParties.findByEntityId(entityId).ifPresent(rp -> {
                    String name = StringUtils.hasText(rp.getDisplayName()) ? rp.getDisplayName() : entityId;
                    participations.add(new AppSessionParticipation(AppType.SAML, entityId, sid, name,
                            oneClickCapable(rp)));
                });
            }
        }
        return participations;
    }

    @Override
    public void logout(String sid, String entityId, String username) {
        Map<String, String> participants = index.lookup(sid);
        String nameId = participants.get(entityId);
        if (nameId == null) {
            return; // not held under this sid (already logged out) — nothing to do
        }
        SamlRelyingParty rp = relyingParties.findByEntityId(entityId)
                .orElseThrow(() -> new BadRequestException("Unknown application."));
        if (!oneClickCapable(rp)) {
            // Defense in depth: the button is disabled client-side, but a front-channel-only SP can never be
            // logged out on the browser-less back-channel — refuse rather than silently claim success.
            throw new BadRequestException("This application can only be signed out from the application itself.");
        }
        boolean delivered = delivery.sendSoap(rp, nameId, sid);
        audit.record(new AuditRecord(AuditType.SAML_SLO, username, delivered,
                "sp=" + entityId + " self-service per-app logout", null));
        // Clear ONLY on delivery — like the whole-session fan-out. A transient SOAP failure keeps the SP in the
        // index (it stays in the viewer) so a later whole-session SLO re-drives it; never drop a revocation.
        if (delivered) {
            index.removeParticipants(sid, Set.of(entityId));
        }
    }

    private boolean oneClickCapable(SamlRelyingParty rp) {
        return rp.sloBinding() == SloBinding.SOAP && StringUtils.hasText(rp.getSingleLogoutUrl());
    }
}
