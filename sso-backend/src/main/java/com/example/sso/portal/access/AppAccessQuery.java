package com.example.sso.portal.access;

import com.example.sso.portal.application.AppType;
import com.example.sso.portal.application.ApplicationService;

import com.example.sso.user.account.UserAccount;

import java.time.Instant;
import java.util.Set;

/**
 * Immutable parameter object for {@link ApplicationService#appAccess(AppAccessQuery)}: the user, the
 * target application, the factors the user currently holds, and the time of their last app step-up.
 */
public record AppAccessQuery(UserAccount user, AppType appType, String appId, Set<String> grantedFactors,
                             Instant lastAppStepUp) {
}
