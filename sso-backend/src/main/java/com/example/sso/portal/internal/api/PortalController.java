package com.example.sso.portal.internal.api;

import com.example.sso.portal.ApplicationView;
import com.example.sso.portal.internal.application.PortalService;
import com.example.sso.portal.internal.application.SessionConfigView;
import com.example.sso.portal.internal.application.StepUpInfo;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** End-user portal API: the applications the signed-in user may launch via SSO, and step-up state. */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
public class PortalController {

    private final PortalService portal;

    @GetMapping("/session-config")
    public SessionConfigView sessionConfig(Authentication authentication) {
        return portal.sessionConfig(authentication.getName());
    }

    @GetMapping("/apps")
    public List<ApplicationView> myApps(Authentication authentication) {
        return portal.myApps(authentication.getName());
    }

    @GetMapping("/stepup")
    public StepUpInfo stepup(Authentication authentication, HttpServletRequest request) {
        return portal.stepup(authentication, request);
    }
}
