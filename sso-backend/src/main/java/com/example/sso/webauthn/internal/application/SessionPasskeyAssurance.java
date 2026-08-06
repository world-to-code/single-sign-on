package com.example.sso.webauthn.internal.application;

import com.example.sso.webauthn.PasskeyAssurance;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * Keeps the passkey kind on the SESSION, because the request that learns it is not the request that needs it.
 *
 * <p>Written through the ceremony's own request so it survives the session-id rotation that follows a
 * successful login: {@code changeSessionId} keeps the session and its attributes, it only re-keys them.
 */
@Component
public class SessionPasskeyAssurance implements PasskeyAssurance {

    private static final String ATTRIBUTE = "PASSKEY_SOFTWARE_BACKED";

    void record(HttpServletRequest request, boolean softwareBacked) {
        request.getSession().setAttribute(ATTRIBUTE, softwareBacked);
    }

    @Override
    public boolean lastAssertionWasSoftwareBacked(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(ATTRIBUTE));
    }
}
