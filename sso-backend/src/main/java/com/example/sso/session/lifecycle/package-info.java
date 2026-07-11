/**
 * Named interface for live-session lifecycle: enumerate and terminate a user's sessions ({@link UserSessions},
 * {@link SessionLifecycle}), read/write session metadata, label devices, and enforce step-up freshness
 * ({@code StepUpInterceptor}). The Redis-backed session store and cookie wiring stay module-internal.
 */
@NamedInterface("lifecycle")
package com.example.sso.session.lifecycle;

import org.springframework.modulith.NamedInterface;
