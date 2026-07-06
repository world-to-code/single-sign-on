package com.example.sso.onboarding.internal.api;

import com.example.sso.onboarding.internal.application.OnboardingInvitationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint for redeeming a tenant-onboarding invitation: an invited org admin sets their password
 * and activates their account. Unauthenticated by design — the invitee has no credentials yet; the
 * high-entropy, single-use, time-boxed token is the authorization. State-changing, so CSRF applies and the
 * auth rate-limit filter throttles brute force.
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingInvitationService invitations;

    @PostMapping("/set-password")
    public ResponseEntity<Void> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        invitations.redeem(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }
}
