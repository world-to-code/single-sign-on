package com.example.sso.session.internal.application;

import com.example.sso.session.SessionLifecycle;
import com.example.sso.session.UserSessions;
import com.example.sso.session.SessionMetadata;
import com.example.sso.session.SessionMetadataStore;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.user.UserAccessChangedEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Override
    public void registerAndEnforceLimit(HttpServletRequest request, String username) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        // Spring Session registers + principal-indexes the session automatically (it holds the security
        // context), so we don't register here — the backing registry queries Redis by principal.
        sessionMetadata.record(session.getId(), username, request.getHeader(HttpHeaders.USER_AGENT), ClientIp.of(request));
        SessionPolicyDetails policy = sessionPolicy.resolveForUsername(username);
        int max = policy.getMaxConcurrentSessions();
        if (max <= 0) {
            return; // 0 = unlimited
        }

        List<SessionInformation> active = new ArrayList<>(sessionRegistry.getAllSessions(username, false));
        // Count the CURRENT session even if Spring Session hasn't flushed it to the Redis principal index
        // yet — a login that completes in the same request that created the session (a single-step policy)
        // wouldn't otherwise be counted, letting max+1 through.
        boolean currentCounted = active.stream().anyMatch(si -> session.getId().equals(si.getSessionId()));
        long total = active.size() + (currentCounted ? 0 : 1);
        if (total <= max) {
            return;
        }

        // Evict the oldest over the cap. The current session (newest, or not in the list) is never evicted.
        active.sort(Comparator.comparing(SessionInformation::getLastRequest)); // oldest first
        active.stream().limit(total - max).forEach(SessionInformation::expireNow);
    }

    @Override
    public void rotateSessionId(HttpServletRequest request, String username) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        String oldId = session.getId();
        String newId = request.changeSessionId();
        if (!oldId.equals(newId)) {
            // Spring Session re-keys the Redis session + its principal index on changeSessionId(); we only
            // re-key our own device-metadata map (keyed by the old id) so "My Profile" keeps the session.
            sessionMetadata.rekey(oldId, newId);
        }
    }

    @Override
    public List<SessionMetadata> liveSessions(HttpServletRequest request, String username) {
        HttpSession current = request.getSession(false);
        String currentId = current == null ? null : current.getId();

        // Guarantee the caller's CURRENT session is always shown: backfill device metadata if missing
        // (e.g. a restart cleared the in-memory store, or it was never recorded). Spring Session already
        // tracks the session itself in the registry, so only the metadata needs backfilling.
        if (currentId != null) {
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

    @Override
    public int terminateAll(String username) {
        List<SessionInformation> active = sessionRegistry.getAllSessions(username, false);
        // Hard-delete each Redis session rather than expireNow() (which only marks it EXPIRED, deferring
        // deletion to the victim's next request). Deletion fires a keyspace notification -> SessionDeletedEvent
        // -> OIDC back-channel logout / SAML SLO + the metadata cleanup listener, so downstream apps are
        // logged out promptly instead of only when the user returns or the idle/absolute TTL lapses.
        active.forEach(info -> sessionRepository.deleteById(info.getSessionId()));
        return active.size();
    }

    /** When a user is disabled/deleted/re-roled, end their live sessions so a frozen SecurityContext can't
     *  keep acting on stale authorities until idle/absolute expiry. Runs AFTER_COMMIT so a rolled-back
     *  mutation never terminates sessions (fallbackExecution covers publishers outside a transaction). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAccessChanged(UserAccessChangedEvent event) {
        terminateAll(event.username());
    }

    private boolean isLive(SessionMetadata metadata) {
        SessionInformation info = sessionRegistry.getSessionInformation(metadata.sessionId());
        return info != null && !info.isExpired();
    }
}
