/**
 * The machine-facing response API: the verbs a detection system (XDR/ITDR) calls on this IdP when it
 * suspects an account. Authenticated as an OAuth 2.0 {@code client_credentials} service principal, one
 * scope per verb, one subject per call.
 *
 * <p>Deliberately small. An API that can end any session and constrain any account collapses the whole SSO
 * estate if its credential leaks, so the surface is the least that closes the loop: end sessions, place a
 * reversible hold, lift one. Disabling an account is NOT here — it is indefinite, and indefinite is a
 * person's decision.
 */
@ApplicationModule
package com.example.sso.response;

import org.springframework.modulith.ApplicationModule;
