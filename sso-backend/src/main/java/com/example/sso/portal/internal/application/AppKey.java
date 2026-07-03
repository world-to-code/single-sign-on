package com.example.sso.portal.internal.application;

import com.example.sso.portal.AppType;

/** The composite key ({@code type:id}) that indexes applications across protocol types (OIDC/SAML). */
final class AppKey {

    private AppKey() {
    }

    static String of(AppType type, String id) {
        return type + ":" + id;
    }
}
