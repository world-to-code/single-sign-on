package com.example.sso.portal.internal.console.application;

import java.util.List;

/** Client-side session timers for the SPA: idle logout, periodic re-auth cadence, and its factors. */
public record SessionConfigView(int idleTimeoutMinutes, int reauthIntervalMinutes, List<String> reauthFactors) {
}
