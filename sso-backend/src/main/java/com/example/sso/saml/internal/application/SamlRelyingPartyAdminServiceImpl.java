package com.example.sso.saml.internal.application;

import com.example.sso.portal.ApplicationDeletedEvent;
import com.example.sso.saml.RelyingPartyRequest;
import com.example.sso.saml.RelyingPartyView;
import com.example.sso.saml.internal.domain.SamlRelyingParty;
import com.example.sso.saml.SamlRelyingPartyAdminService;
import com.example.sso.saml.internal.domain.SamlRelyingPartyRepository;
import com.example.sso.saml.internal.domain.SamlSecuritySettings;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.ConflictException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.tenancy.OrgTierGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

/** Default {@link SamlRelyingPartyAdminService}: admin CRUD for SAML relying parties. */
@Service
@RequiredArgsConstructor
public class SamlRelyingPartyAdminServiceImpl implements SamlRelyingPartyAdminService {

    private final SamlRelyingPartyRepository relyingParties;
    private final ApplicationEventPublisher events;
    private final OrgContext orgContext;
    private final OrgTierGuard tierGuard;

    @Override
    @Transactional(readOnly = true)
    public Page<RelyingPartyView> list(int page, int size) {
        UUID tier = tierGuard.currentTier();
        List<SamlRelyingParty> rows = tier == null
                ? relyingParties.findAllByOrgIdIsNull()
                : relyingParties.findAllByOrgId(tier);
        List<RelyingPartyView> all = rows.stream().map(this::toView).toList();
        return Page.of(all, page, size);
    }

    @Override
    @Transactional
    public RelyingPartyView create(RelyingPartyRequest request) {
        // entityId is globally unique across tenants; check cross-org (RLS would otherwise hide another
        // tenant's row and let a duplicate slip through) before stamping the RP with the caller's tier.
        if (orgContext.callAsPlatform(() -> relyingParties.existsByEntityId(request.entityId()))) {
            throw new ConflictException("a relying party with that entityId already exists");
        }

        SamlRelyingParty rp = new SamlRelyingParty(request.entityId(), request.acsUrl(), nameIdFormat(request),
                tierGuard.currentTier());
        rp.update(request.displayName(), request.acsUrl(), nameIdFormat(request), settings(request),
                trimToNull(request.signingCertificate()), trimToNull(request.encryptionCertificate()),
                trimToNull(request.spLoginUrl()), trimToNull(request.singleLogoutUrl()), request.sloBinding());
        return toView(relyingParties.save(rp));
    }

    @Override
    @Transactional
    public RelyingPartyView update(UUID id, RelyingPartyRequest request) {
        SamlRelyingParty rp = tierGuard.requireInTier(relyingParties.findById(id),
                () -> new NotFoundException("relying party not found"));

        rp.update(request.displayName(), request.acsUrl(), nameIdFormat(request), settings(request),
                trimToNull(request.signingCertificate()), trimToNull(request.encryptionCertificate()),
                trimToNull(request.spLoginUrl()), trimToNull(request.singleLogoutUrl()), request.sloBinding());
        return toView(relyingParties.save(rp));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        SamlRelyingParty rp = tierGuard.requireInTier(relyingParties.findById(id),
                () -> new NotFoundException("relying party not found"));

        relyingParties.delete(rp);
        events.publishEvent(new ApplicationDeletedEvent(id.toString()));
    }

    @Override
    @Transactional
    public void ensureRelyingParty(String entityId, String acsUrl) {
        if (relyingParties.existsByEntityId(entityId)) {
            return;
        }

        relyingParties.save(new SamlRelyingParty(entityId, acsUrl, SamlRelyingParty.NAMEID_EMAIL));
    }

    private SamlSecuritySettings settings(RelyingPartyRequest r) {
        return new SamlSecuritySettings(r.signAssertion(), r.signResponse(), r.encryptAssertion(),
                orDefault(r.signatureAlgorithm(), "RSA_SHA256"),
                orDefault(r.dataEncryptionAlgorithm(), "AES256_GCM"),
                orDefault(r.keyTransportAlgorithm(), "RSA_OAEP"),
                r.wantAuthnRequestsSigned(), r.allowIdpInitiated());
    }

    private String nameIdFormat(RelyingPartyRequest r) {
        return StringUtils.hasText(r.nameIdFormat()) ? r.nameIdFormat() : SamlRelyingParty.NAMEID_EMAIL;
    }

    private String orDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private RelyingPartyView toView(SamlRelyingParty rp) {
        return new RelyingPartyView(rp.getId().toString(), rp.getEntityId(), rp.getDisplayName(),
                rp.getAcsUrl(), rp.getNameIdFormat(),
                rp.isSignAssertion(), rp.isSignResponse(), rp.isEncryptAssertion(),
                rp.getSignatureAlgorithm(), rp.getDataEncryptionAlgorithm(), rp.getKeyTransportAlgorithm(),
                rp.isWantAuthnRequestsSigned(), rp.isAllowIdpInitiated(),
                rp.getSigningCertificate(), rp.getEncryptionCertificate(), rp.getSpLoginUrl(),
                rp.getSingleLogoutUrl(), rp.sloBinding());
    }
}
