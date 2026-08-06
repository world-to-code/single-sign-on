package com.example.sso.webauthn.internal.application;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records, after a passkey assertion succeeds, whether the credential used was a SYNCED one — so the session
 * can later report RFC 8176 {@code swk} rather than claiming a hardware key it never saw.
 *
 * <p>A decorator, and deliberately a second one rather than a few lines inside the tenant-routing decorator it
 * wraps: that class answers "which RP ID is this ceremony for", this one answers "what did the ceremony
 * prove". They change for different reasons.
 *
 * <p>It sits here because this is where BOTH ways of using a passkey meet — the passwordless login filter and
 * the FIDO2 step-up factor both authenticate through these operations, and a fact recorded on only one of them
 * is a claim that is right half the time.
 */
final class AssuranceRecordingRelyingPartyOperations implements WebAuthnRelyingPartyOperations {

    private final WebAuthnRelyingPartyOperations delegate;
    private final UserCredentialRepository credentials;
    private final SessionPasskeyAssurance assurance;

    AssuranceRecordingRelyingPartyOperations(WebAuthnRelyingPartyOperations delegate,
            UserCredentialRepository credentials, SessionPasskeyAssurance assurance) {
        this.delegate = delegate;
        this.credentials = credentials;
        this.assurance = assurance;
    }

    @Override
    public PublicKeyCredentialCreationOptions createPublicKeyCredentialCreationOptions(
            PublicKeyCredentialCreationOptionsRequest request) {
        return delegate.createPublicKeyCredentialCreationOptions(request);
    }

    @Override
    public CredentialRecord registerCredential(RelyingPartyRegistrationRequest request) {
        return delegate.registerCredential(request);
    }

    @Override
    public PublicKeyCredentialRequestOptions createCredentialRequestOptions(
            PublicKeyCredentialRequestOptionsRequest request) {
        return delegate.createCredentialRequestOptions(request);
    }

    @Override
    public PublicKeyCredentialUserEntity authenticate(RelyingPartyAuthenticationRequest request) {
        // Only after the delegate accepts it: a refused assertion proved nothing, and recording one would let a
        // failed ceremony leave an assurance behind for whatever runs next.
        PublicKeyCredentialUserEntity authenticated = delegate.authenticate(request);
        currentRequest().ifPresent(servletRequest -> assurance.record(servletRequest, isSoftwareBacked(request)));
        return authenticated;
    }

    /**
     * A credential the store cannot resolve counts as software-backed. It cannot happen behind a verified
     * assertion — the delegate looked the same credential up to check the signature — but the fallback still
     * has to pick a side, and "we could not tell" is not evidence of hardware.
     */
    private boolean isSoftwareBacked(RelyingPartyAuthenticationRequest request) {
        CredentialRecord stored = credentials.findByCredentialId(request.getPublicKey().getRawId());
        return stored == null || stored.isBackupEligible();
    }

    private Optional<HttpServletRequest> currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? Optional.of(attributes.getRequest())
                : Optional.empty();
    }
}
