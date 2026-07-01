package com.example.sso.portal;

import java.util.List;

/**
 * SPI implemented by each protocol module (OIDC in {@code admin}, SAML in {@code saml}) to publish
 * its launchable applications to the portal. Inverting the dependency this way lets the portal
 * aggregate applications without importing those modules (no module cycles).
 */
public interface ApplicationSource {

    List<ApplicationDescriptor> applications();
}
