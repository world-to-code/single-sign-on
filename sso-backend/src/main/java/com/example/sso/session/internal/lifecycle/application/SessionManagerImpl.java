package com.example.sso.session.internal.lifecycle.application;

import com.example.sso.authpolicy.factor.Factors;
import com.example.sso.organization.OrganizationAccessRevokedEvent;
import com.example.sso.session.lifecycle.SessionLifecycle;
import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.session.lifecycle.SessionMetadata;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.session.policy.UserSessionPolicy;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.shared.web.ClientIp;
import com.example.sso.user.account.UserAccessChangedEvent;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
    private final UserSessionPolicy userSessionPolicy;
    private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;
    private final UserService users;

    @Override
    public void registerAndEnforceLimit(HttpServletRequest request, String username) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }

        // Spring Session registers + principal-indexes the session automatically (it holds the security
        // context), so we don't register here — the backing registry queries Redis by principal.
        sessionMetadata.record(session.getId(), username, request.getHeader(HttpHeaders.USER_AGENT), ClientIp.of(request));
        // The cap is a FLOOR across every policy governing the user — the most-restrictive non-zero limit — so a
        // narrow policy with a looser cap cannot lift a broad org-wide one.
        int max = userSessionPolicy.maxConcurrentSessionsFor(username);
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
        active.stream().limit(total - max).forEach(this::hardDelete);
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
            hardDelete(info); // deletes the Redis session -> downstream BCL/SLO logout, not just a mark
        }

        sessionMetadata.remove(target.sessionId());
    }

    @Override
    public int terminateForUser(String username, UUID orgId) {
        Set<String> ids = orgScopedSessionIds(username, orgId);
        ids.forEach(sessionRepository::deleteById); // fires SessionDeletedEvent -> BCL/SLO + metadata cleanup
        return ids.size();
    }

    @Override
    public Set<String> sessionIdsForUser(String username, UUID orgId) {
        return orgScopedSessionIds(username, orgId);
    }

    /**
     * The ids of {@code username}'s sessions that belong to org {@code orgId}: those carrying the
     * {@code ORG_<orgId>} marker, or — for a global (null) account — those carrying NO org marker at all.
     * Usernames are unique only within an org, so this scoping keeps a same-named user in another tenant out.
     */
    private Set<String> orgScopedSessionIds(String username, UUID orgId) {
        String marker = orgId == null ? null : Factors.ORG_PREFIX + orgId;
        Set<String> ids = new HashSet<>();
        sessionRepository.findByPrincipalName(username).forEach((sessionId, session) -> {
            if (marker == null ? !hasOrgMarker(session) : isBoundToOrg(session, marker)) {
                ids.add(sessionId);
            }
        });
        return ids;
    }

    /**
     * Ends a session by DELETING its Redis key rather than {@code expireNow()} (which only marks it EXPIRED
     * and defers deletion to the victim's next request). The deletion fires a keyspace notification ->
     * {@code SessionDeletedEvent} -> OIDC back-channel logout / SAML SLO + the metadata cleanup listener, so
     * every termination source (admin force-expiry, access change, self-revoke, concurrent eviction) logs the
     * user out of their downstream apps promptly instead of only on their return or the idle/absolute TTL.
     */
    private void hardDelete(SessionInformation info) {
        sessionRepository.deleteById(info.getSessionId());
    }

    /** When a user is disabled/deleted/re-roled, end their live sessions so a frozen SecurityContext can't
     *  keep acting on stale authorities until idle/absolute expiry. Runs AFTER_COMMIT so a rolled-back
     *  mutation never terminates sessions (fallbackExecution covers publishers outside a transaction). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onUserAccessChanged(UserAccessChangedEvent event) {
        terminateForUser(event.username(), event.orgId()); // scoped to the user's own org — never a same-named
    }

    /**
     * A user's membership in an org was revoked (or the org was suspended, which fans this out per member).
     * End that user's live sessions bound to THAT org only — a session logged into another org they still
     * belong to must survive. AFTER_COMMIT so a rolled-back membership change never terminates sessions.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrganizationAccessRevoked(OrganizationAccessRevokedEvent event) {
        users.findById(event.userId())
                .map(UserAccount::getUsername)
                .ifPresent(username -> terminateForUser(username, event.orgId()));
    }

    /** True when the session's stored SecurityContext carries the given org marker as an authority. */
    private boolean isBoundToOrg(Session session, String marker) {
        return authorities(session).anyMatch(marker::equals);
    }

    /** True when the session carries ANY org marker (i.e. it is a tenant login, not a global/platform one). */
    private boolean hasOrgMarker(Session session) {
        return authorities(session).anyMatch(authority -> authority.startsWith(Factors.ORG_PREFIX));
    }

    /** The authority strings in the session's stored SecurityContext (empty when none/unauthenticated). */
    private Stream<String> authorities(Session session) {
        SecurityContext context = session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        if (context == null || context.getAuthentication() == null) {
            return Stream.empty();
        }
        return context.getAuthentication().getAuthorities().stream().map(GrantedAuthority::getAuthority);
    }

    private boolean isLive(SessionMetadata metadata) {
        SessionInformation info = sessionRegistry.getSessionInformation(metadata.sessionId());
        return info != null && !info.isExpired();
    }
}
