package com.example.sso.authpolicy.policy;

import java.util.UUID;

/**
 * The per-app sign-on assignments of an auth policy, stored in the {@code policy_binding} matrix — the OIDC/SAML
 * clients and the {@code PORTAL/admin} console, i.e. every binding that references the policy EXCEPT the
 * {@code PORTAL/user} login binding. Declared here in the authpolicy module so its admin service can consult app
 * usage without importing the portal module (which owns {@code policy_binding}) — the implementation lives in
 * portal and is injected at runtime, avoiding an authpolicy&rarr;portal cycle. Companion to {@link LoginAuthBindings}
 * (the {@code PORTAL/user} login side): {@code clearForPolicy} clears the login binding and this covers all the
 * rest, so together they account for every {@code auth_policy_id} reference on delete.
 */
public interface AppPolicyBindings {

    /**
     * Whether the auth policy is still assigned to an app (an OIDC/SAML client or the admin console) in the acting
     * tier — every referencing binding except the user-login one. Deleting such a policy would violate the
     * {@code auth_policy_id} FK, so the admin must unassign it from the app(s) first: unlike a login binding, an
     * app assignment is NOT silently cleared on delete (that would weaken the app's sign-on requirement).
     */
    boolean isAssignedToApp(UUID authPolicyId);
}
