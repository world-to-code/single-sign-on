package com.example.sso.portal.internal.application;

/** Whether the signed-in user may enter the admin console (assignment-based; drives the SPA affordance). */
public record AdminConsoleAccessView(boolean allowed) {
}
