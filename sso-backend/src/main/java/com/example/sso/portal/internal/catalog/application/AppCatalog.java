package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AuthPolicyResolver;
import com.example.sso.portal.application.ApplicationDescriptor;
import com.example.sso.portal.application.ApplicationSource;
import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import com.example.sso.shared.IdName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The unified application catalog: aggregates each protocol module's launchable apps (OIDC in admin,
 * SAML in saml) into {@link ApplicationView}s, resolving each app's sign-on policy name in one pass.
 */
@Service
@RequiredArgsConstructor
class AppCatalog {

    /** Each protocol module contributes its launchable apps here (OIDC in admin, SAML in saml). */
    private final List<ApplicationSource> applicationSources;
    private final PolicyBindingRepository bindings;
    private final AuthPolicyResolver authPolicies;

    @Transactional(readOnly = true)
    List<ApplicationView> list() {
        return new ArrayList<>(index().values());
    }

    /** All applications keyed by {@code type:id}, so assignment lookups resolve app names in one build. */
    @Transactional(readOnly = true)
    Map<String, ApplicationView> index() {
        // App-wide sign-on policy per app = the all-subjects auth binding (own-org beats the inherited global,
        // mirroring the resolver's orgRank); + policy-id -> name. One lookup pass, not per-app queries.
        Map<String, PolicyBinding> appWide = new HashMap<>();
        for (PolicyBinding b : bindings.findBySubjectTypeIsNullAndAuthPolicyIdNotNull()) {
            appWide.merge(AppKey.of(b.getAppType(), b.getAppId()), b, (a, c) -> a.getOrgId() != null ? a : c);
        }
        Map<String, UUID> appPolicyByKey = appWide.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAuthPolicyId()));
        Map<UUID, String> policyNames = authPolicies.policyNames().stream()
                .collect(Collectors.toMap(IdName::getId, IdName::getName));

        Map<String, ApplicationView> index = new LinkedHashMap<>();
        for (ApplicationSource source : applicationSources) {
            for (ApplicationDescriptor app : source.applications()) {
                index.put(AppKey.of(app.type(), app.id()), appView(app, appPolicyByKey, policyNames));
            }
        }

        return index;
    }

    private ApplicationView appView(ApplicationDescriptor app, Map<String, UUID> appPolicyByKey,
                                    Map<UUID, String> policyNames) {
        UUID policyId = appPolicyByKey.get(AppKey.of(app.type(), app.id()));
        return ApplicationView.of(app, policyId == null ? null : policyId.toString(),
                policyId == null ? null : policyNames.get(policyId));
    }
}
