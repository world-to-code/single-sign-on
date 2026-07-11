package com.example.sso.auth.internal.session.application;

import com.example.sso.auth.internal.login.application.CurrentUserProvider;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.session.lifecycle.DeviceLabeler;
import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.user.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** The signed-in user's own active sessions: list (with device labels) and self-revoke by handle. */
@Service
@RequiredArgsConstructor
public class SessionSelfService {

    private final CurrentUserProvider currentUser;
    private final UserSessions sessions;
    private final DeviceLabeler deviceLabeler;
    private final AuditService audit;

    public List<SessionDeviceView> list(HttpServletRequest request) {
        UserAccount user = currentUser.requireMfaComplete();
        HttpSession current = request.getSession(false);
        String currentId = current == null ? null : current.getId();

        return sessions.liveSessions(request, user.getUsername()).stream()
                .map(m -> new SessionDeviceView(m.handle(), m.sessionId().equals(currentId),
                        deviceLabeler.label(m.userAgent()), m.userAgent(), m.ip(), m.createdAt(), m.lastSeenAt()))
                .toList();
    }

    /**
     * Revokes one of the caller's OWN sessions by its opaque public handle (scoped to the user, so
     * another user's session is never reachable and the real session id is never exposed).
     */
    public void revoke(String handle) {
        UserAccount user = currentUser.requireMfaComplete();
        sessions.revoke(user.getUsername(), handle);
        audit.record(new AuditRecord(AuditType.SESSION_REVOKED, user.getUsername(), true, "handle=" + handle, null));
    }
}
