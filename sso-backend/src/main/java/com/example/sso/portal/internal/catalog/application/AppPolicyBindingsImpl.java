package com.example.sso.portal.internal.catalog.application;

import com.example.sso.authpolicy.policy.AppPolicyBindings;
import com.example.sso.portal.application.AppType;
import com.example.sso.portal.binding.PortalApps;
import com.example.sso.portal.internal.catalog.domain.PolicyBinding;
import com.example.sso.portal.internal.catalog.domain.PolicyBindingRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the {@code policy_binding} matrix to answer whether an auth policy is still assigned to an app
 * (see {@link AppPolicyBindings}). The DIP inversion — port in authpolicy, implementation here in portal — mirrors
 * {@link LoginAuthBindingsImpl}.
 */
@Service
@RequiredArgsConstructor
class AppPolicyBindingsImpl implements AppPolicyBindings {

    private final PolicyBindingRepository bindings;

    @Override
    @Transactional(readOnly = true)
    public boolean isAssignedToApp(UUID authPolicyId) {
        // Any binding referencing the policy EXCEPT the PORTAL/user login binding would trip the auth_policy_id FK
        // on delete — the login binding is the one the delete path clears separately (clearForPolicy). Blocking on
        // the exact complement of that clear (OIDC/SAML clients AND the PORTAL/admin console) leaves no residual FK
        // violation. RLS scopes the rows to the acting tier.
        return bindings.findByAuthPolicyId(authPolicyId).stream().anyMatch(this::isAppAssignment);
    }

    /** Every referencing binding other than the user-login one: OIDC/SAML apps and the PORTAL/admin console. */
    private boolean isAppAssignment(PolicyBinding binding) {
        return !(binding.getAppType() == AppType.PORTAL && PortalApps.USER.equals(binding.getAppId()));
    }
}
