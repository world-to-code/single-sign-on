package com.example.sso.portal.internal.console.api;

import com.example.sso.portal.application.ApplicationView;
import com.example.sso.portal.internal.console.application.AdminConsoleAccessView;
import com.example.sso.portal.internal.console.application.AppSessionService;
import com.example.sso.portal.internal.console.application.AppSessionView;
import com.example.sso.portal.internal.console.application.PortalService;
import com.example.sso.portal.internal.console.application.SessionConfigView;
import com.example.sso.portal.internal.console.application.StepUpInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** End-user portal API: the applications the signed-in user may launch via SSO, and step-up state. */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalController {

    private final PortalService portal;
    private final AppSessionService appSessions;

    @GetMapping("/session-config")
    public SessionConfigView sessionConfig(Authentication authentication) {
        return portal.sessionConfig(authentication.getName());
    }

    @GetMapping("/apps")
    public List<ApplicationView> myApps(Authentication authentication) {
        return portal.myApps(authentication.getName());
    }

    @GetMapping("/admin-console/access")
    public AdminConsoleAccessView adminConsoleAccess(Authentication authentication) {
        return portal.adminConsoleAccess(authentication.getName());
    }

    /** The apps the signed-in user's own sessions still hold a live SSO session/token with (portal goal ③). */
    @GetMapping("/app-sessions")
    public List<AppSessionView> appSessions(Authentication authentication) {
        return appSessions.list(authentication.getName());
    }

    /**
     * Signs the user out of ONE app from the IdP (OIDC back-channel / SAML SOAP SLO) without ending their
     * session or their other apps; returns their remaining app sessions.
     */
    @PostMapping("/app-sessions/logout")
    public ResponseEntity<List<AppSessionView>> logoutApp(Authentication authentication,
            @Valid @RequestBody AppSessionLogoutRequest request) {
        appSessions.logout(authentication.getName(), request.appType(), request.appId());
        return ResponseEntity.ok(appSessions.list(authentication.getName()));
    }

    @GetMapping("/stepup")
    public StepUpInfo stepup(Authentication authentication, HttpServletRequest request) {
        return portal.stepup(authentication, request);
    }
}
