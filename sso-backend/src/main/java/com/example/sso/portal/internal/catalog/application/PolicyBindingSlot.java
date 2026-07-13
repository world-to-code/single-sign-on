package com.example.sso.portal.internal.catalog.application;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The RLS-critical mechanics shared by the {@code policy_binding} WRITERS ({@link AppAuthBinding},
 * {@link LoginAuthBindings} impl, {@code SessionBindings} impl): locating a binding's exact (app, subject, tier)
 * slot, persisting it FLUSHED in the acting tier so RLS {@code WITH CHECK} stamps the right org, and the
 * delete-or-survive rule when a row's last policy is cleared. Extracted so this one invariant — whose divergence
 * would leak a binding across tenants or orphan a row — lives in a single place; each writer keeps only its own
 * axis mutators (auth vs session field) and reconcile orchestration.
 */
@Service
@RequiredArgsConstructor
class PolicyBindingSlot {

    private final PolicyBindingRepository bindings;

    /** The single binding at this (app, subject, tier) slot, or empty. RLS-scoped by the caller's context. */
    Optional<PolicyBinding> find(AppType appType, String appId, SubjectType subjectType, UUID subjectId, UUID org) {
        if (subjectType == null) {
            return org == null
                    ? bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgIdIsNull(appType, appId)
                    : bindings.findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgId(appType, appId, org);
        }
        return org == null
                ? bindings.findByAppTypeAndAppIdAndSubjectTypeAndSubjectIdAndOrgIdIsNull(appType, appId, subjectType, subjectId)
                : bindings.findByAppTypeAndAppIdAndSubjectTypeAndSubjectIdAndOrgId(appType, appId, subjectType, subjectId, org);
    }

    /**
     * Persist a binding, flushed NOW so RLS {@code WITH CHECK} evaluates it in the acting tier — a deferred
     * flush would run after the org scope restores and fail (see rls-connection-context-binder).
     */
    void save(PolicyBinding binding) {
        bindings.saveAndFlush(binding);
    }

    /**
     * After a caller has nulled its OWN policy field: delete the row if it now carries no policy at all (the DB
     * CHECK needs ≥1), else persist it — a co-located binding on the other axis (auth vs session) survives.
     */
    void deleteIfEmptyElseSave(PolicyBinding binding) {
        if (binding.carriesNoPolicy()) {
            bindings.delete(binding);
        } else {
            save(binding);
        }
    }

    /** Keep only the acting tier's rows from an RLS-scoped find (which may also surface GLOBAL rows). */
    List<PolicyBinding> inTier(List<PolicyBinding> found, UUID org) {
        return found.stream().filter(binding -> Objects.equals(binding.getOrgId(), org)).toList();
    }
}
