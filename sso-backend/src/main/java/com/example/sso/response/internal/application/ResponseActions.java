package com.example.sso.response.internal.application;

import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.response.ResponseCaller;
import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import com.example.sso.user.account.AccountHoldService;
import com.example.sso.user.account.AccountHoldView;
import com.example.sso.user.account.HoldSpec;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The verbs a detection system may perform, and the one check every one of them shares.
 *
 * <p><b>The target must belong to the caller's tenant.</b> A user id is a bare UUID and {@code app_user} is
 * not RLS-confined, so nothing about looking one up says whose it is — without this, tenant A's response
 * credential would end tenant B's sessions by knowing an id. The refusal is a 404 rather than a 403, so a
 * caller cannot use it to discover that an id exists somewhere else.
 *
 * <p>Every action carries the caller's correlation id into the trail. That is the only thing joining "this
 * account was held at 04:12" to the detection that decided it should be, and an unexplainable response is one
 * nobody can review, defend or learn from.
 */
@Service
@RequiredArgsConstructor
public class ResponseActions {

    private final UserService users;
    private final AccountHoldService holds;
    private final UserSessions sessions;
    private final OrgContext orgContext;
    private final AuditService audit;
    private final ResponseCaller caller;
    private final Clock clock;

    /** Ends the account's live sessions, propagating to the applications it is signed in to. */
    public int terminateSessions(UUID userId) {
        UserAccount user = requireInCallersTenant(userId);
        int ended = sessions.terminateForUser(user.getUsername(), user.getOrgId());
        // The principal is the TARGET, matching the console's termination — the user-activity view queries by
        // principal, so naming the machine here would hide the machine's own sign-outs from the page an
        // operator opens to ask why somebody was signed out. Which machine acted is in the detail.
        audit.record(new AuditRecord(AuditType.SESSION_ADMIN_REVOKED, user.getUsername(),
                true, "sessions=" + ended + " " + callerDetail(), null,
                AuditSubjectType.USER, userId.toString(), user.getOrgId()));
        return ended;
    }

    /**
     * Places (or re-places) the hold. The expiry is computed from the server's clock rather than taken from
     * the caller, so a detection system's skew cannot mint one that is already over — or one that outlives
     * the ceiling by arriving with a future timestamp.
     */
    public AccountHoldView hold(UUID userId, String reason, Duration duration) {
        requireInCallersTenant(userId);
        return holds.place(HoldSpec.byService(userId, reason, clock.instant().plus(duration),
                caller.correlationId().orElse(null)));
    }

    /** Lifts the hold early; false when there was none, so a caller's retry is not reported as a failure. */
    public boolean liftHold(UUID userId) {
        requireInCallersTenant(userId);
        return holds.lift(userId);
    }

    public Optional<AccountHoldView> holdInEffect(UUID userId) {
        requireInCallersTenant(userId);
        return holds.holdInEffect(userId);
    }

    /**
     * The account, if it belongs to the tenant this request resolved to. The tenant comes from the HOST — the
     * same derivation that decides which issuer signed the caller's token — so the two cannot disagree.
     */
    private UserAccount requireInCallersTenant(UUID userId) {
        UUID callersOrg = orgContext.currentOrg().orElse(null);
        return users.findById(userId)
                .filter(user -> Objects.equals(user.getOrgId(), callersOrg))
                .orElseThrow(() -> NotFoundException.of("user.notFound"));
    }

    private String callerDetail() {
        return "client=" + caller.clientId().orElse("unknown")
                + " correlation=" + caller.correlationId().orElse("none");
    }
}
