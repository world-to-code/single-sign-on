package com.example.sso.onboarding.internal.api;

import com.example.sso.onboarding.internal.application.OnboardingServiceImpl;
import com.example.sso.onboarding.internal.application.OnboardingView;
import com.example.sso.shared.security.RequirePermission;
import com.example.sso.shared.security.RequireStepUp;
import com.example.sso.user.Permissions;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-admin onboarding API. {@code POST} accepts a request and returns 202 immediately with a PENDING
 * job (async provisioning + invitation email follow); {@code GET} polls its status. Under {@code /api/admin/**}
 * (MFA + admin gate); creating a tenant + its admin needs {@code organization:create} + a fresh step-up.
 */
@RestController
@RequestMapping("/api/admin/onboarding")
@RequiredArgsConstructor
class AdminOnboardingController {

    private final OnboardingServiceImpl onboarding;

    @PostMapping
    @RequirePermission(Permissions.ORG_CREATE)
    @RequireStepUp
    public ResponseEntity<OnboardingView> start(@Valid @RequestBody CreateOnboardingRequest request) {
        return ResponseEntity.accepted().body(onboarding.start(request.toSpec()));
    }

    // Platform-only (organization:create, not the tenant-grantable organization:read): onboarding is a
    // super-admin operation, and its status carries the tenant slug + a raw failure message.
    @GetMapping("/{id}")
    @RequirePermission(Permissions.ORG_CREATE)
    public OnboardingView status(@PathVariable UUID id) {
        return onboarding.status(id);
    }

    /** Re-invite a provisioned admin whose invitation email failed or expired: mints a fresh token + re-sends. */
    @PostMapping("/{id}/reinvite")
    @RequirePermission(Permissions.ORG_CREATE)
    @RequireStepUp
    public OnboardingView reinvite(@PathVariable UUID id) {
        return onboarding.requestReinvite(id);
    }
}
