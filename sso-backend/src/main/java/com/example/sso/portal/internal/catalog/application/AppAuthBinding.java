package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.shared.error.NotFoundException;
import com.example.sso.tenancy.OrgTierGuard;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the per-app sign-on (auth) policy into the {@code policy_binding} matrix: the app-wide (all-subjects)
 * policy and each per-subject policy for an OIDC/SAML app. Org-stamped with the acting tier (matching
 * {@code app_assignment}), so RLS confines a tenant admin's write to its own org and a platform admin's to the
 * global rows. A {@code null} policy clears the binding (deleting the row when it carries no other policy).
 */
@Service
@RequiredArgsConstructor
class AppAuthBinding {

    private final PolicyBindingRepository bindings;
    private final PolicyBindingSlot slot;
    private final AuthPolicyResolver authPolicies;
    private final OrgTierGuard tierGuard;

    /** Set (or clear when {@code null}) the app-wide sign-on policy for an app, in the acting tier. */
    @Transactional
    void setAppWide(AppType appType, String appId, UUID authPolicyId) {
        upsert(appType, appId, null, null, authPolicyId);
    }

    /** Set (or clear when {@code null}) a subject's sign-on policy for an app, in the acting tier. */
    @Transactional
    void setForSubject(AppType appType, String appId, SubjectType subjectType, UUID subjectId, UUID authPolicyId) {
        upsert(appType, appId, subjectType, subjectId, authPolicyId);
    }

    /** Remove a subject's sign-on binding for an app (on unassign) — no-op if none. */
    @Transactional
    void clearForSubject(AppType appType, String appId, SubjectType subjectType, UUID subjectId) {
        slot.find(appType, appId, subjectType, subjectId, tierGuard.currentTier()).ifPresent(bindings::delete);
    }

    private void upsert(AppType appType, String appId, SubjectType subjectType, UUID subjectId, UUID authPolicyId) {
        if (authPolicyId != null && !authPolicies.exists(authPolicyId)) {
            // A dangling policy id would silently resolve to "no policy" and quietly drop the configured step-up.
            throw NotFoundException.of("policy.notFound");
        }
        UUID org = tierGuard.currentTier();
        if (authPolicyId == null) {
            slot.find(appType, appId, subjectType, subjectId, org).ifPresent(binding -> {
                binding.assignAuthPolicy(null);
                slot.deleteIfEmptyElseSave(binding); // a session binding on the same row survives
            });
            return;
        }
        // Atomic upsert (race-safe) in the acting tier so RLS WITH CHECK sees the right org; a co-located session
        // binding on the same row is preserved.
        PolicyBinding binding = subjectType == null
                ? PolicyBinding.forAllSubjects(appType, appId, org)
                : PolicyBinding.forSubject(appType, appId, subjectType, subjectId, org);
        binding.assignAuthPolicy(authPolicyId);
        slot.upsert(binding, PolicyAxis.AUTH);
    }
}
