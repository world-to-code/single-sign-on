package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.FactorChallenge;
import com.example.sso.auth.internal.application.FactorVerificationRequest;
import com.example.sso.auth.internal.application.ReauthService;
import com.example.sso.authpolicy.AuthFactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Step-up re-authentication for sensitive operations (prepare/verify a policy-allowed re-auth factor). */
@RestController
@RequestMapping("/api/auth/reauth")
@RequiredArgsConstructor
public class ReauthController {

    private final ReauthService reauth;

    @PostMapping("/{factor}/prepare")
    public FactorChallenge prepare(@PathVariable AuthFactor factor, HttpServletRequest request) {
        return reauth.prepare(factor, request);
    }

    @PostMapping("/{factor}/verify")
    public void verify(@PathVariable AuthFactor factor, @RequestBody FactorVerificationRequest verification,
                       HttpServletRequest request, HttpServletResponse response) {
        reauth.verify(factor, verification, request, response);
    }
}
