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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

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
     * ATOMICALLY take over (or create) this (app, subject, tier) slot for the binding's {@code axis} policy —
     * {@code INSERT ... ON CONFLICT DO UPDATE} against the partial unique index for the slot, so two writers
     * racing the same slot (the async baseline provisioner and a concurrent admin assign) can't both INSERT and
     * trip a duplicate-key. Only the axis's own columns are written; a co-located binding on the OTHER axis is
     * left untouched. Runs on the transaction's RLS-bound connection ({@code WITH CHECK} stamps the acting org),
     * so it must be called INSIDE the org scope like {@link #save}. The target/set fragments are code-controlled
     * (never user input); every value is a bound parameter.
     */
    void upsert(PolicyBinding binding, PolicyAxis axis) {
        // Flush pending JPA writes first: the referenced auth/session policy is often created in the SAME
        // transaction (still in the persistence context), and this native statement bypasses Hibernate — without
        // the flush the FK to that not-yet-inserted policy row fails. (saveAndFlush used to flush it implicitly.)
        bindings.flush();
        String set = axis == PolicyAxis.AUTH
                ? "auth_policy_id = excluded.auth_policy_id, priority = excluded.priority"
                : "session_policy_id = excluded.session_policy_id, session_priority = excluded.session_priority";
        jdbc.update("insert into policy_binding (id, app_type, app_id, subject_type, subject_id, auth_policy_id, "
                + "session_policy_id, priority, session_priority, org_id, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                + "on conflict " + conflictTarget(binding.getOrgId() == null, binding.getSubjectType() == null)
                + " do update set " + set,
                UUID.randomUUID(), binding.getAppType().name(), binding.getAppId(),
                binding.getSubjectType() == null ? null : binding.getSubjectType().name(), binding.getSubjectId(),
                binding.getAuthPolicyId(), binding.getSessionPolicyId(),
                binding.getPriority(), binding.getSessionPriority(), binding.getOrgId());
    }

    /** The partial unique index (columns + predicate) covering this (org-or-global, all-subjects-or-subject) slot. */
    private String conflictTarget(boolean global, boolean allSubjects) {
        if (allSubjects) {
            return global
                    ? "(app_type, app_id) where org_id is null and subject_type is null"
                    : "(org_id, app_type, app_id) where org_id is not null and subject_type is null";
        }
        return global
                ? "(app_type, app_id, subject_type, subject_id) where org_id is null and subject_type is not null"
                : "(org_id, app_type, app_id, subject_type, subject_id) where org_id is not null and subject_type is not null";
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
