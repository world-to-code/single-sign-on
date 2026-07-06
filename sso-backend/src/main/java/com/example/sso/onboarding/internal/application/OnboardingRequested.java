package com.example.sso.onboarding.internal.application;

import java.util.UUID;

/** Published (after commit) when an onboarding job is accepted, so the async worker provisions it. */
record OnboardingRequested(UUID onboardingId, OnboardingSpec spec) {
}
