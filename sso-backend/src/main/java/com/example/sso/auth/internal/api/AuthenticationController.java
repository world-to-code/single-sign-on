package com.example.sso.auth.internal.api;

import com.example.sso.auth.internal.application.AuthSessionView;
import com.example.sso.auth.internal.application.AuthenticationService;
import com.example.sso.auth.internal.application.ResumeService;
import com.example.sso.auth.internal.application.ResumeView;
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

    @GetMapping("/resume")
    public ResumeView resume(HttpServletRequest request, HttpServletResponse response) {
        return resumeService.resume(request, response);
    }
}
