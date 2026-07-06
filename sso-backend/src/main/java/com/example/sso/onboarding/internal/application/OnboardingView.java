package com.example.sso.onboarding.internal.application;

import com.example.sso.onboarding.internal.domain.Onboarding;
import com.example.sso.onboarding.internal.domain.OnboardingStatus;
import java.util.UUID;

/** Pollable onboarding status for the UI. {@code orgId} is populated once provisioned; {@code error} on failure. */
public record OnboardingView(UUID id, OnboardingStatus status, String slug, UUID orgId, String error) {

    static OnboardingView of(Onboarding onboarding) {
        return new OnboardingView(onboarding.getId(), onboarding.getStatus(), onboarding.getSlug(),
                onboarding.getOrgId(), onboarding.getError());
    }
}
