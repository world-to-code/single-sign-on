/**
 * Named interface for the application registry: the {@link ApplicationService} facade over the OIDC/SAML
 * client catalog, its descriptor/view DTOs, the {@code ApplicationSource} contributor port and the
 * {@code ApplicationDeletedEvent}. The persistence of clients stays module-internal.
 */
@NamedInterface("application")
package com.example.sso.portal.application;

import org.springframework.modulith.NamedInterface;
