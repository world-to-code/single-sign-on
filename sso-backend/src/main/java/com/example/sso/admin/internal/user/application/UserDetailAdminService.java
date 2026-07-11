package com.example.sso.admin.internal.user.application;

import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.portal.application.ApplicationService;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.session.lifecycle.SessionMetadataStore;
import com.example.sso.session.lifecycle.UserSessions;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserAccount;
import com.example.sso.user.account.UserService;
import com.example.sso.webauthn.PasskeyService;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only secondary sections of the admin user-detail page: the applications a user can launch, their
 * enrolled authentication devices, active sessions, and recent activity. Aggregates the owning modules'
 * public services and returns admin DTOs — no entity crosses a boundary, and the real session id is
 * never exposed (only the opaque public handle).
 */
@Service
@RequiredArgsConstructor
public class UserDetailAdminService {

    private final UserService userService;
    private final ApplicationService applications;
    private final PasskeyService passkeys;
    private final MfaService mfaService;
    private final SessionMetadataStore sessionMetadata;
    private final UserSessions userSessions;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<ApplicationView> applications(UUID userId) {
        return applications.appsForUser(require(userId));
    }

    @Transactional(readOnly = true)
    public UserDevicesView devices(UUID userId) {
        UserAccount user = require(userId);
        return new UserDevicesView(mfaService.hasEnabledTotp(userId), passkeys.list(user));
    }

    public List<UserSessionView> sessions(UUID userId) {
        UserAccount user = require(userId);
        // Scope to the target's OWN org: username is unique only within an org, so an unscoped metadata lookup
        // would expose a same-named user's session PII (IP/UA/activity) in another tenant.
        Set<String> orgSessionIds = userSessions.sessionIdsForUser(user.getUsername(), user.getOrgId());
        return sessionMetadata.forUser(user.getUsername()).stream()
                .filter(metadata -> orgSessionIds.contains(metadata.sessionId()))
                .map(UserSessionView::of)
                .toList();
    }

    public Page<AuditEntry> activity(UUID userId, int page, int size) {
        // Scope to the target's OWN org: usernames are unique only within an org (V68), so an org-less lookup
        // could surface a same-named principal's activity from another tenant.
        UserAccount user = require(userId);
        List<AuditEntry> recent = audit.recentForPrincipal(user.getOrgId(), user.getUsername());
        return Page.of(recent, page, size);
    }

    /** Admin force-expiry: ends ALL of a user's live sessions (and, via the listeners, logs them out of
     *  their OIDC/SAML participants). Returns the number of sessions ended. */
    public int terminateSessions(UUID userId) {
        UserAccount user = require(userId);
        int count = userSessions.terminateForUser(user.getUsername(), user.getOrgId());
        audit.record(new AuditRecord(AuditType.SESSION_ADMIN_REVOKED, user.getUsername(), true,
                "count=" + count, null));
        return count;
    }

    private UserAccount require(UUID userId) {
        return userService.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }
}
