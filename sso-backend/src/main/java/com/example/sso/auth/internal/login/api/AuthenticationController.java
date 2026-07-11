package com.example.sso.auth.internal.login.api;

import com.example.sso.auth.internal.profile.api.ChangePasswordRequest;

import com.example.sso.auth.internal.login.application.AuthSessionView;
import com.example.sso.auth.internal.login.application.AuthenticationService;
import com.example.sso.auth.internal.reauth.application.ResumeService;
import com.example.sso.auth.internal.reauth.application.ResumeView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The core login flow (session probe, identifier-first + password sign-in, logout, finalize, resume). */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final AuthenticationService authentication;
    private final ResumeService resumeService;

    @GetMapping("/session")
    public AuthSessionView session(HttpServletRequest httpRequest) {
        return authentication.session(httpRequest);
    }

    @PostMapping("/organization")
    public AuthSessionView organization(@Valid @RequestBody OrganizationRequest request,
                                        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authentication.organization(request.slug(), httpRequest, httpResponse);
    }

    @PostMapping("/identify")
    public AuthSessionView identify(@Valid @RequestBody IdentifyRequest request,
                                    HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authentication.identify(request.email(), httpRequest, httpResponse);
    }

    @PostMapping("/login")
    public AuthSessionView login(@Valid @RequestBody LoginRequest request,
                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authentication.login(request.username(), request.password(), httpRequest, httpResponse);
    }

    /** Logs out. {@code samlLogoutRedirect} is a URL the SPA should navigate to for front-channel SAML
     *  Single Logout (a browser redirect chain), or null when there are no front-channel SPs. */
    @PostMapping("/logout")
    public LogoutResult logout(HttpServletRequest request, HttpServletResponse response) {
        return new LogoutResult(authentication.logout(request, response).orElse(null));
    }

    public record LogoutResult(String samlLogoutRedirect) {
    }

    @PostMapping("/complete")
    public AuthSessionView complete(HttpServletRequest request, HttpServletResponse response) {
        return authentication.complete(request, response);
    }

    /** First-login forced reset: the authenticated user replaces their temporary password, finalizing login. */
    @PostMapping("/change-password")
    public AuthSessionView changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                          HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        return authentication.changePassword(request.password(), httpRequest, httpResponse);
    }

    @GetMapping("/resume")
    public ResumeView resume(HttpServletRequest request, HttpServletResponse response) {
        return resumeService.resume(request, response);
    }
}
