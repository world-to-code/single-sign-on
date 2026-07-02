package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.AuthSessionView;
import com.example.sso.auth.internal.application.FactorChallenge;
import com.example.sso.auth.internal.application.FactorStepService;
import com.example.sso.auth.internal.application.FactorVerificationRequest;
import com.example.sso.authpolicy.AuthFactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login-time factor stepping: dispatches prepare/verify to the per-factor strategy via the service. */
@RestController
@RequestMapping("/api/auth/factors")
@RequiredArgsConstructor
public class FactorController {

    private final FactorStepService factorStep;

    @PostMapping("/{factor}/prepare")
    public FactorChallenge prepare(@PathVariable AuthFactor factor, HttpServletRequest request) {
        return factorStep.prepare(factor, request);
    }

    @PostMapping("/{factor}/verify")
    public AuthSessionView verify(@PathVariable AuthFactor factor, @RequestBody FactorVerificationRequest verification,
                                  HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return factorStep.verify(factor, verification, httpRequest, httpResponse);
    }
}
