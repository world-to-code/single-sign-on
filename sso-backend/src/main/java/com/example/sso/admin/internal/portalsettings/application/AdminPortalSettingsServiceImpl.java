package com.example.sso.admin.internal.portalsettings.application;

import com.example.sso.admin.AdminPortalSettingsData;
import com.example.sso.admin.AdminPortalSettingsService;
import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettings;
import com.example.sso.admin.internal.portalsettings.domain.AdminPortalSettingsRepository;
import com.example.sso.session.SessionPolicyDetails;
import com.example.sso.session.SessionPolicyService;
import com.example.sso.shared.error.BadRequestException;
import com.example.sso.shared.error.ForbiddenException;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgContext;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default {@link AdminPortalSettingsService}. Resolves the acting tenant from {@link OrgContext} and reads
 * (or, on update, writes) that tenant's own {@code admin_portal_settings} row, falling back to the GLOBAL
 * default (org_id null) a tenant inherits until it saves its own. The row carries a single choice: which
 * session policy governs the admin console.
 */
@Service
@RequiredArgsConstructor
public class AdminPortalSettingsServiceImpl implements AdminPortalSettingsService {

    private final AdminPortalSettingsRepository repository;
    private final SessionPolicyService sessionPolicies;
    private final OrgContext orgContext;

    @Override
    @Transactional(readOnly = true)
    public AdminPortalSettingsData get() {
        return toData(resolve(actingOrg()));
    }

    @Override
    @Transactional
    public AdminPortalSettingsData update(AdminPortalSettingsData command) {
        requireSelectableInTier(command.sessionPolicyId());
        // Write the ACTING tenant's own row (a tenant admin can only touch their bound org; an un-drilled
        // super-admin edits the global default). On a tenant's first save, copy-on-write from the global row.
        AdminPortalSettings settings = writableRow(actingOrg());
        settings.selectPolicy(command.sessionPolicyId());
        return toData(repository.save(settings));
    }

    /**
     * A tenant may only point its console at a policy of its OWN tier: {@code listAll} is tier-scoped, so a
     * policy outside it (another tenant's, or a global one a tenant cannot see) is rejected — otherwise the
     * console's posture could be governed by a policy the tenant neither owns nor can inspect.
     */
    private void requireSelectableInTier(UUID policyId) {
        if (policyId == null) {
            return; // clearing the selection restores "the acting admin's own resolved policy"
        }
        boolean selectable = sessionPolicies.listAll().stream()
                .map(SessionPolicyDetails::getId)
                .anyMatch(policyId::equals);
        if (!selectable) {
            throw BadRequestException.of("admin.sessionPolicy.unknown");
        }
    }

    private UUID actingOrg() {
        return orgContext.currentOrg().orElse(null);
    }

    /** The tenant's own row if present, else the global default it currently inherits. */
    private AdminPortalSettings resolve(UUID orgId) {
        if (orgId != null) {
            return repository.findByOrgId(orgId).orElseGet(this::global);
        }
        return global();
    }

    /** The row to write for the acting tenant: its own (creating one seeded from the global default if none). */
    private AdminPortalSettings writableRow(UUID orgId) {
        if (orgId == null) {
            // Deny-by-default: the global default is platform-wide (every un-customized tenant inherits it), so
            // only the platform super-admin context may write it. A principal with no bound org that is NOT the
            // platform context (e.g. an org user that authenticated without a resolved tenant) must never fall
            // through to editing every tenant's inherited default.
            if (!orgContext.isPlatform()) {
                throw new ForbiddenException("only a platform administrator may edit the global admin-portal settings");
            }
            return global();
        }
        return repository.findByOrgId(orgId)
                .orElseGet(() -> new AdminPortalSettings(orgId, global().getSessionPolicyId()));
    }

    private AdminPortalSettings global() {
        return repository.findByOrgIdIsNull()
                .orElseThrow(() -> new NotFoundException("admin portal settings not initialized"));
    }

    private AdminPortalSettingsData toData(AdminPortalSettings settings) {
        return new AdminPortalSettingsData(settings.getSessionPolicyId());
    }
}
