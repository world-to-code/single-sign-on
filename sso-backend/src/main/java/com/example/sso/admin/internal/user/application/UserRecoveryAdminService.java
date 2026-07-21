package com.example.sso.admin.internal.user.application;

import com.example.sso.admin.internal.shared.application.AdminAuditLogger;
import com.example.sso.audit.AuditSubjectType;
import com.example.sso.audit.AuditType;
import com.example.sso.mfa.MfaService;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.user.account.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Getting a locked-out person back in, which is not the same thing as granting them anything.
 *
 * <p>Both operations here clear an obstacle rather than confer authority: resetting MFA makes the user enrol
 * again, and re-sending the ownership mail only lets them flip a verified flag. Neither can be used to sign in
 * as somebody. Keeping them apart from the service that edits roles and permissions is the point — this is the
 * pair a support-shaped role should be able to hold without also being able to change what anyone may do.
 *
 * <p>It was also the only reason the larger class carried the MFA service at all.
 */
@Service
@RequiredArgsConstructor
public class UserRecoveryAdminService {

    private final UserService userService;
    private final MfaService mfaService;
    private final AdminAuditLogger auditLogger;

    /** Clears a user's MFA enrollment so they re-enroll on next login (recovery). */
    @Transactional
    public void resetUserMfa(UUID id) {
        requireUser(id);
        mfaService.resetMfa(id);
        auditLogger.log(AuditType.USER_MFA_RESET, AuditSubjectType.USER, id.toString(), "user=" + id);
    }

    /**
     * Re-sends the proof-of-ownership mail for an unverified address. Recovery, not a grant: the code it mails
     * only flips the verified flag, so it cannot be used to sign in as the user.
     */
    @Transactional
    public void resendEmailVerification(UUID id) {
        requireUser(id);
        userService.requestEmailVerification(id);
        auditLogger.log(AuditType.USER_UPDATED, AuditSubjectType.USER, id.toString(),
                "email verification resent user=" + id);
    }

    /** Checked before acting, so a recovery on a non-existent id is a 404 rather than a silent no-op. */
    private void requireUser(UUID id) {
        if (userService.findById(id).isEmpty()) {
            throw NotFoundException.of("user.notFound");
        }
    }
}
