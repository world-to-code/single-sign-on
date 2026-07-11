package com.example.sso.auth.internal.factor.application;

import com.example.sso.authpolicy.factor.AuthFactor;
import com.example.sso.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse;
import org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredential;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialCreationOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRequestOptions;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialCreationOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutablePublicKeyCredentialRequestOptionsRequest;
import org.springframework.security.web.webauthn.management.ImmutableRelyingPartyRegistrationRequest;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.RelyingPartyAuthenticationRequest;
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * FIDO2 factor over the SAME passkeys Spring Security stores for passwordless login (one passkey
 * serves both). When the user already has a passkey, prepare()/verify() run an assertion. When they
 * don't, they run a registration ceremony instead (enroll-at-login) — successful registration
 * satisfies the factor, since the WebAuthn ceremony itself proves possession. The controller gates
 * the un-enrolled path behind the session policy's allow-MFA-enrollment-at-login flag, and the
 * factor is only reachable at its policy step, so a passkey can't be planted before prior factors.
 */
@Component
public class Fido2FactorHandler implements FactorHandler {

    private static final Logger log = LoggerFactory.getLogger(Fido2FactorHandler.class);
    private static final String OPTIONS_ATTR = "FIDO2_REQUEST_OPTIONS";
    private static final String CREATION_CHALLENGE_ATTR = "FIDO2_CREATION_CHALLENGE";

    private final WebAuthnRelyingPartyOperations operations;
    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final UserCredentialRepository userCredentials;
    private final JsonMapper json = JsonMapper.builder().addModule(new WebauthnJacksonModule()).build();

    public Fido2FactorHandler(WebAuthnRelyingPartyOperations operations,
                              PublicKeyCredentialUserEntityRepository userEntities,
                              UserCredentialRepository userCredentials) {
        this.operations = operations;
        this.userEntities = userEntities;
        this.userCredentials = userCredentials;
    }

    @Override
    public AuthFactor factor() {
        return AuthFactor.FIDO2;
    }

    @Override
    public boolean isEnrolled(UserAccount user) {
        PublicKeyCredentialUserEntity entity = userEntities.findByUsername(user.getUsername());
        return entity != null && !userCredentials.findByUserId(entity.getId()).isEmpty();
    }

    @Override
    public boolean enrollableAtLogin() {
        return true;
    }

    @Override
    public FactorChallenge prepare(UserAccount user, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(true);

        // The session lives in Redis. PublicKeyCredentialRequestOptions IS Serializable, so we keep the
        // object; PublicKeyCredentialCreationOptions is NOT (and can't be Jackson-deserialized), so for
        // registration we persist only the challenge (Bytes, Serializable) and rebuild the options on verify.
        if (isEnrolled(user)) {
            PublicKeyCredentialRequestOptions options = operations.createCredentialRequestOptions(
                    new ImmutablePublicKeyCredentialRequestOptionsRequest(authentication));
            session.removeAttribute(CREATION_CHALLENGE_ATTR);
            session.setAttribute(OPTIONS_ATTR, options);
            return FactorChallenge.publicKey(json.writeValueAsString(options));
        }

        // No passkey yet -> registration (enroll-at-login). Reachable only when the policy allows it.
        PublicKeyCredentialCreationOptions options = operations.createPublicKeyCredentialCreationOptions(
                new ImmutablePublicKeyCredentialCreationOptionsRequest(authentication));
        session.removeAttribute(OPTIONS_ATTR);
        session.setAttribute(CREATION_CHALLENGE_ATTR, options.getChallenge());
        return FactorChallenge.publicKey(json.writeValueAsString(options));
    }

    @Override
    public boolean verify(UserAccount user, FactorVerificationRequest verification, HttpServletRequest request) {
        if (verification.credential() == null) {
            return false;
        }

        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }

        if (session.getAttribute(CREATION_CHALLENGE_ATTR) instanceof Bytes challenge) {
            return register(user, rebuildCreationOptions(challenge), verification, session);
        }
        if (session.getAttribute(OPTIONS_ATTR) instanceof PublicKeyCredentialRequestOptions options) {
            return assertCredential(user, options, verification, session);
        }
        return false;
    }

    /**
     * Rebuilds the registration options from the stored challenge: re-derives fresh options (rp, user,
     * params — all stable for the same user) and overrides the challenge with the one the browser signed.
     * Lets us persist only the Serializable {@link Bytes} challenge, since the options object is not.
     */
    private PublicKeyCredentialCreationOptions rebuildCreationOptions(Bytes challenge) {
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

    /** Completes a registration ceremony: persists the new passkey; success satisfies the factor. */
    private boolean register(UserAccount user, PublicKeyCredentialCreationOptions options,
                             FactorVerificationRequest verification, HttpSession session) {
        try {
            PublicKeyCredential<AuthenticatorAttestationResponse> credential =
                    json.readValue(verification.credential(), new TypeReference<>() { });
            operations.registerCredential(new ImmutableRelyingPartyRegistrationRequest(
                    options, new RelyingPartyPublicKey(credential, "Passkey")));
            return true;
        } catch (RuntimeException e) {
            log.warn("FIDO2 registration failed for {}: {}", user.getUsername(), e.getMessage());
            return false;
        } finally {
            session.removeAttribute(CREATION_CHALLENGE_ATTR); // single-use challenge
        }
    }

    /** Completes an assertion ceremony against an existing passkey. */
    private boolean assertCredential(UserAccount user, PublicKeyCredentialRequestOptions options,
                                     FactorVerificationRequest verification, HttpSession session) {
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse> credential =
                    json.readValue(verification.credential(), new TypeReference<>() { });
            PublicKeyCredentialUserEntity authenticated =
                    operations.authenticate(new RelyingPartyAuthenticationRequest(options, credential));
            return authenticated != null && user.getUsername().equals(authenticated.getName());
        } catch (RuntimeException e) {
            log.warn("FIDO2 factor verification failed for {}: {}", user.getUsername(), e.getMessage());
            return false;
        } finally {
            session.removeAttribute(OPTIONS_ATTR); // single-use challenge: clear on success OR failure
        }
    }
}
