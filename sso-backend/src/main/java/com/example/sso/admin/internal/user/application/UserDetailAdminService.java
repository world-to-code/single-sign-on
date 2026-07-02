package com.example.sso.admin.internal.user.application;

import com.example.sso.audit.AuditEntry;
import com.example.sso.audit.AuditService;
import com.example.sso.mfa.MfaService;
import com.example.sso.portal.ApplicationService;
import com.example.sso.portal.ApplicationView;
import com.example.sso.session.SessionMetadataStore;
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

    public List<AuditEntry> activity(UUID userId) {
        return audit.recentForPrincipal(require(userId).getUsername());
    }

    private UserAccount require(UUID userId) {
        return userService.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }
}
