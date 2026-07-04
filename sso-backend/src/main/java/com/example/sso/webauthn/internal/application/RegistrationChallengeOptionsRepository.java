package com.example.sso.webauthn.internal.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.registration.PublicKeyCredentialCreationOptionsRepository;

/**
 * Persists a passkey-registration ceremony across requests when the session lives in Redis. Spring's
 * default {@code HttpSessionPublicKeyCredentialCreationOptionsRepository} stores the whole
 * {@link PublicKeyCredentialCreationOptions}, but that type is not {@code Serializable} (and can't be
 * Jackson-deserialized), so JDK serialization to Redis fails and registration breaks. Instead we store
 * only the challenge ({@link Bytes}, Serializable) and rebuild the options on load by re-deriving fresh
 * options (rp/user/params are stable for the same user) and overriding the challenge the browser signed.
 */
public class RegistrationChallengeOptionsRepository implements PublicKeyCredentialCreationOptionsRepository {

    private static final String CHALLENGE_ATTR = "webauthn.registration.challenge";

    private final WebAuthnRelyingPartyOperations operations;

    public RegistrationChallengeOptionsRepository(WebAuthnRelyingPartyOperations operations) {
        this.operations = operations;
    }

    @Override
    public void save(HttpServletRequest request, HttpServletResponse response,
                     PublicKeyCredentialCreationOptions options) {
        HttpSession session = request.getSession();
        if (options == null) {
            session.removeAttribute(CHALLENGE_ATTR);
        } else {
            session.setAttribute(CHALLENGE_ATTR, options.getChallenge());
        }
    }

    @Override
    public PublicKeyCredentialCreationOptions load(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(CHALLENGE_ATTR) instanceof Bytes challenge)) {
            return null;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        PublicKeyCredentialCreationOptions fresh = operations.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication));
        return PublicKeyCredentialCreationOptions.builder()
                .rp(fresh.getRp()).user(fresh.getUser()).challenge(challenge)
                .pubKeyCredParams(fresh.getPubKeyCredParams()).timeout(fresh.getTimeout())
                .excludeCredentials(fresh.getExcludeCredentials())
                .authenticatorSelection(fresh.getAuthenticatorSelection())
                .attestation(fresh.getAttestation()).extensions(fresh.getExtensions())
                .build();
    }
}
