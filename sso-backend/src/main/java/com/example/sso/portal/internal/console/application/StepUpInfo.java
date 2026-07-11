package com.example.sso.portal.internal.console.application;

import java.util.List;

/** Drives the SPA /stepup page: whether the pending app launch is ready, else the factors to collect. */
public record StepUpInfo(boolean ready, List<String> pendingFactors, String returnUrl) {
}
