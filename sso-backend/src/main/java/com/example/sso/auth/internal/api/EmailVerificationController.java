package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.EmailVerificationFlow;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user re-proves their own email address, which an admin edit leaves unverified (and therefore
 * unusable for the EMAIL one-time-code factor).
 */
@RestController
@RequestMapping("/api/auth/email-verification")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationFlow verification;

    /** Mails a one-time code to the address currently on the account. */
    @PostMapping
    public ResponseEntity<Void> request() {
        verification.request();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody EmailVerificationRequest request) {
        verification.confirm(request.code());
        return ResponseEntity.noContent().build();
    }
}
