package com.example.sso.admin.internal.user.application;

import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditRecord;
import com.example.sso.audit.AuditService;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.session.SessionMetadataStore;
import com.example.sso.session.UserSessions;
import com.example.sso.shared.Page;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.UserAccount;
import com.example.sso.user.UserService;
import com.example.sso.webauthn.PasskeyService;

import java.util.List;
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
        return sessionMetadata.forUser(user.getUsername()).stream()
                .map(UserSessionView::of)
                .toList();
    }

    public Page<AuditEntry> activity(UUID userId, int page, int size) {
        List<AuditEntry> recent = audit.recentForPrincipal(require(userId).getUsername());
        return Page.of(recent, page, size);
    }

    /** Admin force-expiry: ends ALL of a user's live sessions (and, via the listeners, logs them out of
     *  their OIDC/SAML participants). Returns the number of sessions ended. */
    public int terminateSessions(UUID userId) {
        UserAccount user = require(userId);
        int count = userSessions.terminateAll(user.getUsername());
        audit.record(new AuditRecord(AuditType.SESSION_ADMIN_REVOKED, user.getUsername(), true,
                "count=" + count, null));
        return count;
    }

    private UserAccount require(UUID userId) {
        return userService.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }
}
