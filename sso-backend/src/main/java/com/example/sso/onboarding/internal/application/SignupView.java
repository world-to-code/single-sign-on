package com.example.sso.onboarding.internal.application;

/** What the public self-service signup returns to an anonymous caller: just the (normalized) workspace slug,
 *  echoed so the SPA can show "check your email to finish {slug}" and, after activation, "{slug} is ready". */
public record SignupView(String slug) {
}
