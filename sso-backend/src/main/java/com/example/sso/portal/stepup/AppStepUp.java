package com.example.sso.portal.stepup;

import com.example.sso.portal.application.AppType;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * Behavioral entry point for the app step-up session state, so other modules never reach into
 * portal's session-attribute layout. The attribute keys and freshness-clock format stay owned here
 * (see {@link AppStepUpFilter}).
 */
@Component
public class AppStepUp {

    /**
     * If an app launch is pending in the session, (re)starts that app's step-up freshness window — so a
     * factor just verified as part of the launch satisfies the per-app policy. A no-op when no app is pending.
     */
    public void stampIfPending(HttpSession session) {
        if (session == null) {
            return;
        }

        Object type = session.getAttribute(AppStepUpFilter.APP_TYPE);
        Object appId = session.getAttribute(AppStepUpFilter.APP_ID);
        if (type instanceof String t && appId instanceof String id) {
            session.setAttribute(AppStepUpFilter.stepUpTimeKey(AppType.valueOf(t), id), System.currentTimeMillis());
        }
    }
}
