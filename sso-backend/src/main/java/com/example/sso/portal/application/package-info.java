/**
 * Named interface for the application registry: the {@link ApplicationService} facade over the OIDC/SAML
 * client catalog, its descriptor/view DTOs, the {@code ApplicationSource} contributor port and the
 * {@code ApplicationDeletedEvent}. Also carries the "active app sessions" port ({@code AppSessionSource} +
 * {@code AppSessionParticipation}) — each protocol contributes the apps a user's session still holds and the
 * single-participant logout for goal ③. The persistence of clients stays module-internal.
 */
@NamedInterface("application")
package com.example.sso.portal.application;

import org.springframework.modulith.NamedInterface;
