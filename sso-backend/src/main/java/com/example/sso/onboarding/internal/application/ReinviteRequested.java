package com.example.sso.onboarding.internal.application;

import java.util.UUID;

/**
 * Published (after commit) when an admin asks to re-invite a provisioned-but-not-activated onboarding admin
 * (its invitation email failed or its token expired), so the async worker mints a fresh invitation + resends.
 */
record ReinviteRequested(UUID onboardingId) {
}
