package com.example.sso.webauthn.internal.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * A {@link WebAuthnRelyingPartyOperations} that derives the RP ID from the CURRENT request's host per ceremony
 * (see {@link WebAuthnRpIdResolver}), so a single bean serves the bare platform host and every tenant subdomain.
 * The RP ID is baked into both the options sent to the browser (registration/assertion) AND the
 * {@code ServerProperty} the underlying operations verify the attestation/assertion against, so registration and
 * a later authentication must — and do — resolve the SAME RP ID from the same tenant host; a credential is thus
 * scoped to the host family it was created at.
 *
 * <p>The underlying per-RP-ID operations are built once and cached — the set of hosts is small and bounded (the
 * base domains plus the tenant subdomains in use) — and each shares the passkey stores and the tenant-aware
 * allowed-origins set, so this decorator adds only the per-request host lookup.
 */
final class TenantAwareRelyingPartyOperations implements WebAuthnRelyingPartyOperations {

    private final WebAuthnRpIdResolver rpIds;
    private final Function<String, WebAuthnRelyingPartyOperations> operationsFactory;
    private final Map<String, WebAuthnRelyingPartyOperations> byRpId = new ConcurrentHashMap<>();

    TenantAwareRelyingPartyOperations(WebAuthnRpIdResolver rpIds,
            Function<String, WebAuthnRelyingPartyOperations> operationsFactory) {
        this.rpIds = rpIds;
        this.operationsFactory = operationsFactory;
    }

    @Override
    public PublicKeyCredentialCreationOptions createPublicKeyCredentialCreationOptions(
            PublicKeyCredentialCreationOptionsRequest request) {
        return current().createPublicKeyCredentialCreationOptions(request);
    }

    @Override
    public CredentialRecord registerCredential(RelyingPartyRegistrationRequest request) {
        return current().registerCredential(request);
    }

    @Override
    public PublicKeyCredentialRequestOptions createCredentialRequestOptions(
            PublicKeyCredentialRequestOptionsRequest request) {
        return current().createCredentialRequestOptions(request);
    }

    @Override
    public PublicKeyCredentialUserEntity authenticate(RelyingPartyAuthenticationRequest request) {
        return current().authenticate(request);
    }

    private WebAuthnRelyingPartyOperations current() {
        return byRpId.computeIfAbsent(rpIds.rpIdForHost(currentHost()), operationsFactory);
    }

    private String currentHost() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getHeader("Host")
                : null;
    }
}
