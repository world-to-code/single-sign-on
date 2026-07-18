package com.example.sso.portal.internal.console.application;

import com.example.sso.portal.application.AppSessionParticipation;

/**
 * One application the signed-in user still holds a live SSO session with, as shown in the portal. The
 * server-side routing {@code sid} of {@link AppSessionParticipation} is deliberately dropped here — the
 * browser identifies an app to sign out by ({@code type}, {@code appId}), never by a session id.
 */
public record AppSessionView(String type, String appId, String name, boolean oneClickLogoutSupported) {

    static AppSessionView of(AppSessionParticipation participation) {
        return new AppSessionView(participation.type().name(), participation.appId(), participation.name(),
                participation.oneClickLogoutSupported());
    }
}
