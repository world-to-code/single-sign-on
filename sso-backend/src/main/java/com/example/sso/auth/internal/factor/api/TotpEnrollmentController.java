package com.example.sso.auth.internal.factor.api;

import com.example.sso.auth.internal.factor.application.FactorChallenge;
import com.example.sso.auth.internal.factor.application.FactorVerificationRequest;
import com.example.sso.auth.internal.factor.application.TotpEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Self-service TOTP authenticator enrollment for an already-signed-in user ("My Profile"). */
@RestController
@RequestMapping("/api/auth/factors/totp")
@RequiredArgsConstructor
public class TotpEnrollmentController {

    private final TotpEnrollmentService totp;

    @PostMapping("/setup")
    public FactorChallenge setup(HttpServletRequest request) {
        return totp.setup(request);
    }

    @PostMapping("/setup/confirm")
    public void confirmSetup(@RequestBody FactorVerificationRequest verification, HttpServletRequest request) {
        totp.confirmSetup(verification, request);
    }

    @DeleteMapping
    public ResponseEntity<Void> disable() {
        totp.disable();
        return ResponseEntity.noContent().build();
    }
}
