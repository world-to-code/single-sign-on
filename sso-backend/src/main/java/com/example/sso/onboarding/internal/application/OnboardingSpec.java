package com.example.sso.onboarding.internal.application;

import com.example.sso.organization.CompanyProfile;

/**
 * The onboarding request payload: the new tenant's slug/name + Okta/Ping-style company profile, and the
 * initial admin (work email + display name). Carried on the async provisioning event.
 */
public record OnboardingSpec(String slug, String name, CompanyProfile profile,
                             String adminEmail, String adminName) {
}
