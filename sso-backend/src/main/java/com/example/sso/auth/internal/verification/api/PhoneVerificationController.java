package com.example.sso.auth.internal.verification.api;

import com.example.sso.auth.internal.verification.application.PhoneVerificationFlow;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service phone enrollment for the SMS one-time-code factor: a signed-in user records a number, proves
 * control of it with a texted code, and can remove it. Mirrors {@code EmailVerificationController}.
 */
@RestController
@RequestMapping("/api/auth/phone-verification")
@RequiredArgsConstructor
public class PhoneVerificationController {

    private final PhoneVerificationFlow verification;

    /** Records the number (unverified) and texts a one-time code to it. */
    @PostMapping
    public ResponseEntity<Void> request(@Valid @RequestBody PhoneEnrollmentRequest request) {
        verification.request(request.phoneNumber());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody PhoneVerificationRequest request) {
        verification.confirm(request.code());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> remove() {
        verification.remove();
        return ResponseEntity.noContent().build();
    }
}
