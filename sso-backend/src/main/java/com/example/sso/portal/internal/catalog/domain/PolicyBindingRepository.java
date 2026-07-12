package com.example.sso.portal.internal.catalog.domain;

import com.example.sso.portal.application.AppType;
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

    /** Bindings referencing a session policy — the impact view before edit/delete. */
    List<PolicyBinding> findBySessionPolicyId(UUID sessionPolicyId);

    /** Bindings referencing an auth policy — the impact view before edit/delete. */
    List<PolicyBinding> findByAuthPolicyId(UUID authPolicyId);
}
