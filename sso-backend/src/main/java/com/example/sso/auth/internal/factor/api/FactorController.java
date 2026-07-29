package com.example.sso.auth.internal.factor.api;

import com.example.sso.auth.internal.login.application.AuthSessionView;
import com.example.sso.auth.internal.factor.application.FactorChallenge;
import com.example.sso.auth.internal.factor.application.FactorStepService;
import com.example.sso.auth.internal.factor.application.FactorVerificationRequest;
import com.example.sso.authpolicy.factor.AuthFactor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * Whether the code this session last asked for failed to go out.
     *
     * <p>{@code prepare} answers before the send has happened — deliberately, so that sending a code is never
     * measurably slower than not sending one — so the screen that is now waiting for a text has no other way to
     * learn that none is coming. Without this the only report was on a submitted code, which nobody submits
     * when nothing arrived.
     *
     * <p>Scoped to the caller's own session, so it says nothing about anybody else's number.
     */
    @GetMapping("/{factor}/delivery")
    public FactorDeliveryView delivery(@PathVariable AuthFactor factor, HttpServletRequest request) {
        return new FactorDeliveryView(factorStep.deliveryFailed(factor, request));
    }

    @PostMapping("/{factor}/verify")
    public AuthSessionView verify(@PathVariable AuthFactor factor, @RequestBody FactorVerificationRequest verification,
                                  HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return factorStep.verify(factor, verification, httpRequest, httpResponse);
    }
}
