package com.example.sso.portal.internal.catalog.domain;

import com.example.sso.portal.application.AppType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyBindingRepository extends JpaRepository<PolicyBinding, UUID> {

    /** All bindings for an app (RLS-scoped: the caller's tenant plus GLOBAL rows). */
    List<PolicyBinding> findByAppTypeAndAppId(AppType appType, String appId);

    /** An app-wide (all-subjects) binding owned by a specific tenant. */
    Optional<PolicyBinding> findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgId(AppType appType, String appId, UUID orgId);

    /** The GLOBAL app-wide (all-subjects) binding (org_id NULL) tenants inherit. */
    Optional<PolicyBinding> findByAppTypeAndAppIdAndSubjectTypeIsNullAndOrgIdIsNull(AppType appType, String appId);

    /** A specific subject's binding for an app within a tenant (the per-subject auth row). */
    Optional<PolicyBinding> findByAppTypeAndAppIdAndSubjectTypeAndSubjectIdAndOrgId(
            AppType appType, String appId, PolicyBinding.SubjectType subjectType, UUID subjectId, UUID orgId);

    /** A specific subject's GLOBAL binding for an app (org_id NULL). */
    Optional<PolicyBinding> findByAppTypeAndAppIdAndSubjectTypeAndSubjectIdAndOrgIdIsNull(
            AppType appType, String appId, PolicyBinding.SubjectType subjectType, UUID subjectId);

    /** Every app-wide (all-subjects) AUTH binding (RLS-scoped) — the admin catalog's app sign-on-policy badge. */
    List<PolicyBinding> findBySubjectTypeIsNullAndAuthPolicyIdNotNull();

    /** Bindings referencing a session policy — the impact view before edit/delete. */
    List<PolicyBinding> findBySessionPolicyId(UUID sessionPolicyId);

    /** Bindings referencing an auth policy — the impact view before edit/delete. */
    List<PolicyBinding> findByAuthPolicyId(UUID authPolicyId);

    /** Auth bindings of one app referencing any of the given policies (RLS-scoped) — reconstructs login scope. */
    List<PolicyBinding> findByAppTypeAndAppIdAndAuthPolicyIdIn(AppType appType, String appId, Collection<UUID> authPolicyIds);

    /** Session bindings of one app referencing any of the given policies (RLS-scoped) — reconstructs assignment scope. */
    List<PolicyBinding> findByAppTypeAndAppIdAndSessionPolicyIdIn(AppType appType, String appId, Collection<UUID> sessionPolicyIds);
}
