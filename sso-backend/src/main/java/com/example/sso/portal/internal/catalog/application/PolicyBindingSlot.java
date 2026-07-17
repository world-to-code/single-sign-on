package com.example.sso.portal.internal.catalog.application;

import com.example.sso.metadata.AttributePredicate;
import com.example.sso.metadata.AttributePredicateGroup;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding.SubjectType;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingCondition;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingConditionRepository;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import java.util.List;
import java.util.Map;
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
    private final PolicyBindingConditionRepository conditions;
    private final PolicyBindingConditions conditionGroups;
    private final JdbcTemplate jdbc;

    /** The single binding at this (app, subject, tier) slot, or empty. RLS-scoped by the caller's context. */
    Optional<PolicyBinding> find(AppType appType, String appId, SubjectType subjectType, UUID subjectId, UUID org) {
        if (subjectType == SubjectType.ATTRIBUTE) {
            // Predicate bindings have no subject_id to key on; they are located by their condition SET via
            // findAttribute(), never this id-subject find().
            throw new IllegalArgumentException("attribute-predicate bindings are not addressable by find()");
        }
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
     * The app+tier ATTRIBUTE binding whose condition SET equals {@code group}, or empty. A predicate binding has
     * no addressable slot key (its identity is the whole condition set), so the writers reconcile by finding an
     * existing matching binding before creating a new one — accepting a harmless duplicate under a concurrent
     * same-policy edit (already serialized by the policy row lock). RLS-scoped by context, then filtered to the
     * acting tier so a GLOBAL binding surfaced by RLS is not mistaken for the tenant's own.
     */
    private Optional<PolicyBinding> findAttribute(AppType appType, String appId, AttributePredicateGroup group,
            UUID org) {
        List<PolicyBinding> attributeBindings = inTier(bindings.findByAppTypeAndAppId(appType, appId), org).stream()
                .filter(binding -> binding.getSubjectType() == SubjectType.ATTRIBUTE)
                .toList();
        Map<UUID, AttributePredicateGroup> groups = conditionGroups.groupsOf(attributeBindings);
        return attributeBindings.stream().filter(binding -> group.equals(groups.get(binding.getId()))).findFirst();
    }

    /**
     * Persist a new ATTRIBUTE binding (which the caller has ALREADY pointed at an axis policy — so the has-policy
     * CHECK passes) and one condition row per predicate of {@code group}, all flushed in the acting tier so RLS
     * {@code WITH CHECK} stamps each row's org (must run INSIDE the org scope, like {@link #save}). The binding is
     * flushed first so its id is available for the condition rows' FK.
     */
    private void createAttribute(PolicyBinding binding, AttributePredicateGroup group, UUID org) {
        bindings.saveAndFlush(binding);
        for (AttributePredicate condition : group.conditions()) {
            conditions.save(PolicyBindingCondition.of(binding.getId(), condition, org));
        }
        conditions.flush();
    }

    /**
     * Reconcile the exact condition {@code group} for one {@code axis}: reuse the app+tier ATTRIBUTE binding whose
     * condition set equals it — co-locating this axis's policy with the other axis's on the same row — and save,
     * or create a new binding (already carrying this policy, so the has-policy CHECK passes) plus its condition
     * rows. Not the atomic id-subject {@link #upsert}: a group has no slot key, so a concurrent same-policy edit
     * may leave a harmless duplicate (resolution is idempotent for equal groups), cleaned only when the target is
     * removed entirely. The shared attribute-reconcile home for both binding writers.
     */
    void reconcileAttribute(AppType appType, String appId, AttributePredicateGroup group, UUID org, UUID policyId,
            int priority, PolicyAxis axis) {
        PolicyBinding existing = findAttribute(appType, appId, group, org).orElse(null);
        if (existing != null) {
            axis.assign(existing, policyId, priority);
            save(existing); // plain save, not the id-subject upsert (an ATTRIBUTE row has no slot index)
        } else {
            PolicyBinding binding = PolicyBinding.forAttributeGroup(appType, appId, org);
            axis.assign(binding, policyId, priority);
            createAttribute(binding, group, org);
        }
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
        jdbc.update("insert into policy_binding (id, app_type, app_id, subject_type, subject_id, "
                + "auth_policy_id, session_policy_id, priority, session_priority, org_id, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) "
                + "on conflict " + conflictTarget(binding) + " do update set " + set,
                UUID.randomUUID(), binding.getAppType().name(), binding.getAppId(),
                binding.getSubjectType() == null ? null : binding.getSubjectType().name(), binding.getSubjectId(),
                binding.getAuthPolicyId(), binding.getSessionPolicyId(),
                binding.getPriority(), binding.getSessionPriority(), binding.getOrgId());
    }

    /** The partial unique index (columns + predicate) covering this slot's shape (all-subjects / id-subject).
     *  Exhaustive over {@link SubjectType} with no {@code default}, so a new subject kind fails compilation here
     *  until its own conflict target (and index) is chosen. ATTRIBUTE has no slot index — its identity is a
     *  condition SET a partial unique cannot express — so it is reconciled via {@link #findAttribute}/
     *  {@link #createAttribute}, never this atomic upsert. */
    private String conflictTarget(PolicyBinding binding) {
        boolean global = binding.getOrgId() == null;
        return switch (binding.getSubjectType()) {
            case null -> global
                    ? "(app_type, app_id) where org_id is null and subject_type is null"
                    : "(org_id, app_type, app_id) where org_id is not null and subject_type is null";
            case ATTRIBUTE -> throw new IllegalStateException(
                    "attribute bindings are reconciled via findAttribute/createAttribute, not upsert");
            case USER, GROUP, ROLE -> global
                    ? "(app_type, app_id, subject_type, subject_id) where org_id is null and subject_type is not null"
                    : "(org_id, app_type, app_id, subject_type, subject_id) where org_id is not null and subject_type is not null";
        };
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
