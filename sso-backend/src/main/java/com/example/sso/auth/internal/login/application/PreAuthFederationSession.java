package com.example.sso.auth.internal.login.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Holds the one federated-login request's server-side state across the redirect to the upstream and back: the
 * provider alias, the OAuth {@code state} (matched against the callback to bind the request and defeat CSRF /
 * cross-login injection), the {@code nonce} (validated inside the id_token), the PKCE {@code codeVerifier}, and
 * the exact {@code redirectUri} sent (re-sent verbatim at the token exchange). Lives in the pre-auth HTTP
 * session — there is no {@code Authentication} yet — beside {@link PreAuthOrgSession}, and is cleared once the
 * callback consumes it (single use).
 */
@Component
public class PreAuthFederationSession {

    private static final String ORG_ID = "FED_ORG_ID";
    private static final String ALIAS = "FED_ALIAS";
    private static final String STATE = "FED_STATE";
    private static final String NONCE = "FED_NONCE";
    private static final String VERIFIER = "FED_CODE_VERIFIER";
    private static final String REDIRECT_URI = "FED_REDIRECT_URI";

    void stash(HttpServletRequest request, UUID orgId, String alias, String state, String nonce, String codeVerifier,
            String redirectUri) {
        HttpSession session = request.getSession(true);
        session.setAttribute(ORG_ID, orgId.toString());
        session.setAttribute(ALIAS, alias);
        session.setAttribute(STATE, state);
        session.setAttribute(NONCE, nonce);
        session.setAttribute(VERIFIER, codeVerifier);
        session.setAttribute(REDIRECT_URI, redirectUri);
    }

    Optional<PendingFederation> pending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        String orgId = (String) session.getAttribute(ORG_ID);
        String alias = (String) session.getAttribute(ALIAS);
        String state = (String) session.getAttribute(STATE);
        String nonce = (String) session.getAttribute(NONCE);
        String verifier = (String) session.getAttribute(VERIFIER);
        String redirectUri = (String) session.getAttribute(REDIRECT_URI);
        if (orgId == null || alias == null || state == null || nonce == null || verifier == null
                || redirectUri == null) {
            return Optional.empty();
        }
        return Optional.of(new PendingFederation(UUID.fromString(orgId), alias, state, nonce, verifier, redirectUri));
    }

    /** Single use: a redeemed (or abandoned) federation challenge is cleared so a code cannot be replayed. */
    void clear(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(ORG_ID);
            session.removeAttribute(ALIAS);
            session.removeAttribute(STATE);
            session.removeAttribute(NONCE);
            session.removeAttribute(VERIFIER);
            session.removeAttribute(REDIRECT_URI);
        }
    }

    /** The stashed values a callback needs to validate and complete the flow, pinned to the org it started under. */
    record PendingFederation(UUID orgId, String alias, String state, String nonce, String codeVerifier,
            String redirectUri) {
    }
}
