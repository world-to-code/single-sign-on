package com.example.sso.session.internal.application;

import com.example.sso.session.SessionLifecycle;
import com.example.sso.session.UserSessions;
import com.example.sso.session.SessionMetadata;
import com.example.sso.session.SessionMetadataStore;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.web.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

/**
 * Implements both session roles ({@link SessionLifecycle} + {@link UserSessions}): bridges the servlet
 * session, Spring's {@link SessionRegistry}, and
 * the {@link SessionMetadataStore}. Our custom JSON login flow does not run Spring's
 * {@code ConcurrentSessionControlAuthenticationStrategy}, so registration and overflow expiry are done
 * here explicitly; the {@code SessionIntegrityFilter} enforces the resulting expiry on the next request.
 */
@Service
@RequiredArgsConstructor
public class SessionManagerImpl implements SessionLifecycle, UserSessions {

    private final SessionRegistry sessionRegistry;
    private final SessionMetadataStore sessionMetadata;
    private final SessionPolicyService sessionPolicy;

    @Override
    public void registerAndEnforceLimit(HttpServletRequest request, String username) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        if (sessionRegistry.getSessionInformation(session.getId()) == null) {
            sessionRegistry.registerNewSession(session.getId(), username);
        }

        // Stamp device metadata for the self-service "My Profile" sessions list (single-node, in-memory).
        sessionMetadata.record(session.getId(), username, request.getHeader(HttpHeaders.USER_AGENT), ClientIp.of(request));
        SessionPolicyDetails policy = sessionPolicy.resolveForUsername(username);
        int max = policy.getMaxConcurrentSessions();
        if (max <= 0) {
            return; // 0 = unlimited
        }

        List<SessionInformation> active = new ArrayList<>(sessionRegistry.getAllSessions(username, false));
        if (active.size() <= max) {
            return;
        }

        active.sort(Comparator.comparing(SessionInformation::getLastRequest)); // oldest first
        active.stream().limit(active.size() - (long) max).forEach(SessionInformation::expireNow);
    }

    @Override
    public void rotateSessionId(HttpServletRequest request, String username) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        String oldId = session.getId();
        boolean tracked = sessionRegistry.getSessionInformation(oldId) != null;
        String newId = request.changeSessionId();
        if (!oldId.equals(newId)) {
            if (tracked) {
                sessionRegistry.removeSessionInformation(oldId);
                sessionRegistry.registerNewSession(newId, username);
            }
            // changeSessionId() fires sessionIdChanged (not sessionDestroyed), so the cleanup listener
            // never drops the old-id entry — re-key the device metadata to the new id ourselves, else the
            // current session disappears from "My Profile" and the old entry leaks.
            sessionMetadata.rekey(oldId, newId);
        }
    }

    @Override
    public List<SessionMetadata> liveSessions(HttpServletRequest request, String username) {
        HttpSession current = request.getSession(false);
        String currentId = current == null ? null : current.getId();

        // Guarantee the caller's CURRENT session is always tracked + shown: backfill the registry +
        // metadata if missing (e.g. an in-memory restart cleared the store, or it was never recorded).
        if (currentId != null) {
            if (sessionRegistry.getSessionInformation(currentId) == null) {
                sessionRegistry.registerNewSession(currentId, username);
            }
            boolean tracked = sessionMetadata.forUser(username).stream()
                    .anyMatch(m -> m.sessionId().equals(currentId));
            if (!tracked) {
                sessionMetadata.record(currentId, username, request.getHeader(HttpHeaders.USER_AGENT), ClientIp.of(request));
            }
        }

        return sessionMetadata.forUser(username).stream()
                .filter(this::isLive) // hide sessions the registry has expired/forgotten
                .toList();
    }

    @Override
    public void revoke(String username, String handle) {
        SessionMetadata target = sessionMetadata.findByUserAndHandle(username, handle)
                .orElseThrow(() -> new NotFoundException("session not found"));

        SessionInformation info = sessionRegistry.getSessionInformation(target.sessionId());
        if (info != null) {
            info.expireNow(); // SessionIntegrityFilter rejects + invalidates it on the next request
        }

        sessionMetadata.remove(target.sessionId());
    }

    private boolean isLive(SessionMetadata metadata) {
        SessionInformation info = sessionRegistry.getSessionInformation(metadata.sessionId());
        return info != null && !info.isExpired();
    }
}
