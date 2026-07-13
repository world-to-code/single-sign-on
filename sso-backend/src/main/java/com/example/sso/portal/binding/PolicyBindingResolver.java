package com.example.sso.portal.binding;

import com.example.sso.authpolicy.policy.AuthPolicyView;
import com.example.sso.portal.application.AppType;
import com.example.sso.session.policy.SessionPolicyDetails;
import com.example.sso.user.account.UserAccount;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the effective auth and session policy for a user in the context of a given app or portal from the
 * {@code policy_binding} matrix. Auth and session resolve INDEPENDENTLY; for each, the most specific matching
 * binding (USER &gt; GROUP/ROLE &gt; all-subjects, then priority, then a stable tie-break) whose referenced
 * policy is still ENABLED wins, so a disabled strict binding is transparent rather than weakening resolution.
 * Both return empty when no binding applies — the caller supplies its own fallback (login/step-up default,
 * the admin-console pin, or the user's global session policy).
 *
 * <p>SECURITY INVARIANT: call inside the acting user's bound org context, never as platform — a USER-subject
 * id is a global identity, so a platform-context resolution could match another tenant's binding.
 */
public interface PolicyBindingResolver {

    Optional<AuthPolicyView> resolveAuthPolicy(UserAccount user, AppType appType, String appId);

    Optional<SessionPolicyDetails> resolveSessionPolicy(UserAccount user, AppType appType, String appId);

    /**
     * EVERY enabled session policy whose binding matches the user for the app (not just the most-specific
     * winner). For floor-type controls (IP allowlist, concurrent-session cap) that a request must satisfy across
     * ALL applicable policies, not the single specificity winner. Same org-context invariant as above.
     */
    List<SessionPolicyDetails> resolveSessionPolicies(UserAccount user, AppType appType, String appId);
}
