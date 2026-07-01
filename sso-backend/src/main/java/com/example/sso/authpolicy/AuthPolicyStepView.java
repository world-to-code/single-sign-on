package com.example.sso.authpolicy;

import java.util.Set;

/**
 * Read-only view of a single authentication-policy step: the set of factors, any one of which
 * satisfies the step. The backing entity stays module-internal.
 */
public interface AuthPolicyStepView {

    Set<AuthFactor> getAllowedFactors();
}
