package com.example.sso.auth.internal.reauth.application;

/** The post-login redirect target recovered from the saved request (or "/" when none). */
public record ResumeView(String redirectUrl) {
}
