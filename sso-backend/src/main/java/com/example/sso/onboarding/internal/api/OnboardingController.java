package com.example.sso.onboarding.internal.api;

import com.example.sso.onboarding.internal.application.OnboardingInvitationService;
import com.example.sso.onboarding.internal.application.SelfSignupService;
import com.example.sso.onboarding.internal.application.SignupView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated onboarding endpoints. {@code /apply} is self-service tenant signup — it records a
 * pending request and emails a one-time VERIFICATION link, provisioning nothing until the applicant proves
 * control of the email at {@code /activate} (which creates the org + admin with the chosen password). A
 * separate {@code /set-password} redeems an admin-issued onboarding invitation (the platform-admin flow).
 * Anonymous by design (the applicant has no account yet; the single-use token is the authorization).
 * State-changing, so CSRF applies and the auth rate-limit filter throttles these paths (tenant-spam / brute).
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final SelfSignupService selfSignup;
    private final OnboardingInvitationService invitations;

    @PostMapping("/apply")
    public ResponseEntity<SignupView> apply(@Valid @RequestBody CreateOnboardingRequest request) {
        return ResponseEntity.accepted().body(selfSignup.request(request.toSpec()));
    }

    @PostMapping("/activate")
    public ResponseEntity<SignupView> activate(@Valid @RequestBody SetPasswordRequest request) {
        return ResponseEntity.ok(selfSignup.activate(request.token(), request.password()));
    }

    @PostMapping("/set-password")
    public ResponseEntity<Void> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        invitations.redeem(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }
}
